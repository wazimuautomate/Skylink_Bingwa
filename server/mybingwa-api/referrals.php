<?php
/**
 * Referral & commission engine.
 *
 * Shared by register_user.php (attribution), callback.php (accrual), the Earn
 * endpoints the app calls, and cron_referrals.php (payouts + notifications). The
 * admin panel reads the same tables through its own repository.
 *
 * THE THREE RULES THIS FILE EXISTS TO ENFORCE
 *
 * 1. Money is signed integer cents. Never a float. `intdiv` everywhere, and
 *    commission always rounds DOWN so a rounding artefact can never push a
 *    payout above the offer's margin.
 *
 * 2. mb_commission_ledger is append-only and every balance-moving write carries
 *    an idempotency_key protected by a UNIQUE index. That index -- not an
 *    application-level "does it already exist" check, which races under
 *    concurrent Daraja callbacks -- is what makes replays safe. A duplicate-key
 *    error from a ledger insert means "already recorded", which is a SUCCESS.
 *
 * 3. mb_referrers.balance_cents is a cache written in the SAME transaction as
 *    its ledger row. Truth is always SUM(ledger.amount_cents); the nightly
 *    integrity job asserts the two agree.
 *
 * @see docs/REFERRAL_COMMISSION_SPEC.md
 */

const REF_PREFIX = 'mb_';

/** Fully-qualified table name. */
function ref_t(string $name): string
{
    return REF_PREFIX . $name;
}

/** UTC timestamp string, matching how admin-v2 stores every datetime. */
function ref_now(): string
{
    return gmdate('Y-m-d H:i:s');
}

/** UTC timestamp N hours from now. */
function ref_now_plus_hours(int $hours): string
{
    return gmdate('Y-m-d H:i:s', time() + ($hours * 3600));
}

/** Today's date in Africa/Nairobi, for daily caps that must reset at local midnight. */
function ref_nairobi_date(): string
{
    $tz = new DateTimeZone('Africa/Nairobi');
    return (new DateTime('now', $tz))->format('Y-m-d');
}

/** Start of the current Nairobi day, expressed in UTC for comparing against stored datetimes. */
function ref_nairobi_day_start_utc(): string
{
    $tz = new DateTimeZone('Africa/Nairobi');
    $start = new DateTime(ref_nairobi_date() . ' 00:00:00', $tz);
    $start->setTimezone(new DateTimeZone('UTC'));
    return $start->format('Y-m-d H:i:s');
}

/* -------------------------------------------------------------------------- */
/* Provisioning                                                               */
/* -------------------------------------------------------------------------- */

/**
 * Create the referral tables if they are missing, mirroring
 * admin-v2/database/migrations/022_referrals.sql exactly.
 *
 * Same reasoning as db.php and register_user.php: the payment API must work on a
 * cPanel where the admin panel's migrations have never run. A cheap probe keeps
 * the happy path to one query instead of a dozen CREATE statements per request.
 */
