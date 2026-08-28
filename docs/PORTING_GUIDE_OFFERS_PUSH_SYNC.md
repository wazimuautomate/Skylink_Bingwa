# Porting Guide: Offers, Admin Push Notifications & Sync

Extracted from the **Skylink Bingwa** codebase (Android app `skylink-bingwa/`, admin panel
`server/admin-v2/`, public API `server/mybingwa-api/`) on 2026-08-24, for reuse in a different
but similar app. Each section below is self-contained: what exists today, the actual
data/records where relevant, how it works end-to-end, and a concrete checklist for replicating
it elsewhere.

> **Read this first — two known gaps in the source app**, flagged so you don't silently port
> broken behavior as if it were working:
> 1. **`RemoteNotificationSelector.due()`** (admin-authored `GENERAL` messages fetched via
>    `get_app_notifications.php`) is fetched and cached on-device but **nothing calls it** to
>    actually post a notification. The wiring from "cached admin message" → posted notification
>    is incomplete in this app.
> 2. **`CatalogueSyncWorker.enqueueImmediateSync()`** exists (a "sync right now" one-off worker)
>    but **nothing calls it**. In particular, FCM push does **not** trigger an immediate sync —
>    that job is done entirely by a 90-second foreground poll (`ForceSyncWatcher`), not by push.
>
> Also **`SyncTrigger.APP_RESUME`** and **`SyncTrigger.MANUAL_REFRESH`** are defined with
> throttle windows in `SyncPlanner` but have no call site (no lifecycle-resume hook, no
> pull-to-refresh). Decide deliberately in the new app whether to finish these or drop them.

---

## 1. Offers

### 1.1 Data model (Kotlin, client-side)

`OfferItem` (`core/model/OfferItem.kt`) — the offer entity as consumed by the UI:

```kotlin
data class OfferItem(
    val id: String,                          // e.g. "data_6"
    val name: String,                        // e.g. "2GB"
    val allowance: String,                   // duplicate of name in current usage
    val priceKsh: Int,                       // integer price, KES
    val validity: String,                    // free text: "24 Hrs", "7 days", "30 days", "Midnight"
    val validityBand: String = "",           // "Hourly" | "Daily" | "Weekly" | "Monthly"
    val category: OfferCategory,             // DATA | SMS | MINUTES | SPECIAL (+ ALL/FAVOURITES as UI-only filters)
    val dailyRule: DailyRule,                // ONCE_PER_DAY("Buy once a day") | BUY_AGAIN_TODAY("Buy many times")
    val purchasePolicy: PurchasePolicy = ..., // MULTIPLE_PER_DAY | ONCE_PER_RECIPIENT_PER_DAY | MAX_PER_RECIPIENT_PER_DAY
    val maxPurchasesPerDay: Int? = null,
    val commercialLabel: String? = null,     // e.g. "Best value", "Popular", "Limited offer"
    val availableFromMinutes: Int? = null,   // selling-window start, minutes past midnight (Africa/Nairobi)
    val availableToMinutes: Int? = null,     // selling-window end
    val isPopular: Boolean = false,
    val isFavourite: Boolean = false,
    val isBoughtToday: Boolean = false,
    val description: String = "",
    val offlineInstructionsExpired: Boolean = false
)
```

Notes:
- **No icon/image field anywhere.** Category icons are Material Symbol font glyphs resolved by
  `OfferCategory`, not per-offer imagery.
- `OfferAvailability` and `OfferDailyState` are **not stored** — they're pure computed
  presentation (open/closed window, bought-today/purchases-left) derived at render time from
  `availableFromMinutes/To` and local purchase history.

### 1.2 Server-side schema (MySQL, admin-managed)

`{prefix}offers` (`server/admin-v2/database/migrations/002_offers.sql` + `018_offer_availability.sql`):

| column | type | meaning |
|---|---|---|
| id | INT AUTO_INCREMENT PK | internal row id |
| offer_id | VARCHAR(48) UNIQUE | app-facing stable id, e.g. `data_6` |
| category | VARCHAR(16) | `DATA` \| `SMS` \| `MINUTES` \| `SPECIAL` |
| name | VARCHAR(80) | display name/allowance |
| price | INT | KES |
| validity | VARCHAR(48) | free-text duration |
| band | VARCHAR(16) | `Hourly` \| `Daily` \| `Weekly` \| `Monthly` |
| daily_rule | VARCHAR(28) | `MULTIPLE_PER_DAY` \| `ONCE_PER_RECIPIENT_PER_DAY` \| `MAX_PER_RECIPIENT_PER_DAY` |
| max_per_day | INT NULL | cap when rule is MAX_PER_RECIPIENT_PER_DAY |
| commercial_tag | VARCHAR(40) | e.g. "Best value" |
| offline_eligible | TINYINT | buyable while device offline (Paybill/Till) |
| restrictions | VARCHAR(255) | free-text admin note |
| status | VARCHAR(12) | `active` \| `draft` \| `archived` |
| starts_at / ends_at | DATETIME NULL | scheduled visibility window |
| sort_hint | INT | manual ordering |
| row_version | INT | optimistic-locking counter |
| created_at / updated_at / updated_by | audit fields | |
| available_from / available_to | TIME NULL | Nairobi wall-clock selling window (migration 018) |

`{prefix}offer_revisions` — immutable audit trail: `offer_id`, `snapshot_json`, `action`
(create/update/archive/restore/delete), `actor_name`, `created_at`.

### 1.3 API endpoints

- **`GET server/mybingwa-api/get_offers.php`** — the endpoint the Android app actually calls.
  Requires header `X-App-Key: <shared secret>`. Serves the admin's published snapshot if one
  exists, else falls back to `SELECT ... FROM offers WHERE active=1 ORDER BY sort_order,
  category, price`. Response:
  ```json
  { "offers": [ { "id", "category", "name", "price", "validity", "band", "dailyRule",
                  "availableFrom", "availableTo" } ] }
  ```
  (`availableFrom/To` are `"HH:MM"` or `""`.)

