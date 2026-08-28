<?php
/**
 * POST otp_verify.php — finish phone verification and issue a bearer token.
 *
 * Request:  { msisdn, code, deviceId? }   Header: X-App-Key
 * Response: { status: "VERIFIED"|"FAILED", token?, payoutMsisdn?, frozenUntil?, errorCode? }
 *
 * Success binds the PAYOUT NUMBER to the number just proved, and hands back a
 * long-lived bearer token that the app keeps. Every endpoint that moves money
 * requires that token: X-App-Key is a constant compiled into the APK and is
 * extractable by anyone who decompiles it, so it can never be what stands between
 * an attacker and the business's float.
 *
 * Changing an already-verified payout number additionally freezes withdrawals for
 * 48 hours — the defence against a stolen handset or a SIM swap being used to
 * drain a balance that took months to build.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

const OTP_MAX_ATTEMPTS = 5;
const PAYOUT_CHANGE_FREEZE_HOURS = 48;

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
$code = preg_replace('/\D/', '', (string) ($body['code'] ?? ''));
$deviceHash = ref_device_hash((string) ($body['deviceId'] ?? $body['device_id'] ?? ''));

if (strlen($code) !== 6) {
    json_out(['status' => 'FAILED', 'errorCode' => 'BAD_CODE'], 400);
}

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);

$stmt = $pdo->prepare(
    'SELECT * FROM ' . ref_t('otp_challenges') . '
      WHERE msisdn = ? AND consumed_at IS NULL AND expires_at > ?
      ORDER BY id DESC LIMIT 1'
);
$stmt->execute([$msisdn, ref_now()]);
$challenge = $stmt->fetch();

if (!$challenge) {
    json_out(['status' => 'FAILED', 'errorCode' => 'NO_ACTIVE_CODE'], 400);
}

// Burn the challenge once the attempt budget is gone, so a wrong-code loop
// cannot walk the 1,000,000 space.
if ((int) $challenge['attempts'] >= OTP_MAX_ATTEMPTS) {
    $pdo->prepare('UPDATE ' . ref_t('otp_challenges') . ' SET consumed_at = ? WHERE id = ?')
        ->execute([ref_now(), (int) $challenge['id']]);
    json_out(['status' => 'FAILED', 'errorCode' => 'TOO_MANY_ATTEMPTS'], 429);
}

$pdo->prepare('UPDATE ' . ref_t('otp_challenges') . ' SET attempts = attempts + 1 WHERE id = ?')
    ->execute([(int) $challenge['id']]);

// hash_equals: constant-time, so a timing side-channel cannot leak the code.
if (!hash_equals((string) $challenge['code_hash'], hash('sha256', $msisdn . ':' . $code))) {
    ref_log($pdo, 'OTP_FAILED', null, null, ['msisdn' => $msisdn]);
    json_out([
        'status'           => 'FAILED',
        'errorCode'        => 'WRONG_CODE',
        'attemptsRemaining' => max(0, OTP_MAX_ATTEMPTS - ((int) $challenge['attempts'] + 1)),
    ], 400);
}

// Correct. Consume it — a code is single-use.
$pdo->prepare('UPDATE ' . ref_t('otp_challenges') . ' SET consumed_at = ? WHERE id = ?')
    ->execute([ref_now(), (int) $challenge['id']]);

$cust = $pdo->prepare('SELECT id FROM ' . ref_t('customers') . ' WHERE msisdn = ? LIMIT 1');
$cust->execute([$msisdn]);
$customer = $cust->fetch();
if (!$customer) {
    json_out(['status' => 'FAILED', 'errorCode' => 'UNKNOWN_CUSTOMER'], 404);
}
$customerId = (int) $customer['id'];

$referrer = ref_ensure_referrer($pdo, $customerId);
if (!$referrer) {
    json_out(['status' => 'FAILED', 'errorCode' => 'NO_CODE_AVAILABLE'], 503);
}

// A CHANGE of an existing payout number starts the freeze. A first-time
// verification does not — there is nothing yet to protect, and freezing it would
// only punish an honest new earner.
$previous = $referrer['verified_msisdn'];
$isChange = $previous !== null && $previous !== $msisdn;
$frozenUntil = $isChange ? ref_now_plus_hours(PAYOUT_CHANGE_FREEZE_HOURS) : $referrer['payout_frozen_until'];

$pdo->prepare(
    'UPDATE ' . ref_t('referrers') . '
        SET verified_msisdn = ?, verified_at = ?, payout_frozen_until = ?, updated_at = ?
      WHERE id = ?'
)->execute([$msisdn, ref_now(), $frozenUntil, ref_now(), (int) $referrer['id']]);

// Revoke this customer's older tokens: verifying again means "this handset is my
// handset now", and a token left live on a device they no longer hold is exactly
// the thing the freeze exists to contain.
$pdo->prepare(
    'UPDATE ' . ref_t('device_tokens') . ' SET revoked_at = ? WHERE customer_id = ? AND revoked_at IS NULL'
)->execute([ref_now(), $customerId]);

$token = ref_issue_token($pdo, $customerId, $deviceHash);

ref_log($pdo, $isChange ? 'PAYOUT_NUMBER_CHANGED' : 'OTP_VERIFIED', (int) $referrer['id'], $customerId, [
    'msisdn'   => $msisdn,
    'previous' => $previous,
    'frozen'   => $isChange,
]);

if ($isChange) {
    ref_send_sms(
        $config,
        $msisdn,
        'Skylink Bingwa: your commission payout number was changed. '
            . 'Withdrawals are paused for 48 hours for your security. '
            . 'If this was not you, contact support immediately.'
    );
}

json_out([
    'status'       => 'VERIFIED',
    'token'        => $token,
    'payoutMsisdn' => $msisdn,
    'frozenUntil'  => $isChange ? $frozenUntil : null,
]);
