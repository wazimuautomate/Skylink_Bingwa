<?php
/**
 * POST register_user.php — the app announces a new customer, once per install.
 *
 * Request (JSON, from the app):
 *   { name, msisdn, appVersion }
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON): { status: "REGISTERED" | "REGISTER_FAILED", errorCode? }
 *
 * The app calls this exactly once, at the end of onboarding, and remembers that it
 * succeeded so it never calls again. If the phone is offline at that moment the app
 * retries on a later launch — which is why this endpoint is idempotent on the
 * number: a customer who reinstalls (or whose retry lands twice) updates their
 * existing row rather than creating a duplicate.
 *
 * What is stored is only what the customer typed in: a name and a Safaricom number.
 * No purchase history, no behaviour, nothing derived — the phone keeps all of that
 * (CLAUDE.md §10). The admin panel reads this table on its Customers page.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    json_out(['status' => 'REGISTER_FAILED', 'errorCode' => 'METHOD_NOT_ALLOWED'], 405);
}
require_app_key($config);

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$name = trim((string) ($body['name'] ?? ''));
$appVersion = trim((string) ($body['appVersion'] ?? ''));
$digits = preg_replace('/\D/', '', (string) ($body['msisdn'] ?? ''));

// Canonical 2547XXXXXXXX / 2541XXXXXXXX, so one line is always one row whichever
// way the number was typed on the phone.
$tail = substr($digits, -9);
if (strlen($tail) !== 9 || !preg_match('/^[71]/', $tail)) {
    json_out(['status' => 'REGISTER_FAILED', 'errorCode' => 'BAD_MSISDN'], 400);
}
$msisdn = '254' . $tail;

// Trim to the column widths rather than rejecting: a long name is still a customer.
$name = mb_substr($name, 0, 80);
$appVersion = mb_substr($appVersion, 0, 24);
$fcmToken = trim((string) ($body['fcm_token'] ?? $body['fcmToken'] ?? ''));
$fcmToken = $fcmToken !== '' ? mb_substr($fcmToken, 0, 255) : null;

// Referral inputs. The code is optional and a bad one NEVER fails onboarding —
// a blocked first run is worse than a lost attribution. The device id is the
// spine of the anti-farming rules in referrals.php: it is hashed immediately and
// the raw value is never stored.
$referralCode = ref_code_normalise((string) ($body['referralCode'] ?? $body['referral_code'] ?? ''));
$deviceHash = ref_device_hash((string) ($body['deviceId'] ?? $body['device_id'] ?? ''));

$pdo = require __DIR__ . '/db.php';

// Auto-provision, exactly like payments in db.php, so the endpoint works on an
// install where the admin panel has not run its migrations yet. Same DDL as
// admin-v2/database/migrations/019_customers.sql.
try {
    $pdo->exec(
        'CREATE TABLE IF NOT EXISTS mb_customers (
            id            BIGINT AUTO_INCREMENT PRIMARY KEY,
            msisdn        VARCHAR(16)  NOT NULL,
            name          VARCHAR(80)  NOT NULL DEFAULT \'\',
            app_version   VARCHAR(24)  NOT NULL DEFAULT \'\',
            fcm_token     VARCHAR(255) NULL DEFAULT NULL,
            registrations INT          NOT NULL DEFAULT 1,
            created_at    DATETIME     NOT NULL,
            updated_at    DATETIME     NOT NULL,
            UNIQUE KEY uniq_customer_msisdn (msisdn),
            KEY idx_customer_created (created_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
    );
    // Ensure fcm_token column exists if table was created previously
    $pdo->exec('ALTER TABLE mb_customers ADD COLUMN fcm_token VARCHAR(255) NULL DEFAULT NULL');
} catch (Throwable $e) {
    // Already there, or this DB user cannot CREATE/ALTER — the insert below decides.
}

try {
    // Idempotent on the number: a reinstall or a retried call updates the name, token and
    // counts the registration instead of creating a second customer.
    $stmt = $pdo->prepare(
        'INSERT INTO mb_customers (msisdn, name, app_version, fcm_token, registrations, created_at, updated_at)
              VALUES (?, ?, ?, ?, 1, NOW(), NOW())
         ON DUPLICATE KEY UPDATE
              name = VALUES(name),
              app_version = VALUES(app_version),
              fcm_token = COALESCE(VALUES(fcm_token), fcm_token),
              registrations = registrations + 1,
              updated_at = NOW()'
    );
    $stmt->execute([$msisdn, $name, $appVersion, $fcmToken]);
} catch (Throwable $e) {
    json_out(['status' => 'REGISTER_FAILED', 'errorCode' => 'DB_WRITE_FAILED'], 500);
}

// --- Referral programme -----------------------------------------------------
// Everything below is best-effort. A customer is registered the moment the row
// above is written; nothing in the referral system may turn a successful
// registration into a failed one, so the whole block is wrapped and any failure
// simply returns the customer their own code (or none) without an error.
$myCode = '';
$referralApplied = false;
$referralReason = 'NONE';

try {
    ref_provision($pdo);
    $settings = ref_settings($pdo);

    // ON DUPLICATE KEY UPDATE does not give a usable lastInsertId, so read the
    // canonical row back by its natural key.
    $cust = $pdo->prepare('SELECT id, name FROM ' . ref_t('customers') . ' WHERE msisdn = ? LIMIT 1');
    $cust->execute([$msisdn]);
    $customer = $cust->fetch();

    if ($customer) {
        $customerId = (int) $customer['id'];

        // Bind the handset to this customer before any attribution decision, so
        // the device rules below see the current picture.
        if ($deviceHash !== null) {
            $pdo->prepare('UPDATE ' . ref_t('customers') . ' SET device_hash = COALESCE(device_hash, ?) WHERE id = ?')
                ->execute([$deviceHash, $customerId]);
            ref_touch_device($pdo, $deviceHash, $msisdn, $settings);
        }

        // Every customer gets their own code, whether or not they used one.
        $me = ref_ensure_referrer($pdo, $customerId);
        $myCode = $me['code'] ?? '';

        if ($referralCode !== '') {
            // Attribution writes four related rows — the referral, the device's
            // spent redemption, the referrer's counter and the bonus ledger entry.
            // They belong together: a referral recorded without its redemption
            // marked would hand the same handset a second free bonus.
            $pdo->beginTransaction();
            try {
                $result = ref_attribute($pdo, $settings, $customerId, $msisdn, $referralCode, $deviceHash);
                $pdo->commit();
            } catch (Throwable $e) {
                $pdo->rollBack();
                throw $e;
            }

            $referralApplied = $result['ok'];
            $referralReason = $result['reason'];

            // Queued only after the commit, so a notification can never announce a
            // referral that was rolled back.
            if ($result['ok'] && $result['referrer']) {
                ref_notify_joined($pdo, $settings, $result['referrer'], $name, (int) $result['referral_id']);
            }
        }
    }
} catch (Throwable $e) {
    error_log('[register_user] referral step failed: ' . $e->getMessage());
}

json_out([
    'status'          => 'REGISTERED',
    'referralCode'    => $myCode,
    'referralApplied' => $referralApplied,
    'referralReason'  => $referralReason,
]);