- **`server/mybingwa-api/offers.php`** — not an HTTP endpoint; a `require`d PHP array
  `offerId => price` (int KES) used by the payment/STK-push handler so the **server**, never
  the client, is authoritative on price.

- **Admin CRUD** — `OffersController.php` + `OfferRepository.php` (`find`, `search`, `save`
  with optimistic locking via `row_version`, `setStatus`, `delete`, `nextOfferId` auto-generates
  ids like `data_14`). Validation: `category` in the fixed set; `name` ≤ 80 chars; `price` int
  1–100000; `validity` ≤ 48 chars; `daily_rule` in the fixed set; `available_from`/`available_to`
  must both be set or both be empty, and can't be equal.

- **Publish/snapshot shape** — `PublishingService::buildOffers()`
  (`server/admin-v2/app/Services/PublishingService.php`) is the canonical wire shape shipped in
  a published release:
  ```json
  {
    "id": "data_6", "category": "DATA", "name": "2GB", "price": 110,
    "validity": "24 Hrs", "band": "Daily",
    "dailyRule": "BUY_AGAIN_TODAY", "policy": "MULTIPLE_PER_DAY", "maxPerDay": null,
    "availableFrom": "", "availableTo": "",
    "commercialTag": "", "offlineEligible": true, "restrictions": ""
  }
  ```

### 1.4 Actual offer records (real seed data, 28 offers)

Found identically in **`server/mybingwa-api/offers.sql`** (SQL seed) and
**`skylink-bingwa/app/.../data/fake/FakeBingwaRepositoryImpl.kt`** (hardcoded offline fallback
catalogue). All rows `active=1`; provider is Safaricom throughout (single-carrier catalogue, no
multi-network field exists); no `available_from`/`available_to` set (always buyable in the seed).

**Data (12):**

| id | name | price (KES) | validity | band | rule |
|---|---|---|---|---|---|
| data_1 | 1GB | 19 | 1 Hr | Hourly | ONCE_PER_DAY |
| data_2 | 250MB | 20 | 24 Hrs | Daily | ONCE_PER_DAY |
| data_3 | 1.5GB | 50 | 3 Hrs | Hourly | ONCE_PER_DAY |
| data_5 | 1GB | 95 | 24 Hrs | Daily | ONCE_PER_DAY |
| data_6 | 2GB | 110 | 24 Hrs | Daily | BUY_AGAIN_TODAY |
| data_7 | 350MB | 49 | 7 days | Weekly | ONCE_PER_DAY |
| data_8 | 2.5GB | 300 | 7 days | Weekly | ONCE_PER_DAY |
| data_9 | 6GB | 700 | 7 days | Weekly | ONCE_PER_DAY |
| data_10 | 1.2GB | 250 | 30 days | Monthly | ONCE_PER_DAY |
| data_11 | 2.5GB | 500 | 30 days | Monthly | ONCE_PER_DAY |
| data_12 | 10GB | 1000 | 30 days | Monthly | ONCE_PER_DAY |
| data_13 | 8GB + 400 Min | 1005 | 30 days | Monthly | ONCE_PER_DAY |

**SMS (5)** — all `BUY_AGAIN_TODAY`:

| id | name | price | validity | band |
|---|---|---|---|---|
| sms_1 | 10 SMS | 5 | 24 Hrs | Daily |
| sms_2 | 200 SMS | 10 | 24 Hrs | Daily |
| sms_3 | 1,000 SMS | 30 | 7 days | Weekly |
| sms_4 | 1,500 SMS | 101 | 30 days | Monthly |
| sms_5 | 3,500 SMS | 201 | 30 days | Monthly |

**Minutes (8)** — all `BUY_AGAIN_TODAY`:

| id | name | price | validity | band |
|---|---|---|---|---|
| min_1 | 20 Min | 22 | Midnight | Daily |
| min_2 | 35 Min | 23 | 2 Hrs | Hourly |
| min_3 | 45 Min | 24 | 3 Hrs | Hourly |
| min_4 | 50 Min | 48 | Midnight | Daily |
| min_5 | 250 Min | 205 | 7 days | Weekly |
| min_6 | 100 Min | 105 | Midnight | Daily |
| min_7 | 300 Min | 499 | 30 days | Monthly |
| min_8 | 800 Min | 950 | 30 days | Monthly |

**Special (3):**

| id | name | price | validity | band | rule |
|---|---|---|---|---|---|
| spec_1 | 1GB | 21 | 1 Hr | Hourly | ONCE_PER_DAY |
| spec_2 | 1.5GB | 51 | 3 Hrs | Hourly | ONCE_PER_DAY |
| spec_3 | 2GB | 110 | 24 Hrs | Daily | BUY_AGAIN_TODAY |

> **Known discrepancy:** `server/mybingwa-api/offers.php`'s price map also defines
> `'data_4' => 55`, an offer id that appears in **no** seed row above — likely a retired offer
> whose price mapping was never cleaned up. Don't port it as if it's a real active offer.

**Decision for the new app:** treat this Safaricom price list as either (a) real placeholder
data to keep if the new app also resells Safaricom bundles, or (b) pure example data to replace
with the new app's own provider/catalogue — the *schema and endpoints* are the reusable part
either way.

### 1.5 How offers are used in the UI (brief)

- `OffersScreen.kt` — search, category filter chips, filter/sort sheet, `LazyColumn` of
  `OfferCard`s over the cached catalogue.
- `OfferCard.kt` — one row: category chip, favourite toggle, name+validity, price, availability
  line, "bought today" note, Buy button (swapped for "Opens HH:MM" outside the selling window,
  or "Bought today" if already purchased).
- `OfferAvailabilityDialog.kt` — modal shown when tapping a card outside its time window;
  explains when it reopens, no path to payment.
- `OfferRanker.kt` — pure, on-device, deterministic re-ranking by frequency/recency/favourite/
  category/time-fit against local purchase history; adds at most one quiet badge (e.g.
  "Buy again"). No server calls.
- `OfferSuggestionEngine.kt` — picks up to 3 "you may also want" offers, on-device, no network.

