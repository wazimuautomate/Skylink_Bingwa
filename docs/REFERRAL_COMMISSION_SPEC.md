# Referral & Commission System — Implementation Spec

Status: **DESIGN AGREED, NOT BUILT.** The decisions in §1 were taken by the
product owner on 2026-08-28 and are locked unless the owner changes them
explicitly.

This spec covers a single-level referral programme: a customer shares a referral
code, people who onboard with that code become their referees, and the referrer
earns a commission on every purchase those referees pay for. Once the balance
reaches a configured threshold the referrer withdraws it to M-Pesa through Daraja
B2C, with no phone call and no manual step by the owner.

The system moves real money out of the business's account without a human in the
loop. Every rule below exists because of a specific way that can go wrong.

---

## 1. Locked decisions

| # | Decision | Chosen |
|---|---|---|
| 1 | Commission rate model | **Per-offer percentage with a global default.** Default rate in settings; overridable per offer and per category in the admin panel. |
| 2 | First-purchase signup bonus | **None.** Commission only. May be revisited after phase 1 with real data. |
| 3 | Minimum withdrawal | **Ksh 200.** The business absorbs the M-Pesa B2C transaction charge; the referrer receives the full amount shown in the app. |
| 4 | Phone verification (OTP) | **Lazy.** Onboarding is unchanged. OTP is required the first time a user opens the Earn screen or requests a withdrawal. |
| 5 | Referral depth | **Single level only.** You earn from people you referred, never from their referrals. Multi-level is permanently out of scope. |
| 6 | Attribution subject | **The payer**, not the bundle recipient. |
| 7 | Money representation | **Signed integer cents (`BIGINT`).** No `FLOAT` or `DOUBLE` anywhere. |

### 1.1 Consequence of decisions 2 + 3 that the owner accepted

With no signup bonus, a Ksh 200 threshold and an average basket around Ksh 60, a
referrer needs roughly 111 referee purchases at a 3% rate to reach their first
withdrawal. A referrer with one active referee waits months.

This is financially safe — unpaid balances stay small and the programme cannot
outrun its margin — but it means **the app must make progress feel visible**, or
people share once and stop. The mitigations cost nothing and are part of this
spec:

- The Earn screen shows a progress bar to the threshold, not just a balance.
- A push fires on every commission earned, and on crossing the threshold.
- The screen shows per-referee contribution, so a referrer knows who is active
  and who to nudge.

### 1.2 Open input required from the owner before phase 1 ships

**The real per-offer margin.** The commission rate must be a fraction of actual
margin. Bingwa reseller margins are thin and differ sharply between a Ksh 5 SMS
bundle and a Ksh 1,000 data bundle. Until the owner supplies real margin figures
the global default rate stays at **0%** (the feature accrues nothing) and the
admin UI refuses to save a rate that exceeds the recorded margin for that offer.

---

## 2. Scope

### In scope

- Server-generated referral codes, one per registered customer.
- Optional referral-code entry during app onboarding, validated live when online.
- SMS + push to the referrer when a referee completes onboarding.
- Commission accrual on every confirmed payment made by a referee.
- An append-only commission ledger as the sole source of financial truth.
- Lazy OTP phone verification binding a payout number.
- Self-service M-Pesa B2C withdrawal with full async result handling and
  automated reconciliation.
- Admin management: rates, thresholds, caps, kill switch, manual adjustments,
  stuck-withdrawal resolution, liability reporting — all audited.

### Out of scope

- Multi-level or tiered referrals.
- Referral leaderboards, badges, gamification.
- Withdrawal to any number other than the user's own verified number.
- Withdrawal as airtime or bundle credit (considered, not chosen).
- Customer accounts, passwords or cloud profile sync. Identity remains
  MSISDN-based; OTP verifies ownership of that MSISDN and nothing more.

---

## 3. Why identity has to change first

Every existing API call is authenticated by `X-App-Key`, a constant compiled into
the APK. It is extractable by anyone who decompiles the app.

That is tolerable today: the only thing a forged request to `stk.php` achieves is
pushing an STK prompt to the attacker's own phone. Nothing is gained and nothing
leaks.