function ref_provision(PDO $pdo): void
{
    static $done = false;
    if ($done) {
        return;
    }
    $done = true;

    try {
        $pdo->query('SELECT 1 FROM ' . ref_t('referrers') . ' LIMIT 1');
        return; // Already provisioned.
    } catch (Throwable $e) {
        // Fall through and build the schema.
    }

    $ddl = [
        'CREATE TABLE IF NOT EXISTS ' . ref_t('referrers') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            customer_id BIGINT NOT NULL,
            code VARCHAR(12) NOT NULL,
            verified_msisdn VARCHAR(16) NULL DEFAULT NULL,
            verified_at DATETIME NULL DEFAULT NULL,
            payout_frozen_until DATETIME NULL DEFAULT NULL,
            balance_cents BIGINT NOT NULL DEFAULT 0,
            lifetime_earned_cents BIGINT NOT NULL DEFAULT 0,
            lifetime_paid_cents BIGINT NOT NULL DEFAULT 0,
            referrals_count INT NOT NULL DEFAULT 0,
            status VARCHAR(16) NOT NULL DEFAULT \'ACTIVE\',
            status_reason VARCHAR(191) NOT NULL DEFAULT \'\',
            risk_score INT NOT NULL DEFAULT 0,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uniq_referrer_customer (customer_id),
            UNIQUE KEY uniq_referrer_code (code),
            KEY idx_referrer_status (status)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('referrals') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            referrer_customer_id BIGINT NOT NULL,
            referred_customer_id BIGINT NOT NULL,
            code_used VARCHAR(12) NOT NULL,
            device_hash CHAR(64) NULL DEFAULT NULL,
            attributed_at DATETIME NOT NULL,
            first_purchase_at DATETIME NULL DEFAULT NULL,
            purchases_count INT NOT NULL DEFAULT 0,
            earned_cents BIGINT NOT NULL DEFAULT 0,
            UNIQUE KEY uniq_referred (referred_customer_id),
            KEY idx_referrer (referrer_customer_id),
            KEY idx_referral_device (device_hash),
            KEY idx_referral_attributed (attributed_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('device_registry') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            device_hash CHAR(64) NOT NULL,
            msisdn_count INT NOT NULL DEFAULT 0,
            registrations INT NOT NULL DEFAULT 0,
            referral_redeemed TINYINT NOT NULL DEFAULT 0,
            redeemed_code VARCHAR(12) NOT NULL DEFAULT \'\',
            redeemed_at DATETIME NULL DEFAULT NULL,
            blocked TINYINT NOT NULL DEFAULT 0,
            block_reason VARCHAR(191) NOT NULL DEFAULT \'\',
            first_seen_at DATETIME NOT NULL,
            last_seen_at DATETIME NOT NULL,
            UNIQUE KEY uniq_device_hash (device_hash),
            KEY idx_device_blocked (blocked)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('device_msisdns') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            device_hash CHAR(64) NOT NULL,
            msisdn VARCHAR(16) NOT NULL,
            first_seen_at DATETIME NOT NULL,
            UNIQUE KEY uniq_device_msisdn (device_hash, msisdn),
            KEY idx_dm_msisdn (msisdn)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('commission_ledger') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            referrer_id BIGINT NOT NULL,
            entry_type VARCHAR(20) NOT NULL,
            amount_cents BIGINT NOT NULL,
            matures_at DATETIME NOT NULL,
            source_type VARCHAR(20) NOT NULL,
            source_id BIGINT NULL DEFAULT NULL,
            referral_id BIGINT NULL DEFAULT NULL,
            idempotency_key VARCHAR(96) NOT NULL,
            note VARCHAR(191) NOT NULL DEFAULT \'\',
            created_by VARCHAR(64) NOT NULL DEFAULT \'system\',
            created_at DATETIME NOT NULL,
            UNIQUE KEY uniq_ledger_idem (idempotency_key),
            KEY idx_ledger_referrer (referrer_id, created_at),
            KEY idx_ledger_matures (referrer_id, matures_at),
            KEY idx_ledger_referral (referral_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('withdrawals') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            referrer_id BIGINT NOT NULL,
            amount_cents BIGINT NOT NULL,
            fee_cents BIGINT NOT NULL DEFAULT 0,
            msisdn VARCHAR(16) NOT NULL,
            status VARCHAR(16) NOT NULL DEFAULT \'REQUESTED\',
            originator_conversation_id VARCHAR(64) NOT NULL,
            conversation_id VARCHAR(64) NULL DEFAULT NULL,
            transaction_id VARCHAR(32) NULL DEFAULT NULL,
            mpesa_receipt VARCHAR(32) NULL DEFAULT NULL,
            result_code VARCHAR(12) NULL DEFAULT NULL,
            result_desc VARCHAR(191) NULL DEFAULT NULL,
            submit_attempts INT NOT NULL DEFAULT 0,
            admin_note VARCHAR(191) NOT NULL DEFAULT \'\',
            requested_at DATETIME NOT NULL,
            submitted_at DATETIME NULL DEFAULT NULL,
            resolved_at DATETIME NULL DEFAULT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uniq_originator (originator_conversation_id),
            KEY idx_withdrawal_status (status, submitted_at),
            KEY idx_withdrawal_referrer (referrer_id, requested_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('outbox') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            channel VARCHAR(8) NOT NULL,
            target VARCHAR(255) NOT NULL,
            title VARCHAR(120) NOT NULL DEFAULT \'\',
            body TEXT NOT NULL,
            template VARCHAR(40) NOT NULL DEFAULT \'\',
            status VARCHAR(12) NOT NULL DEFAULT \'PENDING\',
            attempts INT NOT NULL DEFAULT 0,
            last_error VARCHAR(191) NOT NULL DEFAULT \'\',
            idempotency_key VARCHAR(96) NOT NULL,
            next_attempt_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL,
            sent_at DATETIME NULL DEFAULT NULL,
            UNIQUE KEY uniq_outbox_idem (idempotency_key),
            KEY idx_outbox_due (status, next_attempt_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('otp_challenges') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            msisdn VARCHAR(16) NOT NULL,
            code_hash CHAR(64) NOT NULL,
            purpose VARCHAR(24) NOT NULL,
            attempts INT NOT NULL DEFAULT 0,
            consumed_at DATETIME NULL DEFAULT NULL,
            expires_at DATETIME NOT NULL,
            created_at DATETIME NOT NULL,
            KEY idx_otp_msisdn (msisdn, created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('device_tokens') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            customer_id BIGINT NOT NULL,
            token_hash CHAR(64) NOT NULL,
            device_hash CHAR(64) NULL DEFAULT NULL,
            issued_at DATETIME NOT NULL,
            last_seen_at DATETIME NOT NULL,
            revoked_at DATETIME NULL DEFAULT NULL,
            UNIQUE KEY uniq_token_hash (token_hash),
            KEY idx_token_customer (customer_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('commission_events') . ' (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            event VARCHAR(40) NOT NULL,
            referrer_id BIGINT NULL DEFAULT NULL,
            ref_id BIGINT NULL DEFAULT NULL,
            detail TEXT NOT NULL,
            created_at DATETIME NOT NULL,
            KEY idx_ce_created (created_at),
            KEY idx_ce_event (event)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',

        'CREATE TABLE IF NOT EXISTS ' . ref_t('settings') . ' (
            skey VARCHAR(64) NOT NULL PRIMARY KEY,
            svalue TEXT NOT NULL,
            updated_at DATETIME NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
    ];

    foreach ($ddl as $sql) {
        try {
            $pdo->exec($sql);
        } catch (Throwable $e) {
            // Already present, or this DB user cannot CREATE. Reads and writes
            // below decide; never fail a customer request over provisioning.
        }
    }

    // Columns added to existing tables. Each is independently guarded because
    // MySQL has no ADD COLUMN IF NOT EXISTS and a throw here must not stop the rest.
    foreach ([
        'ALTER TABLE ' . ref_t('customers') . ' ADD COLUMN referral_code VARCHAR(12) NOT NULL DEFAULT \'\'',
        'ALTER TABLE ' . ref_t('customers') . ' ADD COLUMN device_hash CHAR(64) NULL DEFAULT NULL',
        'ALTER TABLE ' . ref_t('offers') . ' ADD COLUMN commission_bps INT NULL DEFAULT NULL',
        'ALTER TABLE ' . ref_t('offers') . ' ADD COLUMN margin_bps INT NULL DEFAULT NULL',
    ] as $sql) {
        try {
            $pdo->exec($sql);
        } catch (Throwable $e) {
            // Column already exists.
        }
    }
}

/* -------------------------------------------------------------------------- */
/* Settings                                                                    */
/* -------------------------------------------------------------------------- */

/**
 * Operational settings, read from mb_settings with safe defaults.
 *
 * The commission rate defaults to ZERO on purpose. A rate above the real margin
 * means the harder the referral programme works, the faster the business loses
 * money, so the owner must set it deliberately in the admin panel rather than
 * inherit a guess from a config file.
 */
function ref_settings(PDO $pdo): array
{
    static $cache = null;
    if ($cache !== null) {
        return $cache;
    }

    $defaults = [
        'referral_enabled'                  => 1,
        'referral_commission_bps'           => 0,
        'referral_signup_bonus_cents'       => 1000,   // Ksh 10
        'referral_bonus_requires_purchase'  => 1,
        'referral_hold_hours'               => 24,
        'referral_min_withdraw_cents'       => 20000,  // Ksh 200
        'referral_max_withdraw_cents'       => 1000000,
        'referral_cooldown_hours'           => 24,
        'referral_daily_cap_cents'          => 5000000,
        'referral_payouts_enabled'          => 0,
        'referral_float_floor_cents'        => 5000000,
        'referral_max_device_msisdns'       => 3,
        'referral_max_daily_referrals'      => 15,
        'referral_max_daily_earn_cents'     => 50000,
        'referral_join_sms_daily_cap'       => 20,
    ];

    $out = $defaults;
    try {
        $keys = array_keys($defaults);
        $in = implode(',', array_fill(0, count($keys), '?'));
        $stmt = $pdo->prepare('SELECT skey, svalue FROM ' . ref_t('settings') . ' WHERE skey IN (' . $in . ')');
        $stmt->execute($keys);
        foreach ($stmt->fetchAll() as $row) {
            $out[$row['skey']] = (int) $row['svalue'];
        }
    } catch (Throwable $e) {
        // Settings table unreadable — defaults stand, which are the safe values.
    }

    $cache = $out;
    return $out;
}

/* -------------------------------------------------------------------------- */
/* Logging                                                                     */
/* -------------------------------------------------------------------------- */

/** Append-only money-event log. Never throws: logging must not break a payment. */
function ref_log(PDO $pdo, string $event, ?int $referrerId, ?int $refId, array $detail): void
{
    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('commission_events') . ' (event, referrer_id, ref_id, detail, created_at)
             VALUES (?, ?, ?, ?, ?)'
        )->execute([$event, $referrerId, $refId, json_encode($detail), ref_now()]);
    } catch (Throwable $e) {
        error_log('[referrals] log failed: ' . $e->getMessage());
    }
}

/* -------------------------------------------------------------------------- */
/* Referral codes                                                              */
/* -------------------------------------------------------------------------- */

/**
 * Code alphabet for the trailing letter.
 *
 * I and O are removed because these codes get read aloud down a phone line and
 * written on paper: "SK391O" and "SK3910" are the same code to a human, and a
 * mis-keyed code silently pays commission to the wrong person.
 */
const REF_CODE_LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZ';

/** True when $code matches the SK + 3 digits + 1 letter shape (e.g. SK391R). */
function ref_code_valid(string $code): bool
{
    return (bool) preg_match('/^SK[0-9]{3}[' . REF_CODE_LETTERS . ']$/', strtoupper(trim($code)));
}

/** Normalise user input to the canonical uppercase form. */
function ref_code_normalise(string $code): string
{
    return strtoupper(preg_replace('/[^A-Za-z0-9]/', '', trim($code)));
}

/**
 * Generate an unused code: "SK" + 3 digits + 1 letter.
 *
 * The space is 24,000 codes. Generation is random rather than sequential so a
 * code cannot be guessed from a customer id, and uniqueness is settled by the
 * UNIQUE index rather than by a SELECT that would race under concurrent signups.
 * Returns null if the space is genuinely exhausted, which the admin dashboard
 * surfaces rather than the app failing silently.
 */
function ref_generate_code(PDO $pdo): ?string
{
    $letters = REF_CODE_LETTERS;
    for ($attempt = 0; $attempt < 60; $attempt++) {
        $digits = str_pad((string) random_int(0, 999), 3, '0', STR_PAD_LEFT);
        $letter = $letters[random_int(0, strlen($letters) - 1)];
        $code = 'SK' . $digits . $letter;

        $stmt = $pdo->prepare('SELECT 1 FROM ' . ref_t('referrers') . ' WHERE code = ? LIMIT 1');
        $stmt->execute([$code]);
        if (!$stmt->fetch()) {
            return $code;
        }
    }
    return null;
}

/**
 * Return the referrer row for a customer, creating it (with a fresh code) if it
 * does not exist. Every registered customer gets a code, so the Earn screen has
 * something to show from the very first launch.
 */
function ref_ensure_referrer(PDO $pdo, int $customerId): ?array
{
    $stmt = $pdo->prepare('SELECT * FROM ' . ref_t('referrers') . ' WHERE customer_id = ? LIMIT 1');
    $stmt->execute([$customerId]);
    if ($row = $stmt->fetch()) {
        return $row;
    }

    $code = ref_generate_code($pdo);
    if ($code === null) {
        ref_log($pdo, 'CODE_SPACE_EXHAUSTED', null, $customerId, []);
        return null;
    }

    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('referrers') . ' (customer_id, code, created_at, updated_at)
             VALUES (?, ?, ?, ?)'
        )->execute([$customerId, $code, ref_now(), ref_now()]);
    } catch (PDOException $e) {
        // A concurrent request created it first (uniq_referrer_customer), or the
        // code collided (uniq_referrer_code). Either way, re-read and use theirs.
    }

    $stmt->execute([$customerId]);
    $row = $stmt->fetch();
    if ($row) {
        try {
            $pdo->prepare('UPDATE ' . ref_t('customers') . ' SET referral_code = ? WHERE id = ?')
                ->execute([$row['code'], $customerId]);
        } catch (Throwable $e) {
            // Denormalised convenience column only; the referrers table is the truth.
        }
    }
    return $row ?: null;
}

/* -------------------------------------------------------------------------- */
/* Device identity (the anti-farming spine)                                    */
/* -------------------------------------------------------------------------- */

/** SHA-256 of a raw device id. The raw value is never stored. */
function ref_device_hash(string $raw): ?string
{
    $raw = trim($raw);
    if ($raw === '' || strlen($raw) < 8) {
        return null;
    }
    return hash('sha256', 'skylink-device:' . $raw);
}

/**
 * Record that this handset onboarded this number, and return the device row.
 *
 * msisdn_count is the signal that matters. A genuine handset passed around a
 * household reaches two or three numbers. A farm cycling SIM cards to mint
 * signup bonuses climbs without limit, and crossing the configured ceiling
 * blocks the device from redeeming any further codes.
 */
function ref_touch_device(PDO $pdo, ?string $deviceHash, string $msisdn, array $settings): ?array
{
    if ($deviceHash === null) {
        return null;
    }
    $now = ref_now();

    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('device_registry') . '
                (device_hash, msisdn_count, registrations, first_seen_at, last_seen_at)
             VALUES (?, 0, 1, ?, ?)
             ON DUPLICATE KEY UPDATE registrations = registrations + 1, last_seen_at = VALUES(last_seen_at)'
        )->execute([$deviceHash, $now, $now]);

        // Only a NEW number on this handset bumps msisdn_count; the UNIQUE key
        // makes a reinstall with the same number a no-op.
        $ins = $pdo->prepare(
            'INSERT IGNORE INTO ' . ref_t('device_msisdns') . ' (device_hash, msisdn, first_seen_at)
             VALUES (?, ?, ?)'
        );
        $ins->execute([$deviceHash, $msisdn, $now]);
        if ($ins->rowCount() === 1) {
            $pdo->prepare(
                'UPDATE ' . ref_t('device_registry') . ' SET msisdn_count = msisdn_count + 1 WHERE device_hash = ?'
            )->execute([$deviceHash]);
        }

        // Auto-block a handset that has cycled through too many numbers.
        $max = (int) $settings['referral_max_device_msisdns'];
        $pdo->prepare(
            'UPDATE ' . ref_t('device_registry') . '
                SET blocked = 1, block_reason = ?
              WHERE device_hash = ? AND blocked = 0 AND msisdn_count > ?'
        )->execute(['Too many numbers onboarded from one device', $deviceHash, $max]);

        $stmt = $pdo->prepare('SELECT * FROM ' . ref_t('device_registry') . ' WHERE device_hash = ? LIMIT 1');
        $stmt->execute([$deviceHash]);
        return $stmt->fetch() ?: null;
    } catch (Throwable $e) {
        error_log('[referrals] device touch failed: ' . $e->getMessage());
        return null;
    }
}