### 1.6 Advanced rule #1 — time-of-day selling windows

This is the feature you flagged: some offers (e.g. the 1GB/1-hour bundle) are only sellable
during a slot of the day, because the carrier itself won't deliver the bundle outside that
slot. **In the current seed data no offer actually has a window set** (`available_from`/
`available_to` are `NULL` for every row in `offers.sql` and in the app's hardcoded fallback
catalogue) — the feature is fully built end-to-end but sits dormant until an admin sets a
window on a specific offer. Don't assume `data_1`/`spec_1` (the two "1GB · 1 Hr" rows) have a
live window today; they *can* have one set at any time via the admin panel, and the app/server
already handle it correctly when one is.

**Storage:** `offers.available_from` / `offers.available_to`, `TIME NULL`, Africa/Nairobi wall
clock (migration `018_offer_availability.sql`). Both `NULL` (the default) = sold all day. Set
on the admin offer form as **"Sells from" / "Sells until"** (`server/admin-v2/app/Views/offers/form.php:91-99`),
optional, and a window is allowed to **cross midnight** (e.g. 22:00 → 02:00, open on both sides
of midnight — see below).

**Propagation:** admin sets window → publish → embedded in the release snapshot as
`availableFrom`/`availableTo` (`"HH:MM"` strings) → served by `get_offers.php` → cached
on-device as `OfferItem.availableFromMinutes`/`availableToMinutes` (minutes past midnight,
Africa/Nairobi).

**Client-side enforcement (presentation, `core/model/OfferAvailability.kt`):**
`offerAvailabilityAt(offer, nowMillis)` computes an `AvailabilityKind` (`ALWAYS` / `OPEN` /
`CLOSED`) purely from the two minute-of-day fields evaluated against the Nairobi minute-of-day
right now:
- Both fields null (or a server sent nonsense) → `ALWAYS`, label "Available any time".
- Inside the window → `OPEN`, e.g. `listLabel = "Available now · 5:00 PM – 11:00 PM"`.
- Outside the window → `CLOSED`, `listLabel = "Available from 5:00 PM to 11:00 PM"`,
  `chipLabel = "Opens 5:00 PM"`.
- This is what drives `OfferCard`'s Buy-button-vs-"Opens HH:MM"-chip swap, and what
  `OfferAvailabilityDialog` explains when a closed card is tapped (no path to payment from that
  dialog). The review/checkout step restates the window text too, and an offline purchase
  (Paybill/Till instructions shown without a live connection) of a currently-shut offer is
  withheld with the same explanation rather than silently allowed.

**Server-side enforcement (authoritative, `server/mybingwa-api/stk.php` + `lib.php`)** — this is
the part that matters most for a payments-accepting app, because the client-side check above is
only a UI convenience an attacker (or a stale cached catalogue) could bypass:
- `stk.php` calls `offer_rules($pdo, $offerId)` (`lib.php:152`) to re-read the window **from the
  server's own published snapshot** (never trusting anything the app sent), then
  `offer_window_open($rules)` (`lib.php:202`) **before** the payment row is claimed and **before**
  the M-Pesa/Daraja STK push is fired — so a refusal costs nothing: no prompt, no charge, no
  order to reconcile. Refusal response: `409 { status: PAYMENT_FAILED, errorCode:
  "OFFER_NOT_AVAILABLE_NOW", customerMessage: "This offer is only sold between 5:00 PM to 11:00 PM." }`.
- **Midnight-crossing window math** (`offer_window_open`, `lib.php:202-219`): if `from < to`, open
  when `now ∈ [from, to)`; if `from >= to` (the window crosses midnight, e.g. 22:00→02:00), open
  when `now >= from OR now < to`. Evaluated strictly on the **Nairobi** wall clock via PHP
  `DateTimeZone('Africa/Nairobi')`, never the host server's own timezone.

### 1.7 Advanced rule #2 — once-per-day / max-per-day per *recipient*

The daily-purchase rule is scoped to **the phone number receiving the bundle**, not the buyer,
not the device, and not a stored "already bought" flag — it's computed live from actual payment
rows every time, both on-device (for display) and on the server (for enforcement).

**Policy model** (`core/model/OfferItem.kt`):
```kotlin
enum class PurchasePolicy {
    MULTIPLE_PER_DAY,           // no cap — always "Buy again" (BUY_AGAIN_TODAY offers)
    ONCE_PER_RECIPIENT_PER_DAY, // exactly 1 per recipient per Nairobi day (ONCE_PER_DAY offers)
    MAX_PER_RECIPIENT_PER_DAY   // up to OfferItem.maxPurchasesPerDay per recipient per day
}
```
`purchasePolicy` defaults from the simpler `dailyRule` (`ONCE_PER_DAY`→`ONCE_PER_RECIPIENT_PER_DAY`,
`BUY_AGAIN_TODAY`→`MULTIPLE_PER_DAY`) but can be overridden per-offer to
`MAX_PER_RECIPIENT_PER_DAY` with an explicit `maxPurchasesPerDay` cap (admin sets both a
`daily_rule` select and a numeric `max_per_day` field on the offer form).

**Client-side state (`feature/home/CatalogueLogic.kt: dailyStateFor()`)** — purely
presentational, computed from this installation's own local purchase history for one
`(offer, recipientNumber)` pair at a time:
- Filters local `PurchaseRecord`s to this offer + this recipient (numbers normalised so
  `0712…`/`254712…`/`+254712…` match) + same Nairobi calendar day as `nowMillis`.
- Counts `RECEIVED` (confirmed) vs `WAITING_VERIFY` (payment sent, not yet confirmed)
  separately.
- `MULTIPLE_PER_DAY` → always `AVAILABLE`.
- `ONCE_PER_RECIPIENT_PER_DAY` → `AVAILABLE_TOMORROW` if already received today,
  `WAITING_VERIFY` if a payment is pending, else `AVAILABLE`.
