<?php
/**
 * GET|POST check_referral_code.php?code=SK391R — live validation while the
 * customer types a referral code during onboarding.
 *
 * Response: { valid: bool, referrerName?: "John" }
 *
 * Deliberately returns the referrer's FIRST NAME ONLY. The app shows "You were
 * referred by John" so the customer knows the code landed on a real person, and
 * nothing more leaks: not a surname, not a number, not a customer count. Codes
 * are short enough to brute-force, so this endpoint is also rate limited per IP —
 * without that it would be an enumeration oracle over the customer base.
 *
 * This endpoint NEVER decides attribution. register_user.php does, server-side,
 * against the full fraud ruleset. A "valid" answer here is a courtesy to the UI,
 * not a promise.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

require_app_key($config);

$code = ref_code_normalise((string) ($_GET['code'] ?? $_POST['code'] ?? ''));
if (!ref_code_valid($code)) {
    json_out(['valid' => false]);
}

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);

// Rate limit by IP using the event log: 40 lookups per hour is far more than a
// person typing one code, and far less than a useful brute-force run.
$ip = client_ip($config);
try {
    $stmt = $pdo->prepare(
        'SELECT COUNT(*) AS c FROM ' . ref_t('commission_events') . '
          WHERE event = ? AND detail LIKE ? AND created_at >= ?'
    );
    $stmt->execute(['CODE_LOOKUP', '%"ip":"' . $ip . '"%', gmdate('Y-m-d H:i:s', time() - 3600)]);
    if ((int) $stmt->fetch()['c'] > 40) {
        json_out(['valid' => false, 'errorCode' => 'RATE_LIMITED'], 429);
    }
} catch (Throwable $e) {
    // Never fail a lookup because the limiter itself is unavailable.
}
ref_log($pdo, 'CODE_LOOKUP', null, null, ['ip' => $ip, 'code' => $code]);

try {
    $stmt = $pdo->prepare(
        'SELECT c.name FROM ' . ref_t('referrers') . ' r
           JOIN ' . ref_t('customers') . ' c ON c.id = r.customer_id
          WHERE r.code = ? AND r.status <> ? LIMIT 1'
    );
    $stmt->execute([$code, 'BANNED']);
    $row = $stmt->fetch();
} catch (Throwable $e) {
    json_out(['valid' => false]);
}

if (!$row) {
    json_out(['valid' => false]);
}

$first = trim(explode(' ', trim((string) $row['name']))[0] ?? '');
json_out([
    'valid'        => true,
    'referrerName' => $first !== '' ? $first : 'a Skylink Bingwa customer',
]);