/* -------------------------------------------------------------------------- */
/* Ledger                                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Append one ledger entry and move the cached balance in the SAME transaction.
 *
 * The caller owns the transaction. Returns true when this call actually wrote
 * the entry, false when the idempotency key was already present -- which is a
 * successful outcome, not an error: it means a replayed callback or a re-run
 * cron reached code that had already been applied exactly once.
 */
function ref_ledger_add(
    PDO $pdo,
    int $referrerId,
    string $entryType,
    int $amountCents,
    string $maturesAt,
    string $sourceType,
    ?int $sourceId,
    ?int $referralId,
    string $idempotencyKey,
    string $note = '',
    string $createdBy = 'system'
): bool {
    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('commission_ledger') . '
                (referrer_id, entry_type, amount_cents, matures_at, source_type, source_id,
                 referral_id, idempotency_key, note, created_by, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)'
        )->execute([
            $referrerId, $entryType, $amountCents, $maturesAt, $sourceType, $sourceId,
            $referralId, $idempotencyKey, mb_substr($note, 0, 191), $createdBy, ref_now(),
        ]);
    } catch (PDOException $e) {
        // Duplicate idempotency_key. InnoDB rolls back only the failed statement,
        // so the caller's transaction stays usable. Already applied — do NOT
        // touch the cached balance again.
        return false;
    }

    // Cache update, same transaction. lifetime_earned only counts money coming in.
    $earned = ($entryType === 'EARN' || $entryType === 'SIGNUP_BONUS') ? $amountCents : 0;
    $pdo->prepare(
        'UPDATE ' . ref_t('referrers') . '
            SET balance_cents = balance_cents + ?,
                lifetime_earned_cents = lifetime_earned_cents + ?,
                updated_at = ?
          WHERE id = ?'
    )->execute([$amountCents, $earned, ref_now(), $referrerId]);

    return true;
}