- `MAX_PER_RECIPIENT_PER_DAY` → `left = max - received`; `AVAILABLE_TOMORROW` once exhausted,
  `WAITING_VERIFY` if the remaining slots are all currently pending, else `PURCHASES_LEFT` with
  a "N purchases left today" label.
- This state drives the offer-list copy ("Already bought today for 0712 345 678") and blocks
  the checkout sheet client-side with an explanation of both the rule and the workaround (buy
  again after midnight, or for a different number now) — but this is only UX; it is **not**
  the source of truth.

**Server-side enforcement (`stk.php` + `lib.php`, the actual source of truth):**
- `offer_daily_allowance($rules)` (`lib.php:319`) maps the policy to an integer cap: `1` for
  `ONCE_PER_RECIPIENT_PER_DAY`/legacy `ONCE_PER_DAY`, `maxPerDay` (or `1` if unset/invalid) for
  `MAX_PER_RECIPIENT_PER_DAY`, `null` (no cap) otherwise. It understands **both** the old
  per-offer `daily_rule` string and the newer `policy`/`maxPerDay` snapshot fields, so a
  cached-app-catalogue from either era is enforced identically.
- `recipient_purchases_today($pdo, $offerId, $recipient)` (`lib.php:290-316`) counts rows in
  `payments` for this offer + this recipient (last-9-digits match, so formatting differences
  don't create phantom "different" numbers) within **today in Nairobi**, counting:
  - `status = 'PAYMENT_CONFIRMED'` (settled), **plus**
  - `status = 'PAYMENT_REQUESTED'` created in the **last 10 minutes** — so a customer with an
    M-Pesa prompt still visible on their screen can't be sent a second STK push for the same
    once-a-day bundle before the first one even resolves.
  - The limited number is the **recipient**, not the payer — buying the same once-a-day bundle
    for a *different* number is always allowed regardless of who pays.
- If `alreadyToday >= allowance` → `409 { errorCode: "ALREADY_BOUGHT_TODAY", customerMessage:
  "This bundle can only be bought once a day for each number. It can be bought for this number
  again after midnight, or for a different number now." }` (or the N-times variant for
  `MAX_PER_RECIPIENT_PER_DAY`).
- **The reset is the Nairobi calendar-day boundary itself — never a stored flag, cron job, or
  scheduled reset task.** A purchase at 23:58 simply stops being counted at 00:00 because the
  day-bounds comparison changes, nothing is written or cleared.
- **Clock-skew safety** (`nairobi_day_bounds_db()`, `lib.php:247-278`): the app server, PHP's
  own clock, and MySQL's clock can all be on different timezones/hosts (common on shared
  hosting). This function *measures* the actual offset between PHP's Nairobi-computed wall time
  and the database's `NOW()` (rather than assuming they agree), rounds it to the nearest minute,
  and uses that measured shift to compute "today" bounds in the database's own clock — so the
  purchase-count query (`created_at >= start AND created_at < end`) always lands on the real
  Nairobi midnight regardless of what timezone the DB host is actually configured with.

### 1.8 Advanced rule #3 — price integrity & idempotency at checkout

Not strictly "offer" data, but load-bearing enough to port alongside the above or the two rules
above are meaningless:
- `stk.php` **recomputes the amount server-side** from `offerId` via `offer_price()` — the
  client's submitted amount is never trusted. Price resolves from the published snapshot first,
  then the legacy `offers` table, then the static `offers.php` map, in that order, so the
  displayed price and the charged price can never drift.
- An offer that isn't in the current published/active snapshot resolves to `null` price →
  `400 UNKNOWN_OFFER` — an unpublished offer simply stops being payable, no separate "disable"
  step needed.
- Both selling-window and daily-limit checks happen **before** the payment row is inserted and
  **before** Daraja/M-Pesa is called, in that order (window first, then daily cap) — a refusal
  is always free.
- The payment row itself is claimed idempotently: `INSERT` on a `UNIQUE(client_request_id)`
  column happens *before* calling Daraja; two concurrent identical requests race on that unique
  key, the loser's insert throws and the code returns the *existing* row's status instead of
  firing a second STK push or a 500.

### 1.9 Porting checklist

- [ ] Create an `offers` table matching §1.2 (or a simplified subset if the new app doesn't need
      scheduling/optimistic-locking/audit trail).
- [ ] Build a public, app-key-gated `GET /get_offers.php`-equivalent returning the §1.3 shape.
- [ ] If accepting payments, keep prices **server-authoritative** (mirror the `offers.php`
      id→price map pattern) — never trust a client-submitted price.
- [ ] Port `OfferItem`/`OfferCategory`/`OfferAvailability`/`OfferDailyState` models, `OfferCard`,
      `OfferAvailabilityDialog`, `OfferRanker`, `OfferSuggestionEngine` largely as-is — they're
      provider-agnostic.
- [ ] Replace the 28-offer Safaricom catalogue with the new app's own products, or keep as demo
      data if useful for early testing.
- [ ] Add `available_from`/`available_to TIME NULL` to the offers table (§1.6) if any product
      is sold only in a time window — port `offer_window_open()`/`offer_window_label()` from
      `lib.php` verbatim (the midnight-crossing math is easy to get subtly wrong) and enforce it
      **server-side before charging**, not just in the UI.
- [ ] Add a `daily_rule`/`policy` + `max_per_day` pair to the offers table (§1.7) if any product
      is purchase-limited per recipient per day — port `offer_daily_allowance()` +
      `recipient_purchases_today()` + `nairobi_day_bounds_db()` from `lib.php` verbatim; the
      measured-clock-offset trick in `nairobi_day_bounds_db()` is the part most likely to be
      silently wrong if reimplemented from scratch on different hosting.
- [ ] Recompute price server-side from the offer id at charge time (§1.8) — never trust a
      client-submitted amount — and claim idempotency via a `UNIQUE(client_request_id)` insert
      before calling the payment provider.
- [ ] Decide, per new-app product, which of the two rules above actually apply — nothing forces
      every offer to use them; in the source app most offers use neither (see §1.4's `NULL`
      windows and mixed `ONCE_PER_DAY`/`BUY_AGAIN_TODAY` split).

