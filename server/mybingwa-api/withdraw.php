<?php
/**
 * POST withdraw.php — request a commission payout to M-Pesa.
 *
 * Request:  { }                              Header: X-App-Key
 *           Authorization: Bearer <token>    REQUIRED — no exceptions
 * Response: { status: "REQUESTED"|"FAILED", withdrawalId?, amountCents?, errorCode? }
 *
 * WHAT THIS ENDPOINT DELIBERATELY DOES NOT ACCEPT
 *
 *   * The destination number. It is read from referrers.verified_msisdn. A payout
 *     target supplied by the caller is the whole attack: forge one request, drain
 *     the float, and M-Pesa B2C is not reversible.
 *   * The amount. The server pays out the entire available balance. There is no
 *     amount field to tamper with and no partial-withdrawal maths to get wrong.
 *
 * WHAT IT DOES NOT DO: talk to Daraja. This endpoint only writes an intent and
 * places the hold, atomically. cron_referrals.php submits it. That split means a
 * customer's tap can never be blocked by a slow Daraja, and a retry of the tap
 * can never produce a second payout.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';
require_once __DIR__ . '/b2c.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    json_out(['status' => 'FAILED', 'errorCode' => 'METHOD_NOT_ALLOWED'], 405);
}
require_app_key($config);

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);
$settings = ref_settings($pdo);

// Identity comes from the bearer token alone. There is no msisdn parameter here
// precisely so there is nothing to spoof.
$customerId = ref_auth_customer_id($pdo);
if ($customerId === null) {
    json_out(['status' => 'FAILED', 'errorCode' => 'UNAUTHENTICATED'], 401);
}

if ((int) $settings['referral_payouts_enabled'] !== 1) {
    // The kill switch. Requests are refused cleanly rather than queued, so the
    // customer is told the truth instead of watching a payout that never moves.
    json_out(['status' => 'FAILED', 'errorCode' => 'PAYOUTS_DISABLED'], 503);
}

try {
    $pdo->beginTransaction();

    // FOR UPDATE serialises every balance-moving decision for this referrer. Two
    // taps landing at the same instant queue here; the second sees the first's
    // hold and fails the balance check instead of double-paying.
    $stmt = $pdo->prepare('SELECT * FROM ' . ref_t('referrers') . ' WHERE customer_id = ? FOR UPDATE');
    $stmt->execute([$customerId]);
    $referrer = $stmt->fetch();
    if (!$referrer) {
        $pdo->rollBack();
        json_out(['status' => 'FAILED', 'errorCode' => 'NO_REFERRER'], 404);
    }
    $referrerId = (int) $referrer['id'];

    $fail = static function (string $code, int $http = 409) use ($pdo) {
        $pdo->rollBack();
        json_out(['status' => 'FAILED', 'errorCode' => $code], $http);
    };

    if ($referrer['verified_msisdn'] === null) {
        $fail('NOT_VERIFIED', 403);
    }
    if ($referrer['status'] !== 'ACTIVE') {
        // EARN_BLOCKED / PAYOUT_BLOCKED / BANNED. A held payout under review is
        // recoverable; a drained float is not.
        $fail('ACCOUNT_' . $referrer['status']);
    }
    if ($referrer['payout_frozen_until'] !== null && $referrer['payout_frozen_until'] > ref_now()) {
        $fail('PAYOUT_FROZEN');
    }

    // One in flight at a time. Enforced inside the lock, so it cannot be raced.
    $inflight = $pdo->prepare(
        'SELECT COUNT(*) AS c FROM ' . ref_t('withdrawals') . '
          WHERE referrer_id = ? AND status IN (?, ?, ?, ?)'
    );
    $inflight->execute([$referrerId, 'REQUESTED', 'SUBMITTING', 'SUBMITTED', 'UNKNOWN']);
    if ((int) $inflight->fetch()['c'] > 0) {
        $fail('WITHDRAWAL_IN_PROGRESS');
    }

    // Cooldown. Every payout costs the business a B2C charge it absorbs, so a
    // daily drip of tiny withdrawals is pure loss.
    $cooldownHours = (int) $settings['referral_cooldown_hours'];
    if ($cooldownHours > 0) {
        $recent = $pdo->prepare(
            'SELECT COUNT(*) AS c FROM ' . ref_t('withdrawals') . '
              WHERE referrer_id = ? AND status = ? AND resolved_at >= ?'
        );
        $recent->execute([$referrerId, 'PAID', gmdate('Y-m-d H:i:s', time() - ($cooldownHours * 3600))]);
        if ((int) $recent->fetch()['c'] > 0) {
            $fail('COOLDOWN_ACTIVE');
        }
    }

    $balances = ref_balances($pdo, $referrerId, $settings);
    $available = $balances['available_cents'];
    $min = (int) $settings['referral_min_withdraw_cents'];
    $max = (int) $settings['referral_max_withdraw_cents'];

    if ($available < $min) {
        $fail('BELOW_MINIMUM');
    }
    $amount = min($available, $max);

    // B2C takes whole shillings. Round DOWN and leave the remainder in the
    // balance rather than paying a shilling the ledger never earned.
    $amountKsh = intdiv($amount, 100);
    $amount = $amountKsh * 100;
    if ($amountKsh < 1) {
        $fail('BELOW_MINIMUM');
    }

    // Business-wide daily circuit breaker: a runaway bug or a farm that got past
    // every other check still cannot empty the account in one night.
    $dayStart = ref_nairobi_day_start_utc();
    $today = $pdo->prepare(
        'SELECT COALESCE(SUM(amount_cents), 0) AS t FROM ' . ref_t('withdrawals') . '
          WHERE requested_at >= ? AND status NOT IN (?, ?)'
    );
    $today->execute([$dayStart, 'FAILED', 'CANCELLED']);
    if (((int) $today->fetch()['t'] + $amount) > (int) $settings['referral_daily_cap_cents']) {
        $fail('DAILY_CAP_REACHED', 503);
    }

    // Write the intent. The originator id is generated here, before anything
    // leaves this server, so a payout can always be traced back to exactly one row.
    $pdo->prepare(
        'INSERT INTO ' . ref_t('withdrawals') . '
            (referrer_id, amount_cents, msisdn, status, originator_conversation_id, requested_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)'
    )->execute([
        $referrerId,
        $amount,
        // Snapshot the number NOW: a payout in flight must never be retargeted by
        // a later number change.
        (string) $referrer['verified_msisdn'],
        'REQUESTED',
        'PENDING-' . bin2hex(random_bytes(12)),   // replaced with the real id at submit time
        ref_now(),
        ref_now(),
    ]);
    $withdrawalId = (int) $pdo->lastInsertId();

    // The hold, in the SAME transaction as the intent. This is what makes
    // "check the balance, then pay" impossible: the money leaves `available` the
    // instant the request exists, so a concurrent request sees the reduced figure.
    $held = ref_ledger_add(
        $pdo,
        $referrerId,
        'WITHDRAW_HOLD',
        -$amount,
        ref_now(),
        'withdrawal',
        $withdrawalId,
        null,
        'hold:withdrawal:' . $withdrawalId,
        'Withdrawal requested'
    );
    if (!$held) {
        $pdo->rollBack();
        json_out(['status' => 'FAILED', 'errorCode' => 'HOLD_FAILED'], 500);
    }

    $pdo->commit();
} catch (Throwable $e) {
    if ($pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('[withdraw] ' . $e->getMessage());
    json_out(['status' => 'FAILED', 'errorCode' => 'SERVER_ERROR'], 500);
}

ref_log($pdo, 'WITHDRAWAL_REQUESTED', $referrerId, $withdrawalId, [
    'amount_cents' => $amount,
    'msisdn'       => $referrer['verified_msisdn'],
]);

json_out([
    'status'       => 'REQUESTED',
    'withdrawalId' => $withdrawalId,
    'amountCents'  => $amount,
    'message'      => 'Sending Ksh ' . number_format($amount / 100, 2) . ' to '
                      . $referrer['verified_msisdn'] . '. This usually takes under a minute.',
]);
