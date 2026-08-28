-- Referral & commission system.
--
-- A customer shares a code (SK + 3 digits + 1 letter, e.g. SK391R). People who
-- onboard with that code become their referees. The referrer earns a one-off
-- signup bonus plus a percentage commission on every purchase those referees pay
-- for, and withdraws the balance to M-Pesa through Daraja B2C.
--
-- MONEY RULES BAKED INTO THIS SCHEMA:
--   * All amounts are SIGNED INTEGER CENTS. No FLOAT, no DOUBLE, ever.
--   * {p}commission_ledger is APPEND-ONLY. Nothing is updated or deleted; a
--     mistake is corrected by writing a compensating entry.
--   * Every balance-moving write carries an idempotency_key with a UNIQUE index.
--     That index -- not an application "if exists" check, which races -- is what
--     makes a replayed Daraja callback or a re-run cron safe.
--   * {p}referrers.balance_cents is a CACHE. Truth is SUM(ledger.amount_cents).
--     A nightly job asserts they agree and alarms on drift.
--
-- Statements are separated by a line that is exactly "-- @@" (see database/migrate.php).

CREATE TABLE IF NOT EXISTS {p}referrers (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id           BIGINT       NOT NULL,
    code                  VARCHAR(12)  NOT NULL,
    -- NULL until the customer proves the number by OTP. Withdrawal is impossible
    -- while this is NULL: the payout destination is THIS column, never a value
    -- supplied in a withdrawal request.
    verified_msisdn       VARCHAR(16)  NULL DEFAULT NULL,
    verified_at           DATETIME     NULL DEFAULT NULL,
    -- Set when the payout number changes. Blocks withdrawal until it passes, so a
    -- stolen handset or a SIM swap cannot immediately drain an accrued balance.
    payout_frozen_until   DATETIME     NULL DEFAULT NULL,
    balance_cents         BIGINT       NOT NULL DEFAULT 0,
    lifetime_earned_cents BIGINT       NOT NULL DEFAULT 0,
    lifetime_paid_cents   BIGINT       NOT NULL DEFAULT 0,
    referrals_count       INT          NOT NULL DEFAULT 0,
    status                VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
        -- ACTIVE | EARN_BLOCKED | PAYOUT_BLOCKED | BANNED
    status_reason         VARCHAR(191) NOT NULL DEFAULT '',
    -- Rolling fraud score. Raised by the velocity scan; a high score parks the
    -- account in PAYOUT_BLOCKED for a human to look at.
    risk_score            INT          NOT NULL DEFAULT 0,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    UNIQUE KEY uniq_referrer_customer (customer_id),
    UNIQUE KEY uniq_referrer_code (code),
    KEY idx_referrer_status (status),
    KEY idx_referrer_balance (balance_cents)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
CREATE TABLE IF NOT EXISTS {p}referrals (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_customer_id  BIGINT      NOT NULL,
    referred_customer_id  BIGINT      NOT NULL,
    code_used             VARCHAR(12) NOT NULL,
    -- SHA-256 of the installing device's stable id. Never the raw id.
    device_hash           CHAR(64)    NULL DEFAULT NULL,
    attributed_at         DATETIME    NOT NULL,
    first_purchase_at     DATETIME    NULL DEFAULT NULL,
    purchases_count       INT         NOT NULL DEFAULT 0,
    earned_cents          BIGINT      NOT NULL DEFAULT 0,
    -- UNIQUE on the REFERRED customer: one person has at most one referrer, for
    -- life. This is what makes attribution first-write-wins at the database
    -- level, so a reinstall can never re-attribute.
    UNIQUE KEY uniq_referred (referred_customer_id),
    KEY idx_referrer (referrer_customer_id),
    KEY idx_referral_device (device_hash),
    KEY idx_referral_attributed (attributed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- The anti-farming table.
--
-- THE ATTACK THIS EXISTS TO STOP: install the app, onboard with a second SIM,
-- redeem your own referral code, uninstall, reinstall, repeat with a third SIM,
-- and so on -- minting a signup bonus per cycle from one handset.
--
-- Android's ANDROID_ID survives an uninstall/reinstall (it is reset only by a
-- factory reset or a new user profile), so the device is the durable identity
-- here even though the app has no accounts. One device may redeem a referral
-- code exactly ONCE, ever -- enforced by referral_redeemed below.
CREATE TABLE IF NOT EXISTS {p}device_registry (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_hash        CHAR(64)    NOT NULL,
    -- How many DISTINCT numbers have onboarded from this handset. A real handset
    -- that gets handed around a family reaches 2-3. A farm reaches 20.
    msisdn_count       INT         NOT NULL DEFAULT 0,
    registrations      INT         NOT NULL DEFAULT 0,
    -- Set the moment this device redeems any referral code. Once set, this device
    -- can never redeem another one.
    referral_redeemed  TINYINT     NOT NULL DEFAULT 0,
    redeemed_code      VARCHAR(12) NOT NULL DEFAULT '',
    redeemed_at        DATETIME    NULL DEFAULT NULL,
    blocked            TINYINT     NOT NULL DEFAULT 0,
    block_reason       VARCHAR(191) NOT NULL DEFAULT '',
    first_seen_at      DATETIME    NOT NULL,
    last_seen_at       DATETIME    NOT NULL,
    UNIQUE KEY uniq_device_hash (device_hash),
    KEY idx_device_blocked (blocked),
    KEY idx_device_msisdn_count (msisdn_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- Which numbers have been seen on which handset. Lets the velocity scan spot a
-- single device cycling through SIMs without keeping a raw device id anywhere.
CREATE TABLE IF NOT EXISTS {p}device_msisdns (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_hash  CHAR(64)    NOT NULL,
    msisdn       VARCHAR(16) NOT NULL,
    first_seen_at DATETIME   NOT NULL,
    UNIQUE KEY uniq_device_msisdn (device_hash, msisdn),
    KEY idx_dm_msisdn (msisdn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
CREATE TABLE IF NOT EXISTS {p}commission_ledger (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_id     BIGINT       NOT NULL,
    entry_type      VARCHAR(20)  NOT NULL,
        -- SIGNUP_BONUS | EARN | REVERSAL | WITHDRAW_HOLD | WITHDRAW_SETTLE
        -- | WITHDRAW_REFUND | ADJUST
    -- Signed. SIGNUP_BONUS/EARN/WITHDRAW_REFUND are positive; WITHDRAW_HOLD and
    -- REVERSAL are negative. WITHDRAW_SETTLE is zero: it turns a hold into a
    -- completed payout without moving the balance a second time.
    amount_cents    BIGINT       NOT NULL,
    -- When this entry becomes withdrawable. EARN and SIGNUP_BONUS mature after
    -- the configured hold window; every other type matures immediately.
    matures_at      DATETIME     NOT NULL,
    source_type     VARCHAR(20)  NOT NULL,   -- payment | referral | withdrawal | admin
    source_id       BIGINT       NULL DEFAULT NULL,
    -- Links a SIGNUP_BONUS back to its referral so the "bonus only unlocks once
    -- your friend actually buys something" rule can be evaluated as a join
    -- instead of by mutating this append-only row.
    referral_id     BIGINT       NULL DEFAULT NULL,
    idempotency_key VARCHAR(96)  NOT NULL,
    note            VARCHAR(191) NOT NULL DEFAULT '',
    created_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    created_at      DATETIME     NOT NULL,
    UNIQUE KEY uniq_ledger_idem (idempotency_key),
    KEY idx_ledger_referrer (referrer_id, created_at),
    KEY idx_ledger_matures (referrer_id, matures_at),
    KEY idx_ledger_referral (referral_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
CREATE TABLE IF NOT EXISTS {p}withdrawals (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    referrer_id                 BIGINT       NOT NULL,
    amount_cents                BIGINT       NOT NULL,
    -- What Safaricom charged the business for the payout. Reporting only: the
    -- referrer always receives amount_cents in full.
    fee_cents                   BIGINT       NOT NULL DEFAULT 0,
    -- Snapshot of the verified number AT REQUEST TIME, so a number changed while
    -- a payout is in flight can never retarget that payout.
    msisdn                      VARCHAR(16)  NOT NULL,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'REQUESTED',
        -- REQUESTED | SUBMITTING | SUBMITTED | PAID | FAILED | UNKNOWN | CANCELLED
    -- WE generate this and write it BEFORE calling Daraja. It is the payout's
    -- idempotency key and the lookup key for a TransactionStatus query. A retry
    -- reuses the SAME value -- minting a fresh one on retry is how a system pays
    -- twice.
    originator_conversation_id  VARCHAR(64)  NOT NULL,
    conversation_id             VARCHAR(64)  NULL DEFAULT NULL,
    transaction_id              VARCHAR(32)  NULL DEFAULT NULL,
    mpesa_receipt               VARCHAR(32)  NULL DEFAULT NULL,
    result_code                 VARCHAR(12)  NULL DEFAULT NULL,
    result_desc                 VARCHAR(191) NULL DEFAULT NULL,
    submit_attempts             INT          NOT NULL DEFAULT 0,
    admin_note                  VARCHAR(191) NOT NULL DEFAULT '',
    requested_at                DATETIME     NOT NULL,
    submitted_at                DATETIME     NULL DEFAULT NULL,
    resolved_at                 DATETIME     NULL DEFAULT NULL,
    updated_at                  DATETIME     NOT NULL,
    UNIQUE KEY uniq_originator (originator_conversation_id),
    KEY idx_withdrawal_status (status, submitted_at),
    KEY idx_withdrawal_referrer (referrer_id, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- Notifications are QUEUED, never sent inline from a money path. A slow SMS
-- provider inside the Daraja callback would stall the callback, Safaricom would
-- retry it, and the pressure would land on the most important code path in the
-- business.
CREATE TABLE IF NOT EXISTS {p}outbox (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    channel         VARCHAR(8)   NOT NULL,   -- SMS | PUSH
    target          VARCHAR(255) NOT NULL,   -- msisdn or FCM token
    title           VARCHAR(120) NOT NULL DEFAULT '',
    body            TEXT         NOT NULL,
    template        VARCHAR(40)  NOT NULL DEFAULT '',
    status          VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
        -- PENDING | SENT | FAILED | DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(191) NOT NULL DEFAULT '',
    idempotency_key VARCHAR(96)  NOT NULL,
    next_attempt_at DATETIME     NOT NULL,
    created_at      DATETIME     NOT NULL,
    sent_at         DATETIME     NULL DEFAULT NULL,
    UNIQUE KEY uniq_outbox_idem (idempotency_key),
    KEY idx_outbox_due (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
CREATE TABLE IF NOT EXISTS {p}otp_challenges (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    msisdn        VARCHAR(16) NOT NULL,
    -- SHA-256. The plain code is never stored and never logged.
    code_hash     CHAR(64)    NOT NULL,
    purpose       VARCHAR(24) NOT NULL,   -- VERIFY_PAYOUT | CHANGE_PAYOUT
    attempts      INT         NOT NULL DEFAULT 0,
    consumed_at   DATETIME    NULL DEFAULT NULL,
    expires_at    DATETIME    NOT NULL,
    created_at    DATETIME    NOT NULL,
    KEY idx_otp_msisdn (msisdn, created_at),
    KEY idx_otp_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- Only the SHA-256 of a bearer token is stored, so a leaked database yields no
-- usable tokens.
CREATE TABLE IF NOT EXISTS {p}device_tokens (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id  BIGINT      NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    device_hash  CHAR(64)    NULL DEFAULT NULL,
    issued_at    DATETIME    NOT NULL,
    last_seen_at DATETIME    NOT NULL,
    revoked_at   DATETIME    NULL DEFAULT NULL,
    UNIQUE KEY uniq_token_hash (token_hash),
    KEY idx_token_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- Append-only log of every money event, for the 3am "where did my Ksh 400 go"
-- conversation. Retained for at least a year.
CREATE TABLE IF NOT EXISTS {p}commission_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event       VARCHAR(40)  NOT NULL,
    referrer_id BIGINT       NULL DEFAULT NULL,
    ref_id      BIGINT       NULL DEFAULT NULL,
    detail      TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL,
    KEY idx_ce_created (created_at),
    KEY idx_ce_event (event)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- @@
-- Per-offer commission and margin, in basis points (300 = 3.00%), so the rate is
-- always an exact integer. NULL commission_bps falls back to the category rate,
-- then the global default. The admin UI refuses commission_bps > margin_bps.
--
-- These go on {p}offers -- the admin's authoritative catalogue -- NOT the legacy
-- unprefixed `offers` table that mybingwa-api keeps as a last-resort price
-- fallback. Deliberately NOT carried into the published snapshot either: the
-- commission rate does not change what a customer is charged, so it should take
-- effect the moment it is saved rather than waiting for a publish.
SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{p}offers' AND COLUMN_NAME = 'commission_bps'
);
-- @@
SET @sql := IF(@has_col = 0,
    'ALTER TABLE {p}offers ADD COLUMN commission_bps INT NULL DEFAULT NULL',
    'DO 0');
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
SET @has_col2 := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{p}offers' AND COLUMN_NAME = 'margin_bps'
);
-- @@
SET @sql := IF(@has_col2 = 0,
    'ALTER TABLE {p}offers ADD COLUMN margin_bps INT NULL DEFAULT NULL',
    'DO 0');
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
-- The referral code column on the customer register, so register_user.php can
-- resolve a customer to their code without a join on every call.
SET @has_col3 := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{p}customers' AND COLUMN_NAME = 'referral_code'
);
-- @@
SET @sql := IF(@has_col3 = 0,
    'ALTER TABLE {p}customers ADD COLUMN referral_code VARCHAR(12) NOT NULL DEFAULT \'\'',
    'DO 0');
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
SET @has_col4 := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '{p}customers' AND COLUMN_NAME = 'device_hash'
);
-- @@
SET @sql := IF(@has_col4 = 0,
    'ALTER TABLE {p}customers ADD COLUMN device_hash CHAR(64) NULL DEFAULT NULL',
    'DO 0');
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
-- Defaults. Rates are DELIBERATELY 0 until the owner records real per-offer
-- margins: a commission rate above margin means the harder the programme works,
-- the faster the business loses money. Everything else ships with a safe value.
INSERT INTO {p}settings (skey, svalue, updated_at) VALUES
    ('referral_enabled',          '1',    UTC_TIMESTAMP()),
    ('referral_commission_bps',   '0',    UTC_TIMESTAMP()),
    ('referral_signup_bonus_cents','1000', UTC_TIMESTAMP()),
    ('referral_bonus_requires_purchase','1', UTC_TIMESTAMP()),
    ('referral_hold_hours',       '24',   UTC_TIMESTAMP()),
    ('referral_min_withdraw_cents','20000', UTC_TIMESTAMP()),
    ('referral_max_withdraw_cents','1000000', UTC_TIMESTAMP()),
    ('referral_cooldown_hours',   '24',   UTC_TIMESTAMP()),
    ('referral_daily_cap_cents',  '5000000', UTC_TIMESTAMP()),
    ('referral_payouts_enabled',  '0',    UTC_TIMESTAMP()),
    ('referral_float_floor_cents','5000000', UTC_TIMESTAMP()),
    ('referral_max_device_msisdns','3',   UTC_TIMESTAMP()),
    ('referral_max_daily_referrals','15', UTC_TIMESTAMP()),
    ('referral_max_daily_earn_cents','50000', UTC_TIMESTAMP()),
    ('referral_join_sms_daily_cap','20',  UTC_TIMESTAMP())
ON DUPLICATE KEY UPDATE skey = skey;
