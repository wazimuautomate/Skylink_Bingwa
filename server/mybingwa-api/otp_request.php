<?php
/**
 * POST otp_request.php — start phone verification.
 *
 * Request:  { msisdn, purpose? }   Header: X-App-Key
 * Response: { status: "SENT" | "FAILED", errorCode?, retryAfter? }
 *
 * Verification is LAZY: onboarding never asks for it. This fires the first time
 * a customer opens the Earn screen's withdraw flow, so a buyer who never earns
 * is never charged the friction and the business is never charged the SMS.
 *
 * The plain code is hashed immediately and never stored, never logged, and never
 * returned in the response — the only way to learn it is to hold the SIM.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    json_out(['status' => 'FAILED', 'errorCode' => 'METHOD_NOT_ALLOWED'], 405);
}
require_app_key($config);

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$digits = preg_replace('/\D/', '', (string) ($body['msisdn'] ?? ''));
$tail = substr($digits, -9);
if (strlen($tail) !== 9 || !preg_match('/^[71]/', $tail)) {
    json_out(['status' => 'FAILED', 'errorCode' => 'BAD_MSISDN'], 400);
}
$msisdn = '254' . $tail;
$purpose = ((string) ($body['purpose'] ?? '')) === 'CHANGE_PAYOUT' ? 'CHANGE_PAYOUT' : 'VERIFY_PAYOUT';

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);

// The customer must already be registered. This is not an account-creation path.
$cust = $pdo->prepare('SELECT id FROM ' . ref_t('customers') . ' WHERE msisdn = ? LIMIT 1');
$cust->execute([$msisdn]);
if (!$cust->fetch()) {
    json_out(['status' => 'FAILED', 'errorCode' => 'UNKNOWN_CUSTOMER'], 404);
}

// Rate limits, per number AND per source IP. Both matter: the per-number limit
// stops one victim being spammed, the per-IP limit stops one attacker walking a
// list of numbers and burning the SMS credit.
$hourAgo = gmdate('Y-m-d H:i:s', time() - 3600);
$dayAgo  = gmdate('Y-m-d H:i:s', time() - 86400);

$perNumber = $pdo->prepare(
    'SELECT
        SUM(created_at >= ?) AS last_hour,
        SUM(created_at >= ?) AS last_day
       FROM ' . ref_t('otp_challenges') . ' WHERE msisdn = ?'
);
$perNumber->execute([$hourAgo, $dayAgo, $msisdn]);
$counts = $perNumber->fetch() ?: ['last_hour' => 0, 'last_day' => 0];

if ((int) $counts['last_hour'] >= 3) {
    json_out(['status' => 'FAILED', 'errorCode' => 'TOO_MANY_REQUESTS', 'retryAfter' => 3600], 429);
}
if ((int) $counts['last_day'] >= 10) {
    json_out(['status' => 'FAILED', 'errorCode' => 'TOO_MANY_REQUESTS', 'retryAfter' => 86400], 429);
}

$ip = client_ip($config);
$perIp = $pdo->prepare(
    'SELECT COUNT(*) AS c FROM ' . ref_t('commission_events') . '
      WHERE event = ? AND detail LIKE ? AND created_at >= ?'
);
$perIp->execute(['OTP_REQUEST', '%"ip":"' . $ip . '"%', $hourAgo]);
if ((int) $perIp->fetch()['c'] >= 20) {
    json_out(['status' => 'FAILED', 'errorCode' => 'TOO_MANY_REQUESTS', 'retryAfter' => 3600], 429);
}

// random_int is cryptographically secure; rand()/mt_rand() would be guessable.
$code = str_pad((string) random_int(0, 999999), 6, '0', STR_PAD_LEFT);

try {
    // Invalidate any earlier live challenge for this number so only the newest
    // code works — otherwise every unexpired code stays a valid guess target.
    $pdo->prepare(
        'UPDATE ' . ref_t('otp_challenges') . ' SET consumed_at = ?
          WHERE msisdn = ? AND consumed_at IS NULL'
    )->execute([ref_now(), $msisdn]);

    $pdo->prepare(
        'INSERT INTO ' . ref_t('otp_challenges') . ' (msisdn, code_hash, purpose, expires_at, created_at)
         VALUES (?, ?, ?, ?, ?)'
    )->execute([
        $msisdn,
        hash('sha256', $msisdn . ':' . $code),
        $purpose,
        gmdate('Y-m-d H:i:s', time() + 600),   // 10 minutes
        ref_now(),
    ]);
} catch (Throwable $e) {
    json_out(['status' => 'FAILED', 'errorCode' => 'DB_WRITE_FAILED'], 500);
}

// The code itself is never recorded — only that a request happened, for the limiter.
ref_log($pdo, 'OTP_REQUEST', null, null, ['ip' => $ip, 'msisdn' => $msisdn, 'purpose' => $purpose]);

// Sent inline rather than through the outbox: an OTP the customer waits for is
// worthless a minute later. If the provider is down we say so honestly rather
// than leaving them staring at a code entry box.
$sent = ref_send_sms(
    $config,
    $msisdn,
    'Your Skylink Bingwa verification code is ' . $code . '. It expires in 10 minutes. Do not share it with anyone.'
);

if (!$sent) {
    json_out(['status' => 'FAILED', 'errorCode' => 'SMS_UNAVAILABLE'], 502);
}

json_out(['status' => 'SENT', 'expiresInSeconds' => 600]);