**A withdrawal endpoint changes this completely.** A `POST /withdraw` protected
only by `X-App-Key` is a public, irreversible drain on the business's float. One
forged request moves real money to an attacker's number in seconds, and M-Pesa
B2C is not reversible.

Therefore:

1. Withdrawal, and the Earn screen generally, is gated on a **per-install bearer
   token** issued only after an OTP round-trip proves the user controls the
   MSISDN.
2. **The payout destination is the verified MSISDN stored on the server.** It is
   not a field in the withdrawal request. The app cannot influence it.
3. Changing the payout number requires a fresh OTP **and freezes withdrawals for
   48 hours**. This is the defence against a stolen handset or a SIM swap being
   used to drain an accrued balance.
4. `X-App-Key` stays as a coarse gate on the public read endpoints. It is never
   sufficient for anything that moves money.

---

## 4. Data model

New tables live in `admin-v2/database/migrations/` (022+) with the `{p}` prefix,
**and** carry a `CREATE TABLE IF NOT EXISTS` auto-provision guard in the API
layer — the same pattern `register_user.php` already uses — so `mybingwa-api`
keeps working on an install where the admin migrations have not run.

All timestamps are stored UTC (matching admin-v2's
`date_default_timezone_set('UTC')`) and displayed in Africa/Nairobi. All daily
caps and windows use the **Nairobi** calendar day, matching the existing
`offer_daily_allowance` convention.

### 4.1 `mb_referrers`

One row per customer who has a referral code. Created eagerly at registration.