---

## 2. Admin Push Notifications

Two **distinct** systems coexist in the source app. This distinction is the most important
thing to preserve (or deliberately simplify away) when porting.

| | **System A — Instant Push** | **System B — Rule Engine** |
|---|---|---|
| Trigger | Admin clicks "Send" in the panel | On-device conditions (time of day, low balance, connectivity change, etc.) |
| Delivery | Real FCM message, immediate | Locally composed from templates, policy-gated |
| Policy applied | None beyond permanent per-message de-dup | Quiet hours, daily cap, per-category cooldown, dedup |
| Server code | `PushController` + `FcmService` | `NotificationsController` + `NotificationService` |
| Client code | `SkylinkBingwaFirebaseService` → `AppNotifier.postPush()` | `NotificationEngine` → `AppNotifier.postEngine()` |

### 2.1 Android side

**`SkylinkBingwaFirebaseService`** (extends `FirebaseMessagingService`, package root
`com.example`):
- `onNewToken()` → forwards the new FCM token to `repository.setFcmToken()`.
- `onMessageReceived()` → reads `title`/`body`/`route` **from `remoteMessage.data` only**
  (falls back to `remoteMessage.notification` for title only; returns early if there's no
  body). Calls `AppNotifier(context).postPush(...)` directly, and separately appends a
  `NotificationItem` to the in-app notification list. **This bypasses the rule engine entirely.**

**`AppNotifier`** (`core/notifications/AppNotifier.kt`) — thin `NotificationManagerCompat`
wrapper (plain `Context`, no DI). `postPush()`/`postOfferSuggestion()`/`postAppUpdate()` are
one-shot helpers permanently de-duped per-process by a `stableId`; `postEngine()` has no
de-dup (the engine owns that). `postInternal()` builds the `NotificationCompat.Builder` (brand
icon/color, `BigTextStyle`, auto-cancel, `PendingIntent` carrying `EXTRA_DEEP_LINK_ROUTE` that
`MainActivity` reads to navigate). No-ops silently on API 33+ without `POST_NOTIFICATIONS`
granted; never requests the permission itself.

**`NotificationChannels`** — 5 channels: `TRANSACTIONS` (default importance), `OFFERS`,
`REMINDERS`, `UPDATES`, `NEWS` (low importance/silent). Created idempotently, API 26+ gated.

**`core/notifications/engine/` package** (the rule engine, only needed if porting System B):
- `NotificationEngine` — single entry point; `notify()` (personalized, template-composed) and
  `notifyRaw()` (pre-authored copy, still policy-checked). Both: load state → policy pre-check
  → compose/use copy → hash content → policy check with hash → post → save state. Never throws.
- `NotificationComposer` — deterministic-by-seed template picker + `{token}` renderer
  (`{name}`, `{greeting}`, `{bundle}`, `{amount}`, `{balance}`, `{recipient}`, `{days}`,
  `{category}`); drops `{balance}` templates for non-balance-driven categories; avoids repeating
  the last template per category.
- `NotificationPolicy` — the spam guard: dedup by content hash → quiet hours 22:00–06:59
  (local tz, non-transactional only) → daily cap (6/day) → per-category cooldown (3h–48h);
  transactional categories (purchase success, bundle/gift received) bypass cap/cooldown/quiet
  hours but still dedupe.
- `NotificationCategory` — enum with `channelId`, `transactional` flag, `defaultRoute`,
  `isBalanceDriven`; unknown server-supplied category strings degrade to `GENERAL` rather than
  crashing.
- `NotificationStateStore` — per-category last-posted-at, last-template-id, daily counts, recent
  content hashes, persisted in its own DataStore.
- `NotificationTemplates`/`NotificationTemplateStore` — in-APK seed template set (versioned) +
  optional server-synced override, server wins only if its `version` is strictly higher.
- `RemoteNotifications` — admin-published `GENERAL` messages, fetched and cached but (per the
  gap noted at the top of this doc) **not currently wired to actually post**.

**`EngagementNotificationWorker`** — self-rescheduling one-shot `CoroutineWorker` chain (not
periodic) that posts morning/evening engagement nudges via `engine.notifyRaw()`.

### 2.2 Server side

**Instant Push:**
- `PushController` (`server/admin-v2/app/Controllers/PushController.php`) — `index()` shows FCM
  config status, distinct token count, last 20 broadcasts; `send()` validates
  `title`(≤120)/`body`(≤500)/`route`(from a fixed allow-list), calls `FcmService::broadcast()`,
  audit-logs the send.
- `FcmService` (`server/admin-v2/app/Services/FcmService.php`) — pure PHP, **FCM HTTP v1**, no
  Composer dependency (works on plain cPanel hosting):
  - Loads a Firebase service-account JSON from a configured path (`Config::get(
    'fcm.service_account_file')`), hand-signs an RS256 JWT, exchanges it for an OAuth2 bearer
    token via `POST https://oauth2.googleapis.com/token` (cached until ~2 min before expiry).
  - Builds a **data-only** envelope (`'data' => [title, body, route]`, **no** `notification`
    block) — deliberately, so `onMessageReceived()` always fires client-side and the SDK never
    auto-draws a tray notification on the wrong channel.
  - `dispatch()` → `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`;
    detects stale tokens (404/NOT_FOUND/UNREGISTERED) for pruning.
  - `broadcast()` sends per-token to every distinct `customers.fcm_token`, **plus** one send to
    the `all_users` topic (fan-out safety net for not-yet-registered devices), logs a
    `push_broadcasts` row, prunes stale tokens.
- View: `server/admin-v2/app/Views/push/index.php` — compose form + status card + history table.
- Migration `021_fcm_push.sql` — adds `customers.fcm_token` + index, creates `push_broadcasts`
  (title, body, deep_link_route, recipients_count, success_count, failure_count, created_by,
  created_at).

