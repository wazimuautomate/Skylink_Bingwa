<?php
/**
 * POST stk.php  — start an M-Pesa STK Push for buy-for-myself.
 *
 * Request (JSON, from the app):
 *   { offerId, payerMsisdn, recipientMsisdn, clientRequestId, amountKsh,
 *     forSelf, route }
 *   Header: X-App-Key: <shared secret>
 *
 *   forSelf (bool) / route ("self"|"another") pick the M-Pesa product:
 *     self    → Till / Buy-Goods (default, backward compatible)
 *     another → Paybill, AccountReference = recipient MSISDN
 *
 * Response (JSON, to the app):
 *   { status, orderReference, customerMessage, errorCode }
 *   status is one of: PAYMENT_REQUESTED | PAYMENT_FAILED
 *
 * The server recomputes the amount from offerId; the app's amount is ignored. The
 * price comes from the SAME published catalogue `get_offers.php` serves the app
 * (see `offer_price()` in lib.php), so the displayed price and the charged price
 * can never drift apart, and an un-published offer stops being payable.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
// Static price map — the last-resort fallback inside offer_price() when neither the
// published snapshot nor the legacy offers table can be read.
$fallbackPrices = require __DIR__ . '/offers.php';

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'METHOD_NOT_ALLOWED'], 405);
}
require_app_key($config);

$body = json_decode(file_get_contents('php://input'), true) ?: [];
$offerId   = (string) ($body['offerId'] ?? '');
$payer     = preg_replace('/\D/', '', (string) ($body['payerMsisdn'] ?? ''));
$recipient = preg_replace('/\D/', '', (string) ($body['recipientMsisdn'] ?? ''));
$clientId  = (string) ($body['clientRequestId'] ?? '');
$forSelf   = stk_is_self($body);

if ($offerId === '' || $clientId === '' || strlen($payer) < 12) {
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'BAD_REQUEST'], 400);
}
if (!$forSelf && strlen($recipient) < 12) {
    // Buy-for-another needs a real bundle recipient for the Paybill account.
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'RECIPIENT_REQUIRED'], 400);
}
$pdo = require __DIR__ . '/db.php';

// Price from the published catalogue (falling back to the legacy table, then the
// static map). Null = the offer is not currently published/active → not payable.
$amount = offer_price($pdo, $offerId, $fallbackPrices);
if ($amount === null) {
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'UNKNOWN_OFFER'], 400);
}

// --- Safaricom's own selling rules -----------------------------------------
// Refused BEFORE the payments row is claimed and before Daraja is called, so a
// refusal costs the customer nothing: no STK prompt, no charge, no order to
// reconcile. Both rules come from the same published catalogue the app was shown
// (offer_rules()), so the app and the server can never disagree about them.
$rules = offer_rules($pdo, $offerId);

// 1. Time-of-day window. Outside it Safaricom will not deliver the bundle, so
//    taking the money would create a refund, not a sale.
if (!offer_window_open($rules)) {
    $window = offer_window_label($rules);
    json_out([
        'status'          => 'PAYMENT_FAILED',
        'errorCode'       => 'OFFER_NOT_AVAILABLE_NOW',
        'customerMessage' => $window === ''
            ? 'This offer is not on sale right now.'
            : "This offer is only sold between {$window}.",
    ], 409);
}

// 2. Once-per-recipient-per-day (and max-per-day). The number that receives the
//    bundle is the one that is limited — buying the same bundle for a DIFFERENT
//    number is always allowed. Resets at Nairobi midnight by itself.
$allowance = offer_daily_allowance($rules);
if ($allowance !== null) {
    $limitedNumber = $recipient !== '' ? $recipient : $payer;
    $alreadyToday = recipient_purchases_today($pdo, $offerId, $limitedNumber);
    if ($alreadyToday >= $allowance) {
        json_out([
            'status'          => 'PAYMENT_FAILED',
            'errorCode'       => 'ALREADY_BOUGHT_TODAY',
            'customerMessage' => $allowance === 1
                ? 'This bundle can only be bought once a day for each number. '
                    . 'It can be bought for this number again after midnight, or for a different number now.'
                : "This bundle can only be bought {$allowance} times a day for each number.",
        ], 409);
    }
}

$route = $forSelf ? 'self' : 'another';

// --- Atomic idempotency ----------------------------------------------------
// Claim the client_request_id by INSERTing the row (status PAYMENT_REQUESTED)
// BEFORE calling Daraja. Two concurrent identical requests race on the UNIQUE
// key: the first insert wins and fires exactly one STK; the loser's insert
// throws, and we return the existing row's status — no second STK, no 500.
try {
    $ins = $pdo->prepare(
        'INSERT INTO payments
            (client_request_id, offer_id, amount, payer, recipient, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())'
    );
    $ins->execute([$clientId, $offerId, $amount, $payer, $recipient, 'PAYMENT_REQUESTED']);
    $paymentId = (int) $pdo->lastInsertId();
} catch (PDOException $e) {
    // Almost certainly the UNIQUE(client_request_id) violation → idempotent replay.
    $existing = $pdo->prepare('SELECT * FROM payments WHERE client_request_id = ? LIMIT 1');
    $existing->execute([$clientId]);
    if ($row = $existing->fetch()) {
        json_out([
            'status'          => $row['status'],
            'orderReference'  => $row['checkout_request_id'],
            'customerMessage' => 'Request already in progress',
        ]);
    }
    // Some other write failure → fail cleanly, never surface a raw 500 exception.
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'DB_WRITE_FAILED'], 500);
}

// We now own a fresh row; only WE will call Daraja for this clientRequestId.
$token = daraja_token($config);
if ($token === null) {
    $pdo->prepare('UPDATE payments SET status = ?, result_desc = ?, updated_at = NOW() WHERE id = ?')
        ->execute(['PAYMENT_FAILED', 'Daraja token request failed', $paymentId]);
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'TOKEN_FAILED'], 502);
}

// AccountReference shown on the M-Pesa/Paybill statement.
//  - Buy-for-myself → a fixed brand tag "SkylinkBingwa" so every self-purchase to the
//    Paybill lands under one clear account.
//  - Buy-for-another → the recipient's number (CLAUDE.md §7), so the owner can see
//    which line the bundle is for. (Another-number is mocked in the app for now.)
$account = $forSelf ? 'SkylinkBingwa' : ($recipient !== '' ? $recipient : $payer);
$resp = daraja_stk_push($config, $token, $amount, $payer, $account, $route);

if (is_array($resp) && (string) ($resp['ResponseCode'] ?? '') === '0') {
    $checkoutId = (string) $resp['CheckoutRequestID'];
    $pdo->prepare('UPDATE payments SET checkout_request_id = ?, updated_at = NOW() WHERE id = ?')
        ->execute([$checkoutId, $paymentId]);

    json_out([
        'status'          => 'PAYMENT_REQUESTED',
        'orderReference'  => $checkoutId,
        'customerMessage' => (string) ($resp['CustomerMessage'] ?? 'Check your phone'),
    ]);
}

// Daraja rejected the request up front → mark the row failed and return cleanly.
$errorCode = (string) ($resp['errorCode'] ?? $resp['ResponseCode'] ?? 'STK_REJECTED');
$pdo->prepare('UPDATE payments SET status = ?, result_desc = ?, updated_at = NOW() WHERE id = ?')
    ->execute(['PAYMENT_FAILED', substr('STK rejected: ' . $errorCode, 0, 191), $paymentId]);
json_out([
    'status'    => 'PAYMENT_FAILED',
    'errorCode' => $errorCode,
], 502);