/**
 * Total and withdrawable balance, both computed from the ledger.
 *
 * `available` is the only figure a withdrawal may draw against. Two things hold
 * it down:
 *   * matures_at -- an EARN inside its hold window is not yet spendable, which
 *     closes "buy, earn, withdraw, then dispute the M-Pesa payment".
 *   * the signup-bonus purchase gate -- when referral_bonus_requires_purchase is
 *     on, a SIGNUP_BONUS only becomes withdrawable once that referee has
 *     actually paid for something. This is what makes SIM-farming the bonus
 *     unprofitable: the farm has to generate real revenue to unlock it.
 *
 * A WITHDRAW_HOLD is a negative entry that matures immediately, so an in-flight
 * payout is already excluded here. There is no separate "pending" figure that
 * could drift out of step.
 */
function ref_balances(PDO $pdo, int $referrerId, array $settings): array
{
    $requirePurchase = (int) $settings['referral_bonus_requires_purchase'] === 1;

    $totalStmt = $pdo->prepare(
        'SELECT COALESCE(SUM(amount_cents), 0) AS t FROM ' . ref_t('commission_ledger') . ' WHERE referrer_id = ?'
    );
    $totalStmt->execute([$referrerId]);
    $total = (int) $totalStmt->fetch()['t'];

    $sql = 'SELECT COALESCE(SUM(l.amount_cents), 0) AS a
              FROM ' . ref_t('commission_ledger') . ' l
              LEFT JOIN ' . ref_t('referrals') . ' r ON r.id = l.referral_id
             WHERE l.referrer_id = ?
               AND l.matures_at <= ?';
    if ($requirePurchase) {
        $sql .= " AND (l.entry_type <> 'SIGNUP_BONUS' OR r.first_purchase_at IS NOT NULL)";
    }
    $availStmt = $pdo->prepare($sql);
    $availStmt->execute([$referrerId, ref_now()]);
    $available = (int) $availStmt->fetch()['a'];

    // Anything counted in the total but not yet spendable.
    return [
        'total_cents'     => $total,
        'available_cents' => max(0, $available),
        'pending_cents'   => max(0, $total - $available),
    ];
}

/* -------------------------------------------------------------------------- */
/* Attribution                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Attribute a newly-registered customer to the owner of $rawCode.
 *
 * Returns a result array: ['ok' => bool, 'reason' => string, 'referrer' => ?array].
 * A rejection is NEVER surfaced as an onboarding failure -- a first run blocked
 * by a mistyped code is worse than a lost attribution -- so callers log the
 * reason and carry on.
 *
 * THE ATTACK THIS FUNCTION IS BUILT AGAINST: onboard with a second SIM, redeem
 * your own code, uninstall, reinstall, repeat with a third SIM. Each check below
 * closes one step of that loop, and check 5 (one redemption per handset, for
 * life) closes the loop itself -- ANDROID_ID survives a reinstall, so coming back
 * on a fresh install with a new number is exactly what it detects.
 */