```sql
CREATE TABLE IF NOT EXISTS {p}referrers (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id           BIGINT       NOT NULL,
    code                  VARCHAR(12)  NOT NULL,
    -- NULL until OTP verification. Withdrawal is impossible while NULL.
    verified_msisdn       VARCHAR(16)  NULL DEFAULT NULL,
    verified_at           DATETIME     NULL DEFAULT NULL,
    -- Set when the payout number changes; blocks withdrawal until it passes.
    payout_frozen_until   DATETIME     NULL DEFAULT NULL,
    -- CACHE ONLY. Truth is SUM(mb_commission_ledger.amount_cents).
    -- Written in the same transaction as every ledger insert; asserted nightly.
    balance_cents         BIGINT       NOT NULL DEFAULT 0,
    lifetime_earned_cents BIGINT       NOT NULL DEFAULT 0,
    lifetime_paid_cents   BIGINT       NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | EARN_BLOCKED | PAYOUT_BLOCKED | BANNED
    status_reason         VARCHAR(191) NOT NULL DEFAULT '',
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    UNIQUE KEY uniq_referrer_customer (customer_id),
    UNIQUE KEY uniq_referrer_code (code),
    KEY idx_referrer_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.2 `mb_referrals`

The referral graph. **Immutable once written.**

```sql
CREATE TABLE IF NOT EXISTS {p}referrals (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_customer_id  BIGINT      NOT NULL,
    referred_customer_id  BIGINT      NOT NULL,
    code_used             VARCHAR(12) NOT NULL,
    -- Hashed install identifier, for same-device abuse detection. Never raw.
    device_hash           CHAR(64)    NULL DEFAULT NULL,
    attributed_at         DATETIME    NOT NULL,
    first_purchase_at     DATETIME    NULL DEFAULT NULL,
    purchases_count       INT         NOT NULL DEFAULT 0,
    earned_cents          BIGINT      NOT NULL DEFAULT 0,
    -- UNIQUE on the REFERRED customer: one person has at most one referrer, ever.
    UNIQUE KEY uniq_referred (referred_customer_id),
    KEY idx_referrer (referrer_customer_id),
    KEY idx_device (device_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`uniq_referred` is what makes attribution first-write-wins at the database level,
rather than through an application check that can race.

### 4.3 `mb_commission_ledger`

Append-only. **Nothing in this table is ever updated or deleted.** A mistake is
corrected by writing a compensating entry, never by editing history.

```sql
CREATE TABLE IF NOT EXISTS {p}commission_ledger (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_id     BIGINT       NOT NULL,
    entry_type      VARCHAR(20)  NOT NULL,
        -- EARN | REVERSAL | WITHDRAW_HOLD | WITHDRAW_SETTLE
        -- | WITHDRAW_REFUND | ADJUST
    -- Signed. EARN and WITHDRAW_REFUND are positive; WITHDRAW_HOLD and REVERSAL
    -- are negative. WITHDRAW_SETTLE is zero — it converts a hold into a completed
    -- payout without moving the balance a second time.
    amount_cents    BIGINT       NOT NULL,
    -- When this entry becomes withdrawable. EARN entries mature after the
    -- configured hold window; every other type matures immediately.
    matures_at      DATETIME     NOT NULL,
    source_type     VARCHAR(20)  NOT NULL,   -- payment | withdrawal | admin
    source_id       BIGINT       NULL DEFAULT NULL,
    -- THE exactly-once guarantee. e.g. 'earn:payment:12345',
    -- 'hold:withdrawal:88'. A replayed callback or a re-run cron bounces off this
    -- index instead of double-crediting.
    idempotency_key VARCHAR(96)  NOT NULL,
    note            VARCHAR(191) NOT NULL DEFAULT '',
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    created_at      DATETIME     NOT NULL,
    UNIQUE KEY uniq_ledger_idem (idempotency_key),
    KEY idx_ledger_referrer (referrer_id, created_at),
    KEY idx_ledger_matures (referrer_id, matures_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Balances are always computed, never assumed:

```
total     = SUM(amount_cents) WHERE referrer_id = ?
available = SUM(amount_cents) WHERE referrer_id = ? AND matures_at <= UTC_TIMESTAMP()
```

`available` is the only figure a withdrawal may draw against. Because
`WITHDRAW_HOLD` is a negative entry that matures immediately, an in-flight
withdrawal is already excluded from `available` — there is no separate "pending"
bookkeeping to keep in sync, and therefore nothing that can drift out of sync.

### 4.4 `mb_withdrawals`

```sql
CREATE TABLE IF NOT EXISTS {p}withdrawals (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_id                 BIGINT       NOT NULL,
    amount_cents                BIGINT       NOT NULL,
    -- What Safaricom charged the business. Recorded for reporting; the referrer
    -- always receives amount_cents in full (decision 3).
    fee_cents                   BIGINT       NOT NULL DEFAULT 0,
    -- Snapshot of the verified number AT REQUEST TIME, so a later number change
    -- can never retarget an in-flight payout.
    msisdn                      VARCHAR(16)  NOT NULL,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'REQUESTED',
        -- REQUESTED | SUBMITTING | SUBMITTED | PAID | FAILED | UNKNOWN | CANCELLED
    -- WE generate this, and write it BEFORE calling Daraja. It is the idempotency
    -- key for the payout and the lookup key for TransactionStatus.
    originator_conversation_id  VARCHAR(64)  NOT NULL,
    conversation_id             VARCHAR(64)  NULL DEFAULT NULL,
    transaction_id              VARCHAR(32)  NULL DEFAULT NULL,
    mpesa_receipt               VARCHAR(32)  NULL DEFAULT NULL,
    result_code                 VARCHAR(12)  NULL DEFAULT NULL,
    result_desc                 VARCHAR(191) NULL DEFAULT NULL,
    submit_attempts             INT          NOT NULL DEFAULT 0,
    requested_at                DATETIME     NOT NULL,
    submitted_at                DATETIME     NULL DEFAULT NULL,
    resolved_at                 DATETIME     NULL DEFAULT NULL,
    updated_at                  DATETIME     NOT NULL,
    UNIQUE KEY uniq_originator (originator_conversation_id),
    KEY idx_withdrawal_status (status, submitted_at),
    KEY idx_withdrawal_referrer (referrer_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.5 `mb_outbox`

Notifications are queued, never sent inline from a money path. See §7.

```sql
CREATE TABLE IF NOT EXISTS {p}outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel         VARCHAR(8)   NOT NULL,   -- SMS | PUSH
    target          VARCHAR(255) NOT NULL,   -- msisdn or FCM token
    template        VARCHAR(40)  NOT NULL,
    payload         TEXT         NOT NULL,   -- JSON substitutions
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
        -- PENDING | SENT | FAILED | DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(191) NOT NULL DEFAULT '',
    -- Prevents a retried caller from queuing the same message twice.
    idempotency_key VARCHAR(96)  NOT NULL,
    next_attempt_at DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL,
    sent_at         DATETIME     NULL DEFAULT NULL,
    UNIQUE KEY uniq_outbox_idem (idempotency_key),
    KEY idx_outbox_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.6 `mb_otp_challenges`

```sql
CREATE TABLE IF NOT EXISTS {p}otp_challenges (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    msisdn        VARCHAR(16) NOT NULL,
    code_hash     CHAR(64)    NOT NULL,   -- SHA-256. The plain code is never stored.
    purpose       VARCHAR(24) NOT NULL,   -- VERIFY_PAYOUT | CHANGE_PAYOUT
    attempts      INT         NOT NULL DEFAULT 0,
    consumed_at   DATETIME    NULL DEFAULT NULL,
    expires_at    DATETIME    NOT NULL,
    created_at    DATETIME    NOT NULL,
    KEY idx_otp_msisdn (msisdn, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 4.7 `mb_device_tokens`

```sql
CREATE TABLE IF NOT EXISTS {p}device_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    token_hash   CHAR(64)    NOT NULL,   -- SHA-256 of the bearer token
    device_hash  CHAR(64)    NULL DEFAULT NULL,
    issued_at    DATETIME    NOT NULL,
    last_seen_at DATETIME    NOT NULL,
    revoked_at   DATETIME    NULL DEFAULT NULL,
    UNIQUE KEY uniq_token_hash (token_hash),
    KEY idx_token_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Only the hash is stored. A leaked database does not yield usable tokens.

### 4.8 Offer commission rates

Added to the existing offers table, with category defaults and a global default
in `mb_settings`:

```sql
ALTER TABLE offers ADD COLUMN commission_bps INT NULL DEFAULT NULL;
-- Basis points: 300 = 3.00%. NULL = fall back to category, then global default.
ALTER TABLE offers ADD COLUMN margin_bps     INT NULL DEFAULT NULL;
-- The owner's real margin. The admin UI refuses commission_bps > margin_bps.
```

Basis points rather than percentages, so the rate itself is an exact integer.

---

## 5. Referral codes

- **Generated server-side** at customer registration. Never by the app.
- 6 characters, Crockford base32 with `0 O 1 I L U` removed. Codes get read aloud
  over the phone and written on paper; confusable characters generate support
  calls and mis-attributed commission.
- Uppercase on display, matched case-insensitively on input.
- Generated with `random_bytes()`, inserted against the `UNIQUE` index, retried
  on collision. **Never derived from the MSISDN or the customer id** — a
  guessable code lets someone enumerate the customer base.
- The admin can assign a vanity code to a high-value referrer; it goes through
  the same uniqueness constraint.

---

## 6. Flows

### 6.1 Attribution — app onboarding

The onboarding screen gains an **optional** referral-code field.

While the user types (debounced, online only) the app calls a lightweight
`check_referral_code.php`. A valid code returns the referrer's **first name
only**, so the app can show *"You were referred by John."* — confidence for the
user without exposing another customer's full identity or number.

Attribution itself is decided by the server inside `register_user.php`, never by
the app. The code is rejected — silently, without blocking onboarding — when:

| Rejection | Reason |
|---|---|
| The code does not exist | A typo or a made-up code |
| The code belongs to the same MSISDN | Self-referral |
| The referred MSISDN already has a `mb_referrals` row | Attribution is permanent; a reinstall must not re-attribute |
| The referrer is `BANNED` | No new referees for a banned account |

**Onboarding never fails because of a bad referral code.** A first-run experience
blocked by a mistyped code is worse than a lost attribution. Offline, the code is
accepted optimistically and resolved when `register_user.php` eventually succeeds
on a later launch — the existing retry-on-next-launch behaviour already covers
this.

`register_user.php` stays idempotent on MSISDN. A reinstall bumps `registrations`
as it does today and leaves `mb_referrals` untouched.

### 6.2 The "your referral joined" notification

On the transition that actually creates the `mb_referrals` row — never on a
repeat registration — two outbox rows are queued: an FCM push and an SMS to the
referrer.

Rate limited to **N join-notifications per referrer per Nairobi day** (default
20). Without this, someone guessing codes can burn the business's SMS credit.

### 6.3 Accrual — the commission event

The only trustworthy money event in the system is the `PAYMENT_REQUESTED →
PAYMENT_CONFIRMED` transition in `mybingwa-api/callback.php`. That code is
**already** atomic and exactly-once: the `status <> 'PAYMENT_CONFIRMED'` guard
plus the `rowCount() === 1` check means exactly one caller observes the
transition, even when Daraja retries the callback. This spec reuses that
guarantee rather than inventing a second one beside it.

Inside the existing `$weConfirmed` branch, in the **same database transaction**:

1. Look up `payments.payer` in `mb_customers`.
2. Look that customer up in `mb_referrals`. No row → no commission, done.
3. Referrer status is `EARN_BLOCKED` or `BANNED` → no commission, done.
4. Resolve the rate: `offers.commission_bps` → category default → global default.
5. `commission = floor(payments.amount * 100 * bps / 10000)` in cents. Zero →
   done.
6. Insert an `EARN` ledger row with
   `idempotency_key = 'earn:payment:' . $payment['id']` and
   `matures_at = UTC_TIMESTAMP() + hold_hours`.
7. Bump `mb_referrers.balance_cents`, `lifetime_earned_cents` and the
   `mb_referrals` counters — in the same transaction.
8. Queue a push (not an SMS — see §7) if the referrer wants earn notifications.

Attribution follows **the payer**. On a buy-for-another purchase the payer is the
person who was referred and who spent the money. Attributing to the recipient
instead would let anyone farm a stranger's referral tree simply by buying a
bundle for one of their referees' numbers.

The unique `idempotency_key` means a duplicate Daraja callback, a manual re-run
or a reconciliation job replaying old payments cannot double-credit. If the
insert throws on the unique index, the accrual is already recorded — that is a
success, not an error, and must be handled as one.

**Never** let a notification failure roll back an accrual, and never let accrual
work delay the `200 OK` back to Daraja. A slow response invites Safaricom to
retry the callback, which puts pressure on the most important code path in the
business. Accrual is a fast local transaction; notification is a queued row.

### 6.4 Reversal

If a payment is later reversed, disputed or voided by an admin, a `REVERSAL`
entry of the exact negative amount is written with
`idempotency_key = 'reversal:payment:' . $id`. A balance may go negative; that is
correct and honest. Withdrawal is blocked while negative, and the position
recovers as the referrer earns again.

### 6.5 OTP verification (lazy)

Triggered the first time the user opens the Earn screen or requests a withdrawal.

```
POST /otp_request  { msisdn }        rate limited: 3/hour, 10/day per msisdn
                                     AND per source IP
   server: generate 6 digits, store SHA-256, expire in 10 minutes,
           queue the SMS via the outbox

POST /otp_verify   { msisdn, code }  max 5 attempts per challenge
   server: on success — issue a 32-byte random bearer token, store only its
           SHA-256 in mb_device_tokens, set verified_msisdn + verified_at
```

The plain OTP is never stored and never logged. The token is returned once and
kept by the app in DataStore. All Earn and withdrawal endpoints require
`Authorization: Bearer <token>` **in addition to** `X-App-Key`.

Changing the payout number repeats this flow with `purpose = CHANGE_PAYOUT` and
sets `payout_frozen_until = now + 48h`.

### 6.6 Withdrawal — the state machine

This is the highest-risk code in the business. The design assumption is that
**every** outbound call may time out, and that a timeout tells you nothing about
whether the money moved.

```
  ┌───────────┐  request accepted; WITHDRAW_HOLD written in the SAME txn
  │ REQUESTED │──────────────────────────────────────────────┐
  └───────────┘                                              │
        │ cron picks it up and locks the row                 │
        ▼                                                    │
  ┌────────────┐  OriginatorConversationID generated AND     │
  │ SUBMITTING │  PERSISTED **before** the Daraja call       │
  └────────────┘                                             │
        │ Daraja accepts (ResponseCode 0)                    │
        ▼                                                    │
  ┌───────────┐                                              │
  │ SUBMITTED │                                              │
  └───────────┘                                              │
     │      │  no ResultURL callback within 5 minutes        │
     │      └──────────────► ┌─────────┐                     │
     │                       │ UNKNOWN │ ── TransactionStatus │
     │                       └─────────┘    query, repeated   │
     │ ResultURL arrives           │        until resolved    │
     ▼                             ▼                          ▼
  ResultCode 0           resolves to PAID or FAILED    Daraja rejected
     │                                                  up front
     ▼                                                       │
  ┌──────┐                             ┌────────┐             │
  │ PAID │  WITHDRAW_SETTLE            │ FAILED │◄────────────┘
  └──────┘  (the hold becomes          └────────┘
             permanent)                  WITHDRAW_REFUND
                                         (the hold is released)
```

Non-negotiable rules:

1. **The hold is written in the same transaction that creates the withdrawal
   row.** Never "check the balance, then pay" — that races and overdraws.
2. **`originator_conversation_id` is generated by us and persisted before the
   outbound call.** It is the payout's idempotency key. A retry reuses the *same*
   value. Generating a fresh one on retry is exactly how a system pays twice.
3. **A timed-out or errored HTTP call moves the row to `UNKNOWN`** — never back
   to `REQUESTED`, and never to `FAILED`. `UNKNOWN` is resolved only by an
   authoritative answer from Daraja's `TransactionStatus` API, never by a guess
   and never by a timer.
4. **`UNKNOWN` never auto-refunds.** Releasing a hold on a payout that actually
   succeeded pays the user twice.
5. **Amount and destination are server-side.** The request body carries the
   amount only so the server can reject a mismatch; `msisdn` is read from
   `verified_msisdn` and never from the request.
6. **One in-flight withdrawal per referrer**, enforced inside a
   `SELECT ... FOR UPDATE` on the `mb_referrers` row.
7. `ResultURL` is authenticated the same way `callback.php` already is —
   Safaricom source-IP allowlist, fail closed, ack-and-ignore on failure.
8. The `ResultURL` handler is guarded by `status IN ('SUBMITTED','UNKNOWN')` in
   its `UPDATE ... WHERE`, so duplicate results settle exactly once.

Preconditions checked at request time, all server-side:

- `verified_msisdn IS NOT NULL`
- `status = 'ACTIVE'`
- `payout_frozen_until` is null or past
- `available >= min_withdrawal_cents` (default Ksh 200)
- the amount is within the per-transaction and per-day caps
- the per-referrer cooldown has elapsed (default 24h — each payout costs the
  business a B2C charge that it absorbs, so many tiny withdrawals are pure loss)
- the business-wide daily payout cap is not exhausted (circuit breaker)
- the global payouts kill switch is off

### 6.7 Float

B2C pays from the funded utility account. Till and Paybill collections do **not**
automatically fund it. A 15-minute cron polls Daraja's `AccountBalance` API and
alerts the admin below a configured floor.

When the float is below the floor, withdrawal requests are still **accepted and
held** but not submitted, and the app reports an honest "processing, this may
take a little longer" rather than a failure. Silently failing payouts at 2am is
how you lose users' trust in a single night.

---

## 7. Notifications

`send_mocked_mpesa_sms` is called inline today, and that is acceptable for one
best-effort message. It is not acceptable for a referral system running 24/7: a
slow SMS provider inside the Daraja callback stalls the callback, Safaricom
retries, and the pressure lands on the payment path.

Everything goes through `mb_outbox` and a 1-minute cron sender with exponential
backoff (1m, 5m, 30m, 2h, 6h) and a `DEAD` terminal state after 5 attempts.

| Event | Push | SMS | Why |
|---|---|---|---|
| Referee completed onboarding | Yes | Yes | High value; drives sharing. Rate limited. |
| Commission earned | Yes | No | Frequent. SMS here would be the largest running cost in the system. |
| Threshold reached — you can withdraw | Yes | Yes | The moment that converts an accrual into an action. |
| Withdrawal paid | Yes | Yes | Money moved. Must reach the user even with the app uninstalled. |
| Withdrawal failed | Yes | Yes | Must be unmissable, and must state that the funds are back in the balance. |
| Withdrawal under review | Yes | No | Rare and non-urgent. |

SMS uses the existing blazetechscope integration and the registered `MYBINGWA`
sender id.

---

## 8. Fraud controls

| Attack | Control |
|---|---|
| Self-referral | Code owner MSISDN ≠ new MSISDN; a matching `device_hash` flags for review |
| Reinstall farming | `uniq_referred` makes attribution permanent; the existing `registrations` counter surfaces the pattern |
| Forged withdrawal request | Bearer token from OTP; payout to `verified_msisdn` only |
| SIM swap / stolen handset | 48h `payout_frozen_until` after any payout-number change |
| Buy → earn → withdraw → dispute | `matures_at` hold window (default 24h) before funds are withdrawable |
| Double payout on timeout | Our `originator_conversation_id`, no auto-retry, `TransactionStatus` resolution |
| Concurrent withdrawal race | `SELECT ... FOR UPDATE` plus the one-in-flight rule |
| Code enumeration | Random codes, not derived; `check_referral_code.php` rate limited per IP and returns a first name only |
| SMS credit burn | Per-referrer daily cap on join notifications; per-MSISDN and per-IP OTP rate limits |
| Farm rings | Velocity rules, below |

**Velocity rules** — breaching any of these moves the account to
`PAYOUT_BLOCKED` with a reason, queues an admin alert, and leaves the balance
intact:

- more than N referrals attributed in one Nairobi day (default 15)
- more than Ksh X earned in one Nairobi day (default 500)
- three or more referees sharing one `device_hash`
- a withdrawal request within minutes of an account's first-ever earning

A delayed payout pending review is recoverable. A drained float is not.

---

## 9. Admin surface (admin-v2)

All money-affecting actions go through the existing `App\Core\Audit` trail and
RBAC. **No admin action writes a balance directly** — an adjustment is an
`ADJUST` ledger row like everything else.

- **Referrals dashboard** — top referrers, referral counts, conversion (referees
  who bought at least once), total accrued, total paid.
- **Outstanding liability** — `SUM(available)` across all referrers, shown
  prominently. This is money the business owes, and it grows silently.
- **Referrer detail** — code, verification state, ledger history, referee list,
  withdrawal history, status controls.
- **Withdrawals queue** — filterable by status, with `UNKNOWN` and `FAILED`
  surfaced first. Per-row actions: force a `TransactionStatus` re-query, mark
  resolved after manual investigation, cancel a `REQUESTED` row (which refunds
  the hold).
- **Settings** — global rate, per-category rates, minimum withdrawal, hold hours,
  cooldown, per-day caps, business-wide daily cap, float floor, **and a payouts
  kill switch**.
- **Offer editor** — `commission_bps` and `margin_bps`, with the UI refusing a
  commission above the recorded margin.
- **Alerts** — a dashboard banner for: float below floor, any withdrawal
  `UNKNOWN` for over an hour, ledger-versus-cache drift, and velocity blocks
  awaiting review.

---

## 10. Operations — running this 24/7

Shared cPanel hosting has no long-running workers, so everything is a short PHP
script on cron. Every job takes a MySQL `GET_LOCK` first and exits immediately if
another instance holds it — overlapping runs on a money system cause the exact
race conditions the rest of this spec exists to prevent.

| Schedule | Job | Purpose |
|---|---|---|
| every 1 min | outbox sender | SMS and push delivery with backoff |
| every 1 min | withdrawal submitter | `REQUESTED` → Daraja, respecting caps, float and kill switch |
| every 5 min | withdrawal reconciler | `SUBMITTED`/`UNKNOWN` older than 5 min → `TransactionStatus` |
| every 15 min | float monitor | `AccountBalance`; alert below floor |
| hourly | velocity scan | flag abusive patterns |
| nightly | ledger integrity | assert `balance_cents == SUM(ledger)` per referrer; alarm on drift |
| nightly | liability report | totals for the dashboard |

**Structured logging** on every money event, retained for at least a year: who,
what, how much, which idempotency key, and what Daraja returned. When something
goes wrong at 3am over a customer's Ksh 400, the log is the only thing that
settles it.

---

## 11. Phasing

Each phase is independently shippable and de-risks the next. Do not compress them.

| Phase | Contents | Risk if wrong |
|---|---|---|
| 1 | Schema, ledger, code generation, attribution, accrual. **No withdrawals.** The admin sees balances. The first payouts are made manually by the owner. | None — no money moves automatically. Proves the commission maths against real margin. |
| 2 | Outbox, SMS and push notifications, rate limits. | SMS cost only. |
| 3 | App Earn screen: code, share sheet, balance, progress to threshold, referee list, ledger history. Read-only. | None. |
| 4 | OTP verification, bearer tokens, payout-number binding, 48h freeze. | Auth bugs — but there is nothing to steal yet. |
| 5 | B2C in **Daraja sandbox**. Full state machine, reconciler, kill switch, admin queue, and the §12 test matrix passing. | Sandbox money only. |
| 6 | B2C production, launched with a low business-wide daily cap, ramped as it proves out. | Real money. Everything above exists to make this phase boring. |

B2C production requires its own Daraja Go-Live, a shortcode, an Initiator name
and a `SecurityCredential` (the initiator password encrypted with Safaricom's
public certificate). **That paperwork has lead time — start it during phase 1**,
not when phase 6 is otherwise ready.

---

## 12. Test matrix — required before phase 6

Money code that has not been tested against these cases is not finished.

**Accrual**

- A duplicate Daraja callback for the same payment credits exactly once.
- Two concurrent callbacks for different payments by the same referee both credit.
- A payment by a customer with no referrer credits nothing.
- A payment by a referee of an `EARN_BLOCKED` referrer credits nothing.
- Rate resolution order: offer → category → global.
- Commission rounding is floor, and never rounds up past the offer's margin.
- An accrual failure never blocks the `200 OK` to Daraja.

**Ledger**

- `balance_cents` equals `SUM(amount_cents)` after a randomised sequence of
  earns, reversals, holds, settles, refunds and adjustments.
- An `EARN` inside its hold window is excluded from `available`.
- A negative balance blocks withdrawal and recovers correctly on later earnings.

**Withdrawal**

- Two concurrent requests from one referrer: exactly one is accepted.
- A request for more than `available` is rejected with nothing written.
- A Daraja HTTP timeout → `UNKNOWN`, **no refund**, no second submission.
- `TransactionStatus` reports success on an `UNKNOWN` row → `PAID`, the hold
  settles, no double credit.
- `TransactionStatus` reports failure on an `UNKNOWN` row → `FAILED`, the hold
  refunds exactly once.
- A duplicate `ResultURL` callback settles exactly once.
- A `ResultURL` callback from a non-allowlisted IP changes nothing.
- Kill switch on → requests accepted, nothing submitted.
- Float below floor → held, not failed.
- A payout-number change → withdrawal blocked for 48h.
- An in-flight withdrawal targets the snapshot `msisdn`, not a number changed
  mid-flight.

**Attribution**

- A reinstall by a referee never re-attributes.
- A self-referral code is rejected without blocking onboarding.
- An invalid code never blocks onboarding.
- An offline onboarding with a code attributes correctly on the later retry.

---

## 13. Documentation amendments required

`CLAUDE.md` §2 currently lists, under locked v1 facts, *"Rewards, referrals,
credits and tokens are excluded from version 1"*, and lists *"Rewards, referrals
or tokens in version 1"* under explicit non-goals.

The product owner has explicitly overridden this. Per the source-of-truth
hierarchy in `CLAUDE.md` §1 the owner's current explicit instruction wins — but
the documents must be amended, or a future session will read the non-goal and
refuse the work:

- `CLAUDE.md` §2 — remove referrals from the non-goals; record the override.
- `docs/Plan.md` — add the referral/commission product scope and API contract.
- `docs/design.md` — the Earn screen, per "Calm Momentum".
- `docs/PRIVACY.md` — a referral graph plus payout records is personal-data
  processing under Kenya's Data Protection Act 2019. Disclose what is stored,
  why, and for how long.
- `memory.md` — record the decision, the override and the phase status.
- `CHANGELOG.md` — `[Unreleased]` entries per phase.

**Single-level only is a deliberate compliance choice, not a simplification.**
Multi-level cash commission in Kenya invites regulatory attention that should
never come near an M-Pesa shortcode the business depends on.