**Rule Engine (only needed if porting System B):**
- `NotificationsController` — CRUD for `notification_campaigns` + `notification_variations`
  (multiple wordings per campaign); validates category/trigger against catalogue tables, rejects
  unsupported `{{token}}`s, requires ≥1 variation before "active"; row actions toggle/duplicate/
  cancel/delete/testSend(records intent only)/preview.
- `NotificationService` — pure logic: `render()` (leaves unresolved `{{var}}` intact for
  on-device resolution — the server never learns personal data), `validateSchedule()`,
  `isWithinWindow()` (day/time-window logic), `describeSchedule()`.
- `PublishingService::buildNotifications()` — selects enabled+active campaigns, joins enabled
  variations, shapes into the published snapshot: `{ id, name, category, trigger, variations[],
  deepLink, startsOn, endsOn, daysOfWeek[], timeStart/timeEnd, cooldownMinutes, frequencyCap,
  respectQuietHours, suppressRecentPurchase, expiresAt }`.
- Migrations: `004_notifications.sql` (original schema), `014_notifications_v2.sql` (rule-based
  redesign: trigger_type, schedule window fields, `notification_variations`,
  `notification_categories`/`notification_trigger_types`/`notification_variables` catalogues).

**Public API** (`server/mybingwa-api/`):
- `get_app_notifications.php` — app-key gated; emits one item per active campaign using only
  its first variation, times converted to epoch millis.
- `get_notification_templates.php` — app-key gated; emits every enabled variation as a separate
  template (`id = "c{campaignId}-v{index}"`), tagged with the snapshot's config version.
- `register_user.php` — accepts `fcm_token` at registration; upserts without ever overwriting a
  known token with null (`ON DUPLICATE KEY UPDATE fcm_token = COALESCE(VALUES(fcm_token),
  fcm_token)`).

### 2.3 End-to-end flow

**Instant Push:** admin fills title/body/route → `PushController::send()` →
`FcmService::broadcast()` → one data-only FCM v1 message per registered token + one to
`all_users` topic → `onMessageReceived()` fires (data-only ⇒ always runs, foreground or
background) → `AppNotifier.postPush()` draws the tray notification + appends to the in-app
notification list, no engine policy applied → tapping launches `MainActivity` with the deep
link route.

**Rule engine:** admin authors a campaign + wordings → publish → embedded in the release
snapshot → app's periodic/foreground sync fetches `get_app_notifications.php` (→
`RemoteNotification` cache) and `get_notification_templates.php` (→ template set cache, wins
over the in-APK seed only if its version is higher) → when an on-device trigger fires (time of
day, low balance, purchase success, etc.), `NotificationComposer` picks wording from whichever
template set is current → `NotificationPolicy.shouldPost()` gates it → `AppNotifier.postEngine()`.

### 2.4 Setup checklist for a new app

**Firebase:**
- [ ] Create/select a Firebase project; add an Android app entry with the new app's real
      `applicationId` (and a `.debug`-suffixed variant if needed).
- [ ] Download `google-services.json` into the new app's `app/` module (git-ignore it; consider
      a CI stub-generation step, e.g. keyed off a `GOOGLE_SERVICES_JSON` env var, so builds
      don't fail without the real file).
- [ ] Firebase Console → Project Settings → Service Accounts → generate a new private key JSON
      — this is the **server-side** credential; never bundle it in the app or commit it to git.

**Android Gradle:**
- [ ] Root `build.gradle.kts`: `alias(libs.plugins.google.services) apply false` (plugin id
      `com.google.gms.google-services`).
- [ ] `app/build.gradle.kts`: apply the plugin; add
      `implementation(platform(libs.firebase.bom))` + `implementation(libs.firebase.messaging)`.
- [ ] `AndroidManifest.xml`: `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`
      permissions; register the service:
      ```xml
      <service android:name=".YourFirebaseService" android:exported="false">
          <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
      </service>
      ```

**Android source to port (rename package/app references):**
- [ ] `AppNotifier`, `NotificationChannels` (define the new app's own channel set).
- [ ] `core/notifications/engine/*` — only if the policy-gated rule engine is wanted; otherwise
      Instant Push alone is a much smaller lift.
- [ ] A `FirebaseMessagingService` subclass mirroring `SkylinkBingwaFirebaseService` — read from
      `data`, not `notification`, to match the data-only server envelope.
- [ ] `Application.onCreate()`: fetch `FirebaseMessaging.getInstance().token`, send to backend;
      `subscribeToTopic(<broadcast-topic>)` matching the server's broadcast topic string exactly.
- [ ] A registration/token-update API call, since `onNewToken()` alone can race a fresh
      install's onboarding flow.

**Server (PHP or equivalent) to port:**
- [ ] `FcmService`-equivalent — reusable almost as-is; **do not** hardcode a project-specific
      service-account filename fallback (the source app has one, `my-bingwa-b538e0f6c645.json`,
      that should not be copied verbatim).
- [ ] `PushController` + compose-form view — adjust the allowed `route` list to the new app's
      actual deep-link screens.
- [ ] Migration: `fcm_token` column on the customer/user table + a `push_broadcasts` history
      table.
- [ ] If porting the rule engine too: campaign/variation/category/trigger/variable tables, the
      controller+service, the `buildNotifications()` publishing step, and the two public API
      endpoints.
- [ ] A shared-secret `X-App-Key` request-auth pattern if the new API doesn't already have one.

**Credentials/env vars:**
- [ ] `GOOGLE_SERVICES_JSON` (optional CI convenience).
- [ ] Firebase service-account JSON on the server (path via config, e.g. `fcm.service_account_file`).
- [ ] App-key shared secret for the public API.

---

## 3. Sync (app ↔ server)

### 3.1 What gets synced

Seller config (Till/Paybill/support contacts), offer catalogue, home billboards/promotions,
notification wording templates, and admin-published in-app notifications. **Not** synced: user
behavior (purchases, favourites, recent recipients) — the device is the source of truth for
that; the server is the source of truth for the five items above. Sync is one-directional,
server → device.

### 3.2 Core client architecture

- **`SyncModels`** (pure Kotlin, no Android deps) — `SyncResource` enum (`CONFIG`, `OFFERS`,
  `BILLBOARDS`, `NOTIFICATION_TEMPLATES`, `REMOTE_NOTIFICATIONS` — enum name = manifest wire
  key), `ResourceVersion(version: Long, updatedAt: Long, checksum: String)`,
  `SyncManifest(publishVersion, generatedAt, resources: Map<String, ResourceVersion>)`,
  `SyncTrigger` enum (`APP_START`, `CONNECTIVITY_RESTORED`, `PERIODIC`, `MANUAL_REFRESH`,
  `APP_RESUME`, `FORCE_PUBLISH`), `RemoteSyncManifestSource` interface (`fetch(): SyncManifest?`,
  null = offline/unavailable).

- **`SyncPlanner`** (pure, JVM-unit-testable, no `android.*` import) — per-trigger throttle:
  `APP_START` 5 min, `CONNECTIVITY_RESTORED` 2 min, `APP_RESUME` 15 min, `PERIODIC`/
  `MANUAL_REFRESH`/`FORCE_PUBLISH` unthrottled. `plan()` compares `remote.publishVersion` to the
  last-seen value to detect a force-publish (bypasses throttle entirely); a resource is a
  candidate if it's unknown locally or `changed()` (compares **only** `version` + `checksum`,
  deliberately ignoring `updatedAt` so a shared publish timestamp doesn't mark everything
  changed); candidates are then filtered by the trigger's throttle window.