function ref_attribute(
    PDO $pdo,
    array $settings,
    int $referredCustomerId,
    string $referredMsisdn,
    string $rawCode,
    ?string $deviceHash
): array {
    $reject = static function (string $reason) use ($pdo, $referredCustomerId, $rawCode) {
        ref_log($pdo, 'ATTRIBUTION_REJECTED', null, $referredCustomerId, [
            'reason' => $reason,
            'code'   => $rawCode,
        ]);
        return ['ok' => false, 'reason' => $reason, 'referrer' => null];
    };

    if ((int) $settings['referral_enabled'] !== 1) {
        return $reject('REFERRALS_DISABLED');
    }

    $code = ref_code_normalise($rawCode);
    if (!ref_code_valid($code)) {
        return $reject('BAD_CODE_FORMAT');
    }

    // 1. The code must belong to a real, non-banned referrer.
    $stmt = $pdo->prepare(
        'SELECT r.*, c.msisdn AS referrer_msisdn, c.name AS referrer_name,
                c.fcm_token AS referrer_token, c.device_hash AS referrer_device
           FROM ' . ref_t('referrers') . ' r
           JOIN ' . ref_t('customers') . ' c ON c.id = r.customer_id
          WHERE r.code = ? LIMIT 1'
    );
    $stmt->execute([$code]);
    $referrer = $stmt->fetch();
    if (!$referrer) {
        return $reject('UNKNOWN_CODE');
    }
    if ($referrer['status'] === 'BANNED') {
        return $reject('REFERRER_BANNED');
    }

    // 2. Self-referral by number.
    if ($referrer['referrer_msisdn'] === $referredMsisdn) {
        return $reject('SELF_REFERRAL_MSISDN');
    }

    // 3. Self-referral by handset: the code owner's own phone entering their own
    //    code under a different SIM.
    if ($deviceHash !== null && $referrer['referrer_device'] === $deviceHash) {
        return $reject('SELF_REFERRAL_DEVICE');
    }

    // 4. Attribution is for life. The UNIQUE index settles the race; this is the
    //    cheap early exit.
    $existing = $pdo->prepare('SELECT 1 FROM ' . ref_t('referrals') . ' WHERE referred_customer_id = ? LIMIT 1');
    $existing->execute([$referredCustomerId]);
    if ($existing->fetch()) {
        return $reject('ALREADY_ATTRIBUTED');
    }

    // 5. ONE REDEMPTION PER HANDSET, FOR LIFE. This is the check that breaks the
    //    uninstall/reinstall/new-SIM loop.
    if ($deviceHash !== null) {
        $dev = $pdo->prepare('SELECT * FROM ' . ref_t('device_registry') . ' WHERE device_hash = ? LIMIT 1');
        $dev->execute([$deviceHash]);
        $device = $dev->fetch();
        if ($device) {
            if ((int) $device['blocked'] === 1) {
                return $reject('DEVICE_BLOCKED');
            }
            if ((int) $device['referral_redeemed'] === 1) {
                return $reject('DEVICE_ALREADY_REDEEMED');
            }
            if ((int) $device['msisdn_count'] > (int) $settings['referral_max_device_msisdns']) {
                return $reject('DEVICE_TOO_MANY_NUMBERS');
            }
        }
    } else {
        // No usable device id means we cannot enforce the rule that matters most.
        // Refuse the attribution rather than open the farming hole.
        return $reject('NO_DEVICE_IDENTITY');
    }

    // 6. Velocity: a referrer suddenly acquiring referees far faster than a human
    //    shares a code is parked for review rather than paid.
    $dayStart = ref_nairobi_day_start_utc();
    $vel = $pdo->prepare(
        'SELECT COUNT(*) AS c FROM ' . ref_t('referrals') . '
          WHERE referrer_customer_id = ? AND attributed_at >= ?'
    );
    $vel->execute([(int) $referrer['customer_id'], $dayStart]);
    if ((int) $vel->fetch()['c'] >= (int) $settings['referral_max_daily_referrals']) {
        $pdo->prepare(
            'UPDATE ' . ref_t('referrers') . '
                SET status = ?, status_reason = ?, risk_score = risk_score + 25, updated_at = ?
              WHERE id = ? AND status = ?'
        )->execute([
            'PAYOUT_BLOCKED', 'Daily referral velocity exceeded', ref_now(),
            (int) $referrer['id'], 'ACTIVE',
        ]);
        return $reject('REFERRER_VELOCITY');
    }

    // --- All checks passed. Write the attribution. -------------------------
    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('referrals') . '
                (referrer_customer_id, referred_customer_id, code_used, device_hash, attributed_at)
             VALUES (?, ?, ?, ?, ?)'
        )->execute([
            (int) $referrer['customer_id'], $referredCustomerId, $code, $deviceHash, ref_now(),
        ]);
    } catch (PDOException $e) {
        // uniq_referred: a concurrent request attributed this customer first.
        return $reject('ALREADY_ATTRIBUTED');
    }
    $referralId = (int) $pdo->lastInsertId();

    // Burn the handset's one redemption.
    $pdo->prepare(
        'UPDATE ' . ref_t('device_registry') . '
            SET referral_redeemed = 1, redeemed_code = ?, redeemed_at = ?
          WHERE device_hash = ?'
    )->execute([$code, ref_now(), $deviceHash]);

    $pdo->prepare(
        'UPDATE ' . ref_t('referrers') . ' SET referrals_count = referrals_count + 1, updated_at = ? WHERE id = ?'
    )->execute([ref_now(), (int) $referrer['id']]);

    // --- Signup bonus ------------------------------------------------------
    // Credited immediately so the referrer sees it, but held: it matures after
    // the hold window AND (when referral_bonus_requires_purchase is on) stays
    // out of `available` until this referee actually buys something. The app
    // shows it as "pending — unlocks when your friend makes their first
    // purchase", which is both honest and the reason farming it does not pay.
    $bonus = (int) $settings['referral_signup_bonus_cents'];
    if ($bonus > 0 && $referrer['status'] !== 'EARN_BLOCKED') {
        ref_ledger_add(
            $pdo,
            (int) $referrer['id'],
            'SIGNUP_BONUS',
            $bonus,
            ref_now_plus_hours((int) $settings['referral_hold_hours']),
            'referral',
            $referralId,
            $referralId,
            'bonus:referral:' . $referralId,
            'Signup bonus for ' . $code
        );
    }

    ref_log($pdo, 'ATTRIBUTED', (int) $referrer['id'], $referralId, [
        'code'         => $code,
        'referred_id'  => $referredCustomerId,
        'bonus_cents'  => $bonus,
    ]);

    return ['ok' => true, 'reason' => 'ATTRIBUTED', 'referrer' => $referrer, 'referral_id' => $referralId];
}

