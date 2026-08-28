<?php
/**
 * POST referral_summary.php — everything the Earn screen renders.
 *
 * Request:  { msisdn }                     Header: X-App-Key
 *           Authorization: Bearer <token>  (optional — see below)
 *
 * Two access levels, on purpose:
 *
 *   WITHOUT a bearer token the caller gets the read-only view: their code, their
 *   balances, their referee list. This keeps the Earn screen instant on first
 *   open, with no OTP friction for someone who only wants to see and share their
 *   code. It is gated on X-App-Key + the msisdn, which is a weak identity — so it
 *   exposes nothing an attacker could monetise and nothing about anyone else.
 *
 *   WITH a bearer token the response additionally reports canWithdraw and the
 *   verified payout number. Withdrawal ITSELF lives in withdraw.php and requires
 *   the token unconditionally: X-App-Key is compiled into the APK and extractable,
 *   so it is never sufficient for anything that moves money.
 *
 * The app never computes a balance. Every figure here is derived from the ledger
 * on the server.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

require_app_key($config);

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$digits = preg_replace('/\D/', '', (string) ($body['msisdn'] ?? ''));
$tail = substr($digits, -9);
if (strlen($tail) !== 9 || !preg_match('/^[71]/', $tail)) {
    json_out(['status' => 'FAILED', 'errorCode' => 'BAD_MSISDN'], 400);
}
$msisdn = '254' . $tail;

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);
$settings = ref_settings($pdo);

$cust = $pdo->prepare('SELECT id, name FROM ' . ref_t('customers') . ' WHERE msisdn = ? LIMIT 1');
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
$referrerId = (int) $referrer['id'];

// A token that resolves to a DIFFERENT customer is ignored rather than honoured:
// it must never grant a view of someone else's earnings.
$authedId = ref_auth_customer_id($pdo);
$isAuthed = ($authedId !== null && $authedId === $customerId);

$balances = ref_balances($pdo, $referrerId, $settings);

// Referees, newest first. First names only — a referrer has no business seeing
// the full identity or number of everyone who used their code.
$refStmt = $pdo->prepare(
    'SELECT rf.id, rf.attributed_at, rf.first_purchase_at, rf.purchases_count, rf.earned_cents, c.name
       FROM ' . ref_t('referrals') . ' rf
       JOIN ' . ref_t('customers') . ' c ON c.id = rf.referred_customer_id
      WHERE rf.referrer_customer_id = ?
      ORDER BY rf.attributed_at DESC
      LIMIT 100'
);
$refStmt->execute([$customerId]);
$referees = [];
foreach ($refStmt->fetchAll() as $r) {
    $first = trim(explode(' ', trim((string) $r['name']))[0] ?? '');
    $referees[] = [
        'name'           => $first !== '' ? $first : 'Customer',
        'joinedAt'       => $r['attributed_at'],
        'hasPurchased'   => $r['first_purchase_at'] !== null,
        'purchasesCount' => (int) $r['purchases_count'],
        'earnedCents'    => (int) $r['earned_cents'],
    ];
}

// Recent ledger, for the "where did this come from" list.
$ledStmt = $pdo->prepare(
    'SELECT entry_type, amount_cents, matures_at, note, created_at
       FROM ' . ref_t('commission_ledger') . '
      WHERE referrer_id = ? ORDER BY id DESC LIMIT 50'
);
$ledStmt->execute([$referrerId]);
$ledger = [];
foreach ($ledStmt->fetchAll() as $l) {
    $ledger[] = [
        'type'        => $l['entry_type'],
        'amountCents' => (int) $l['amount_cents'],
        'maturesAt'   => $l['matures_at'],
        'note'        => $l['note'],
        'createdAt'   => $l['created_at'],
    ];
}

// In-flight or recent withdrawals.
$wStmt = $pdo->prepare(
    'SELECT id, amount_cents, status, mpesa_receipt, result_desc, requested_at, resolved_at
       FROM ' . ref_t('withdrawals') . '
      WHERE referrer_id = ? ORDER BY id DESC LIMIT 20'
);
$wStmt->execute([$referrerId]);
$withdrawals = [];
foreach ($wStmt->fetchAll() as $w) {
    $withdrawals[] = [
        'id'          => (int) $w['id'],
        'amountCents' => (int) $w['amount_cents'],
        // UNKNOWN is a real state and the app shows it honestly as "checking with
        // M-Pesa" — never as success, never as failure.
        'status'      => $w['status'],
        'receipt'     => $w['mpesa_receipt'],
        'detail'      => $w['result_desc'],
        'requestedAt' => $w['requested_at'],
        'resolvedAt'  => $w['resolved_at'],
    ];
}

$hasInFlight = false;
foreach ($withdrawals as $w) {
    if (in_array($w['status'], ['REQUESTED', 'SUBMITTING', 'SUBMITTED', 'UNKNOWN'], true)) {
        $hasInFlight = true;
        break;
    }
}

$min = (int) $settings['referral_min_withdraw_cents'];
$frozen = $referrer['payout_frozen_until'] !== null && $referrer['payout_frozen_until'] > ref_now();

json_out([
    'status'              => 'OK',
    'code'                => $referrer['code'],
    'shareMessage'        => 'Get cheap Safaricom data, SMS and minutes on Skylink Bingwa. '
                             . 'Use my referral code ' . $referrer['code'] . ' when you sign up.',
    'balanceCents'        => $balances['total_cents'],
    'availableCents'      => $balances['available_cents'],
    'pendingCents'        => $balances['pending_cents'],
    'lifetimeEarnedCents' => (int) $referrer['lifetime_earned_cents'],
    'lifetimePaidCents'   => (int) $referrer['lifetime_paid_cents'],
    'minWithdrawCents'    => $min,
    'signupBonusCents'    => (int) $settings['referral_signup_bonus_cents'],
    'bonusNeedsPurchase'  => (int) $settings['referral_bonus_requires_purchase'] === 1,
    'referralsCount'      => count($referees),
    'verified'            => $referrer['verified_msisdn'] !== null,
    'payoutMsisdn'        => $isAuthed ? $referrer['verified_msisdn'] : null,
    'accountStatus'       => $referrer['status'],
    'accountStatusReason' => $referrer['status'] !== 'ACTIVE' ? (string) ($referrer['status_reason'] ?? '') : '',
    'payoutsEnabled'      => (int) $settings['referral_payouts_enabled'] === 1,
    'frozen'              => $frozen,
    'hasInFlightWithdrawal' => $hasInFlight,
    'canWithdraw'         => $isAuthed
                             && $referrer['verified_msisdn'] !== null
                             && $referrer['status'] === 'ACTIVE'
                             && !$frozen
                             && !$hasInFlight
                             && $balances['available_cents'] >= $min
                             && (int) $settings['referral_payouts_enabled'] === 1,
    'referees'            => $referees,
    'ledger'              => $ledger,
    'withdrawals'         => $withdrawals,
]);