- **`SyncOrchestrator`** — `sync(trigger, now)` uses `Mutex.tryLock()`: a concurrent trigger
  during an in-flight sync is skipped, not queued. `runSync()`: load metadata → fetch manifest
  (null-safe) → `SyncPlanner.plan()` → dispatch each due resource to a `SyncTargets` method →
  only advance a resource's stored fingerprint on success → persist metadata (records
  `lastPublishVersion` even on an empty plan, so a no-op publish isn't retried forever). Never
  throws to the caller.

- **`SyncMetadataStore`** — its own Preferences DataStore (separate from user-behavior storage),
  Moshi-reflection JSON, whole-document read/write; never throws (load failure → empty state,
  save failure → swallowed).

- **`ContentSyncers`** — small collaborator for notification-engine-specific syncs; enforces the
  "offline safety" rule everywhere: a failed/null fetch keeps cached content, only an explicit
  empty list replaces it.

- **`CatalogueSyncWorker`** (`CoroutineWorker`) — looks up the orchestrator via a
  `SyncOrchestratorProvider` interface implemented by the `Application` class, so the background
  worker shares state with the UI. No provider → `Result.success()`; any error → `Result.retry()`.
  Scheduled via `PeriodicWorkRequestBuilder(6, HOURS)`, `NetworkType.CONNECTED`, exponential
  backoff, `enqueueUniquePeriodicWork(..., ExistingPeriodicWorkPolicy.KEEP)`.

- **`ForceSyncWatcher`** — the near-real-time path: polls the manifest every **90 seconds**
  while the app is foregrounded (hosted from a `LaunchedEffect`); if `publishVersion` differs
  from what's stored, calls `orchestrator.sync(SyncTrigger.FORCE_PUBLISH)`. Costs nothing in the
  background since it's only started while foregrounded.

- **`AndroidRemoteSyncManifestSource`** — Retrofit client for the manifest endpoint,
  `X-App-Key`-authenticated; every DTO field nullable-with-defaults; `fetch()` returns null on
  any failure (offline, non-2xx, malformed body, or an old server without the endpoint).

- **`ConnectivityObserver`** — dual `NetworkCallback` registration (default request + an
  INTERNET-capability request); only `NET_CAPABILITY_INTERNET` + `VALIDATED` counts as usable
  (defeats captive portals); **asymmetric debounce**: going offline is emitted immediately,
  coming online waits 400ms (cancelled by a newer event) to avoid announcing "online" mid
  network-association. `MainActivity` collects this to call
  `sync(SyncTrigger.CONNECTIVITY_RESTORED)` on the offline→online edge.

**Local persistence:** no Room database anywhere — everything is Jetpack **Preferences
DataStore**, JSON-encoded via **Moshi reflection** (no KSP/codegen), one document per store,
whole-document rewrite per change. Six independent stores exist (sync metadata, user-behavior
`LocalStore`, personalization, remote notifications, notification state, notification templates)
— deliberately kept separate so a corrupt sync-state file can never touch purchase history.

**Wiring:** `SkylinkBingwaApplication` implements `SyncOrchestratorProvider`, builds the
orchestrator/manifest-source/metadata-store lazily (only when a real base URL is configured),
schedules the periodic worker in `onCreate()`. `MainActivity` starts `ForceSyncWatcher`, collects
`ConnectivityObserver`, and fires one `APP_START` sync on launch.

### 3.3 Server-side manifest & versioning

The app calls a flat-script API (`server/mybingwa-api/get_sync_manifest.php` +
per-resource endpoints), **not** the richer `admin-v2/app/Controllers/Api/SyncController.php`
(`/api/sync/*`) that also exists on the same codebase but has no Android client using it yet.
Decide up front, when porting, which contract to build against.

**`get_sync_manifest.php`** (the one actually used):
- Auth: `X-App-Key` header, constant-time compare.
- Response: `{ publishVersion, generatedAt, resources: { CONFIG:{version,updatedAt,checksum}, OFFERS:{...}, BILLBOARDS:{...}, NOTIFICATION_TEMPLATES:{...}, REMOTE_NOTIFICATIONS:{...} } }`.
- **Versioning:** `version` per resource = `crc32(canonical_json(section)) & 0x7fffffff`
  (content-derived, not the release counter, so only sections that actually changed bump);
  `checksum` = `md5(canonical_json(section))`; `publishVersion` = the admin's release row
  version (moving this is what triggers `FORCE_PUBLISH` client-side).
- Fingerprints are computed once per publish and cached in a small temp file keyed by
  `publishVersion`, so the (frequent) poll is normally one indexed DB row + one cached file read.