/* -------------------------------------------------------------------------- */
/* Accrual                                                                     */
/* -------------------------------------------------------------------------- */

/**
 * Commission rate for an offer, in basis points.
 * Resolution order: per-offer → per-category → global default.
 */
function ref_rate_bps(PDO $pdo, string $offerId, array $settings): int
{
    try {
        // mb_offers is the admin's authoritative catalogue. The unprefixed `offers`
        // table is only the legacy price fallback and never carries these columns.
        $stmt = $pdo->prepare(
            'SELECT commission_bps, margin_bps, category FROM ' . ref_t('offers') . ' WHERE offer_id = ? LIMIT 1'
        );
        $stmt->execute([$offerId]);
        if ($row = $stmt->fetch()) {
            if ($row['commission_bps'] !== null && $row['commission_bps'] !== '') {
                $bps = (int) $row['commission_bps'];
            } else {
                $catKey = 'referral_commission_bps_' . strtolower((string) $row['category']);
                $cat = $pdo->prepare('SELECT svalue FROM ' . ref_t('settings') . ' WHERE skey = ? LIMIT 1');
                $cat->execute([$catKey]);
                $catRow = $cat->fetch();
                $bps = $catRow ? (int) $catRow['svalue'] : (int) $settings['referral_commission_bps'];
            }

            // A commission above the recorded margin loses money on every sale.
            // The admin UI blocks it; this is the second line of defence for a
            // rate written straight into the database.
            if ($row['margin_bps'] !== null && $row['margin_bps'] !== '' && $bps > (int) $row['margin_bps']) {
                $bps = (int) $row['margin_bps'];
            }
            return max(0, $bps);
        }
    } catch (Throwable $e) {
        // offers table missing the columns, or unreadable — fall through.
    }
    return max(0, (int) $settings['referral_commission_bps']);
}

/**
 * Accrue commission for one CONFIRMED payment.
 *
 * MUST be called from inside the branch of callback.php that observed the
 * PAYMENT_REQUESTED → PAYMENT_CONFIRMED transition, which is already exactly-once
 * (`status <> 'PAYMENT_CONFIRMED'` guard + rowCount() === 1). The ledger's
 * idempotency key is the second, independent guarantee: a Daraja retry, a manual
 * replay or a reconciliation sweep all land on the same key and change nothing.
 *
 * Attribution follows the PAYER. On a buy-for-another purchase the payer is the
 * person who was referred and who spent the money; crediting the recipient
 * instead would let anyone farm a stranger's tree by buying a bundle for one of
 * their referees' numbers.
 *
 * Best-effort by contract: it must never throw into the callback and never delay
 * the 200 back to Safaricom.
 */
function ref_accrue_for_payment(PDO $pdo, array $payment): void
{
    try {
        ref_provision($pdo);
        $settings = ref_settings($pdo);
        if ((int) $settings['referral_enabled'] !== 1) {
            return;
        }

        $payer = (string) ($payment['payer'] ?? '');
        if ($payer === '') {
            return;
        }

        // Payer → customer → their referrer.
        $stmt = $pdo->prepare(
            'SELECT rf.id AS referral_id, rf.referrer_customer_id, rf.first_purchase_at,
                    r.id AS referrer_id, r.status,
                    rc.msisdn AS referrer_msisdn, rc.name AS referrer_name, rc.fcm_token AS referrer_token
               FROM ' . ref_t('customers') . ' c
               JOIN ' . ref_t('referrals') . ' rf ON rf.referred_customer_id = c.id
               JOIN ' . ref_t('referrers') . ' r  ON r.customer_id = rf.referrer_customer_id
               JOIN ' . ref_t('customers') . ' rc ON rc.id = r.customer_id
              WHERE c.msisdn = ? LIMIT 1'
        );
        $stmt->execute([$payer]);
        $link = $stmt->fetch();
        if (!$link) {
            return; // Not a referred customer.
        }
        if ($link['status'] === 'BANNED' || $link['status'] === 'EARN_BLOCKED') {
            return;
        }

        $offerId = (string) ($payment['offer_id'] ?? '');
        $amountKsh = (int) ($payment['amount'] ?? 0);
        $bps = ref_rate_bps($pdo, $offerId, $settings);

        // Integer cents, rounding DOWN. Never a float.
        $commission = intdiv($amountKsh * 100 * $bps, 10000);

        $paymentId = (int) ($payment['id'] ?? 0);
        $referrerId = (int) $link['referrer_id'];
        $referralId = (int) $link['referral_id'];

        $pdo->beginTransaction();

        // Mark the referee's first purchase. This is what unlocks a held signup
        // bonus, so it happens whether or not there is any commission to accrue.
        $pdo->prepare(
            'UPDATE ' . ref_t('referrals') . '
                SET purchases_count = purchases_count + 1,
                    first_purchase_at = COALESCE(first_purchase_at, ?)
              WHERE id = ?'
        )->execute([ref_now(), $referralId]);

        $wrote = false;
        if ($commission > 0) {
            $wrote = ref_ledger_add(
                $pdo,
                $referrerId,
                'EARN',
                $commission,
                ref_now_plus_hours((int) $settings['referral_hold_hours']),
                'payment',
                $paymentId,
                $referralId,
                'earn:payment:' . $paymentId,
                $offerId . ' @ ' . $bps . 'bps'
            );
            if ($wrote) {
                $pdo->prepare(
                    'UPDATE ' . ref_t('referrals') . ' SET earned_cents = earned_cents + ? WHERE id = ?'
                )->execute([$commission, $referralId]);
            }
        }

        $pdo->commit();

        if ($wrote) {
            ref_log($pdo, 'EARNED', $referrerId, $paymentId, [
                'commission_cents' => $commission,
                'bps'              => $bps,
                'offer'            => $offerId,
                'amount_ksh'       => $amountKsh,
            ]);
            ref_notify_earned($pdo, $settings, $link, $commission, $referrerId);
        }
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) {
            $pdo->rollBack();
        }
        error_log('[referrals] accrual failed: ' . $e->getMessage());
    }
}