- Never a 500 on failure — a `503` with empty `resources` degrades the client to "manifest
  unavailable" rather than a misleading manifest.

**Content endpoints** (`get_offers.php`, `get_config.php`, `get_billboards.php`,
`get_notification_templates.php`, `get_app_notifications.php`) — each app-key-gated, each
returns **full current content** (not a diff) read from the latest published snapshot, with a
legacy-table fallback for installs that predate the admin panel. The manifest is what lets the
client skip calling ones that haven't changed.

**Publishing pipeline** (`admin-v2/app/Services/PublishingService.php`): builds the working
snapshot, computes per-resource versions (`ResourceVersions::compute()` — canonicalize + sha256
each section, compare to the previous release's stored checksum for that key; unchanged → keep
old version number; changed → bump to the new global release version), checksums/signs the whole
snapshot, inserts an immutable row into an append-only releases table
(`mb_configuration_releases`), records field-level diffs for the admin UI.

### 3.4 End-to-end sync flow

1. Client polls the manifest (foreground: every 90s via `ForceSyncWatcher`; always at app start;
   on reconnect; every 6h in the background via WorkManager).
2. Client compares each resource's `{version, checksum}` against what it has stored; unchanged
   resources are skipped entirely — no network call for them.
3. Changed resources are fetched **in full** from their own endpoint and replace the local cache.
4. A failed/null/invalid response **never clears cache** — only a successful fetch advances the
   stored fingerprint, so failures are simply retried on the next throttled attempt.
5. A publish-version bump (`FORCE_PUBLISH`) bypasses all throttles, forcing an immediate full
   pass over every changed resource — this is how a Till number or price change propagates to
   already-installed apps within ~90 seconds without a new app release.
6. Push notifications are **not** part of this propagation path in the source app (see the gap
   noted at the top of this document) — only the foreground poll drives it.

### 3.5 Porting checklist

**Server:**
- [ ] An append-only "published release" table: incrementing `version`, full canonical JSON
      snapshot, checksum.
- [ ] A cheap manifest endpoint: `{ publishVersion, generatedAt, resources: { KEY:
      {version, updatedAt, checksum} } }`, with per-resource fingerprints derived from a hash of
      *only that section's bytes* (not the global release number), cached per release so the
      endpoint stays fast under frequent polling.
- [ ] Full-content endpoints per resource (or one batch endpoint), app-key gated, reading from
      the same published snapshot, with a graceful "nothing published yet" fallback.
- [ ] A simple shared `X-App-Key` header is enough auth for a read-only config-sync surface —
      no need for per-endpoint OAuth.
- [ ] Optional: if bandwidth/caching matters more than in the source app, consider ETags +
      `since=<version>` delta filtering + batch fetch (modeled, but unused, in this codebase's
      `admin-v2/SyncController` — a good reference if you want to build it properly this time).
- [ ] Optional: if you want push-triggered instant sync (which this app's design hints at but
      never wires up), send a data-only FCM message on publish and have the client's
      `onMessageReceived` enqueue an immediate one-off sync.

**Android client:**
- [ ] Port `SyncModels`/`SyncPlanner` close to unchanged — no app-specific logic beyond the
      `SyncResource` enum values.
- [ ] Port `SyncOrchestrator` unchanged (the `Mutex.tryLock()` guard and "never clear cache on
      failure" contract are the load-bearing parts); implement `SyncTargets` for the new app's
      resource types.
- [ ] `SyncMetadataStore` — Preferences DataStore + any JSON serializer; keep it separate from
      user-behavior storage.
- [ ] `ForceSyncWatcher` pattern for foreground near-real-time propagation without background
      battery cost.
- [ ] `CatalogueSyncWorker` + `enqueueUniquePeriodicWork(KEEP)` for the coarse background
      safety net.
- [ ] `ConnectivityObserver`'s dual-callback + asymmetric-debounce pattern if you want an
      offline banner + resume-triggered sync.
- [ ] Wire a `SyncOrchestratorProvider` on the `Application` class so the worker and UI share
      one repository/state.
- [ ] Unlike the source app, decide explicitly whether to wire `APP_RESUME` (lifecycle observer)
      and `MANUAL_REFRESH` (pull-to-refresh) — the throttle logic is already there, just unused.

---

## Summary of files referenced (for direct copy/adaptation)

**Offers:** `core/model/OfferItem.kt`, `OfferCategory.kt`, `OfferAvailability.kt`,
`OfferDailyState.kt`, `core/ui/OfferCard.kt`, `OfferAvailabilityDialog.kt`,
`core/personalization/OfferRanker.kt`, `core/notifications/OfferSuggestionEngine.kt`,
`feature/offers/OffersScreen.kt`; server: `OffersController.php`, `OfferRepository.php`,
`get_offers.php`, `offers.php`, migrations `002_offers.sql`, `018_offer_availability.sql`.

**Push:** `SkylinkBingwaFirebaseService.kt`, `core/notifications/AppNotifier.kt`,
`NotificationChannels.kt`, `core/notifications/engine/*`,
`data/sync/EngagementNotificationWorker.kt`, `feature/notifications/NotificationsScreen.kt`,
`core/model/NotificationItem.kt`; server: `PushController.php`, `FcmService.php`,
`NotificationsController.php`, `NotificationService.php`, `push/index.php`, migrations
`004_notifications.sql`, `014_notifications_v2.sql`, `021_fcm_push.sql`; API:
`get_app_notifications.php`, `get_notification_templates.php`, `register_user.php`.

**Sync:** `data/sync/SyncModels.kt`, `SyncPlanner.kt`, `SyncOrchestrator.kt`,
`SyncMetadataStore.kt`, `ContentSyncers.kt`, `CatalogueSyncWorker.kt`, `ForceSyncWatcher.kt`,
`data/remote/AndroidRemoteSyncManifestSource.kt`, `core/notifications/ConnectivityObserver.kt`;
server: `get_sync_manifest.php`, `lib.php`, `PublishingService.php`, `ResourceVersions.php`,
migrations `007_publishing.sql`, `015_release_management.sql`.