/* -------------------------------------------------------------------------- */
/* Outbox                                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Queue a notification. Never sends inline: a slow SMS provider inside the
 * Daraja callback would stall the callback and invite Safaricom to retry it.
 */
function ref_outbox_queue(
    PDO $pdo,
    string $channel,
    string $target,
    string $title,
    string $body,
    string $template,
    string $idempotencyKey
): bool {
    if (trim($target) === '') {
        return false;
    }
    try {
        $pdo->prepare(
            'INSERT INTO ' . ref_t('outbox') . '
                (channel, target, title, body, template, idempotency_key, next_attempt_at, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        )->execute([
            $channel, $target, mb_substr($title, 0, 120), $body, $template,
            $idempotencyKey, ref_now(), ref_now(),
        ]);
        return true;
    } catch (PDOException $e) {
        return false; // Duplicate idempotency key — already queued.
    }
}

/** Push-only: commission events are frequent, and SMS here would be the biggest running cost. */
function ref_notify_earned(PDO $pdo, array $settings, array $link, int $commissionCents, int $referrerId): void
{
    $amount = number_format($commissionCents / 100, 2);
    $token = (string) ($link['referrer_token'] ?? '');
    ref_outbox_queue(
        $pdo,
        'PUSH',
        $token,
        'You earned Ksh ' . $amount,
        'Someone you referred just bought a bundle. Your commission balance has gone up.',
        'commission_earned',
        'push:earn:' . $referrerId . ':' . substr(hash('sha256', (string) microtime(true)), 0, 16)
    );

    // Crossing the withdrawal threshold is the moment an accrual becomes an
    // action, so that one gets an SMS as well as a push.
    $balances = ref_balances($pdo, $referrerId, $settings);
    $min = (int) $settings['referral_min_withdraw_cents'];
    if ($balances['available_cents'] >= $min) {
        $msisdn = (string) ($link['referrer_msisdn'] ?? '');
        $key = 'threshold:' . $referrerId . ':' . intdiv($balances['available_cents'], max(1, $min));
        ref_outbox_queue(
            $pdo, 'PUSH', $token,
            'You can withdraw now',
            'Your commission has reached Ksh ' . number_format($balances['available_cents'] / 100, 2)
                . '. Open Skylink Bingwa to send it to M-Pesa.',
            'threshold_reached', 'push:' . $key
        );
        ref_outbox_queue(
            $pdo, 'SMS', $msisdn, '',
            'Skylink Bingwa: your referral commission is now Ksh '
                . number_format($balances['available_cents'] / 100, 2)
                . '. Open the app to withdraw it to M-Pesa.',
            'threshold_reached', 'sms:' . $key
        );
    }
}

/**
 * "Someone joined with your code."
 *
 * Rate limited per referrer per Nairobi day: without a cap, anyone guessing
 * codes could burn the business's SMS credit.
 */
function ref_notify_joined(PDO $pdo, array $settings, array $referrer, string $newName, int $referralId): void
{
    $token = (string) ($referrer['referrer_token'] ?? '');
    $msisdn = (string) ($referrer['referrer_msisdn'] ?? '');
    $first = trim(explode(' ', trim($newName))[0] ?? '');
    $who = $first !== '' ? $first : 'Someone';

    ref_outbox_queue(
        $pdo, 'PUSH', $token,
        $who . ' joined with your code',
        'They downloaded Skylink Bingwa using your referral code. You will earn commission every time they buy.',
        'referral_joined', 'push:joined:' . $referralId
    );

    $dayStart = ref_nairobi_day_start_utc();
    $countStmt = $pdo->prepare(
        'SELECT COUNT(*) AS c FROM ' . ref_t('outbox') . '
          WHERE channel = ? AND target = ? AND template = ? AND created_at >= ?'
    );
    $countStmt->execute(['SMS', $msisdn, 'referral_joined', $dayStart]);
    if ((int) $countStmt->fetch()['c'] < (int) $settings['referral_join_sms_daily_cap']) {
        ref_outbox_queue(
            $pdo, 'SMS', $msisdn, '',
            'Skylink Bingwa: ' . $who . ' just joined using your referral code '
                . $referrer['code'] . '. You earn commission on every bundle they buy.',
            'referral_joined', 'sms:joined:' . $referralId
        );
    }
}

/* -------------------------------------------------------------------------- */
/* Bearer tokens                                                               */
/* -------------------------------------------------------------------------- */

/**
 * Resolve an Authorization: Bearer token to its customer id.
 *
 * X-App-Key is a constant compiled into the APK and is extractable by anyone who
 * decompiles it; it is a coarse gate on public reads and is NEVER sufficient for
 * anything that moves money. Every Earn and withdrawal endpoint additionally
 * requires one of these tokens, which is issued only after an OTP round-trip.
 */
function ref_auth_customer_id(PDO $pdo): ?int
{
    $header = '';
    foreach (['HTTP_AUTHORIZATION', 'REDIRECT_HTTP_AUTHORIZATION'] as $key) {
        if (!empty($_SERVER[$key])) {
            $header = (string) $_SERVER[$key];
            break;
        }
    }
    if ($header === '' && function_exists('apache_request_headers')) {
        $headers = apache_request_headers();
        $header = (string) ($headers['Authorization'] ?? $headers['authorization'] ?? '');
    }
    if (stripos($header, 'Bearer ') !== 0) {
        return null;
    }

    $token = trim(substr($header, 7));
    if ($token === '') {
        return null;
    }
    $hash = hash('sha256', $token);

    try {
        $stmt = $pdo->prepare(
            'SELECT customer_id FROM ' . ref_t('device_tokens') . '
              WHERE token_hash = ? AND revoked_at IS NULL LIMIT 1'
        );
        $stmt->execute([$hash]);
        $row = $stmt->fetch();
        if (!$row) {
            return null;
        }
        $pdo->prepare('UPDATE ' . ref_t('device_tokens') . ' SET last_seen_at = ? WHERE token_hash = ?')
            ->execute([ref_now(), $hash]);
        return (int) $row['customer_id'];
    } catch (Throwable $e) {
        return null;
    }
}

/** Issue a fresh bearer token. Only its SHA-256 is stored. */
function ref_issue_token(PDO $pdo, int $customerId, ?string $deviceHash): string
{
    $token = bin2hex(random_bytes(32));
    $pdo->prepare(
        'INSERT INTO ' . ref_t('device_tokens') . ' (customer_id, token_hash, device_hash, issued_at, last_seen_at)
         VALUES (?, ?, ?, ?, ?)'
    )->execute([$customerId, hash('sha256', $token), $deviceHash, ref_now(), ref_now()]);
    return $token;
}

/* -------------------------------------------------------------------------- */
/* SMS                                                                         */
/* -------------------------------------------------------------------------- */

/**
 * Send one SMS through the configured bulk provider.
 *
 * Same provider and sender id the fulfilment SMS already uses (lib.php), lifted
 * into a general helper because the referral system sends to customers rather
 * than only to the owner's fulfilment phone. Best-effort and never throws:
 * returns false when SMS is unconfigured or the provider refuses, and the outbox
 * retries on its own schedule.
 */
/** Thin wrapper so referral call sites read as referral code; see hostpinnacle_send_sms() in lib.php. */
function ref_send_sms(array $config, string $msisdn, string $message): bool
{
    return hostpinnacle_send_sms($config, $msisdn, $message);
}

/* -------------------------------------------------------------------------- */
/* Withdrawal settlement                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Mark a withdrawal PAID and make its hold permanent.
 *
 * The guarded UPDATE (status IN the not-yet-final set) is what makes this
 * idempotent: Safaricom delivers duplicate results, and the reconciler may race
 * a genuine callback. Exactly one caller flips the row, so exactly one caller
 * writes the settle entry — and the ledger's idempotency key catches even that
 * if the guard is ever loosened.
 *
 * WITHDRAW_SETTLE carries amount 0 on purpose. The hold already removed the money
 * when the request was made; settling only records that the hold became a real
 * payout. Writing a negative amount here would debit the referrer twice.
 */
function ref_withdrawal_settle(PDO $pdo, array $w, string $receipt, ?string $transactionId, string $resultDesc): bool
{
    $id = (int) $w['id'];
    $upd = $pdo->prepare(
        'UPDATE ' . ref_t('withdrawals') . '
            SET status = ?, mpesa_receipt = ?, transaction_id = COALESCE(?, transaction_id),
                result_code = ?, result_desc = ?, resolved_at = ?, updated_at = ?
          WHERE id = ? AND status IN (?, ?, ?, ?)'
    );
    $upd->execute([
        'PAID', $receipt, $transactionId, '0', mb_substr($resultDesc, 0, 191), ref_now(), ref_now(),
        $id, 'REQUESTED', 'SUBMITTING', 'SUBMITTED', 'UNKNOWN',
    ]);
    if ($upd->rowCount() !== 1) {
        return false;   // Already resolved by an earlier result. Nothing to do.
    }

    $pdo->beginTransaction();
    ref_ledger_add(
        $pdo, (int) $w['referrer_id'], 'WITHDRAW_SETTLE', 0, ref_now(),
        'withdrawal', $id, null, 'settle:withdrawal:' . $id, 'Paid ' . $receipt
    );
    $pdo->prepare(
        'UPDATE ' . ref_t('referrers') . '
            SET lifetime_paid_cents = lifetime_paid_cents + ?, updated_at = ? WHERE id = ?'
    )->execute([(int) $w['amount_cents'], ref_now(), (int) $w['referrer_id']]);
    $pdo->commit();

    ref_log($pdo, 'WITHDRAWAL_PAID', (int) $w['referrer_id'], $id, [
        'amount_cents' => (int) $w['amount_cents'],
        'receipt'      => $receipt,
    ]);
    return true;
}

/**
 * Mark a withdrawal FAILED and release its hold.
 *
 * ONLY call this when Safaricom has said, authoritatively, that the money did not
 * move: an up-front rejection, or a result callback carrying a non-zero code.
 * NEVER call it for a timeout, a transport error or an unanswered query — the
 * payment may have gone through, and releasing the hold on a successful payout
 * pays the customer twice. Those cases belong in UNKNOWN until Safaricom answers.
 */
function ref_withdrawal_refund(PDO $pdo, array $w, string $resultCode, string $resultDesc): bool
{
    $id = (int) $w['id'];
    $upd = $pdo->prepare(
        'UPDATE ' . ref_t('withdrawals') . '
            SET status = ?, result_code = ?, result_desc = ?, resolved_at = ?, updated_at = ?
          WHERE id = ? AND status IN (?, ?, ?, ?)'
    );
    $upd->execute([
        'FAILED', mb_substr($resultCode, 0, 12), mb_substr($resultDesc, 0, 191), ref_now(), ref_now(),
        $id, 'REQUESTED', 'SUBMITTING', 'SUBMITTED', 'UNKNOWN',
    ]);
    if ($upd->rowCount() !== 1) {
        return false;
    }

    $pdo->beginTransaction();
    ref_ledger_add(
        $pdo, (int) $w['referrer_id'], 'WITHDRAW_REFUND', (int) $w['amount_cents'], ref_now(),
        'withdrawal', $id, null, 'refund:withdrawal:' . $id, 'Payout failed: ' . $resultDesc
    );
    $pdo->commit();

    ref_log($pdo, 'WITHDRAWAL_FAILED', (int) $w['referrer_id'], $id, [
        'amount_cents' => (int) $w['amount_cents'],
        'result_code'  => $resultCode,
        'result_desc'  => $resultDesc,
    ]);
    return true;
}

/** Pull the withdrawal id out of an originator id we minted ("SKB-12-ab..." / "SKBQ-12-ab..."). */
function ref_withdrawal_id_from_originator(string $originatorId): ?int
{
    if (preg_match('/^SKBQ?-(\d+)-/', $originatorId, $m)) {
        return (int) $m[1];
    }
    return null;
}
