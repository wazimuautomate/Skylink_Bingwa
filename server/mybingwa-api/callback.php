<?php
/**
 * POST callback.php — Daraja posts the STK result here.
 *
 * Daraja cannot send a custom header AND strips the query string from the
 * CallbackURL, so the webhook is authenticated by SOURCE IP (Safaricom's callback
 * block, plus an optional explicit allowlist) — with a shared-secret token accepted
 * too if it ever survives in the path/query. See lib.php `callback_authenticated`.
 * A request that fails authentication is acked with ResultCode 0 (so Safaricom stops
 * retrying) but applies NO database change.
 *
 * We also cross-check the callback Amount against the row's server-recomputed
 * amount; a mismatch is flagged for manual review instead of being confirmed. We
 * never trust the callback's amount to overwrite the recomputed price.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';

$rawInput = file_get_contents('php://input');

// --- Authenticity gate. Accept a surviving token OR a Safaricom callback IP. Fail
// closed otherwise: ack-and-ignore, never touch the DB. The amount cross-check below
// is the final guard against a bad confirmation.
if (!callback_authenticated($config)) {
    callback_ack_ignore();
}

$payload = json_decode($rawInput, true) ?: [];
$cb = $payload['Body']['stkCallback'] ?? null;

if ($cb && isset($cb['CheckoutRequestID'])) {
    $checkoutId = (string) $cb['CheckoutRequestID'];
    $resultCode = $cb['ResultCode'] ?? null;
    $resultDesc = (string) ($cb['ResultDesc'] ?? '');
    $status     = map_result_code($resultCode);

    // Pull the receipt + paid amount from the metadata on success. The paid
    // amount is used ONLY to cross-check, never to overwrite the stored price.
    $receipt    = null;
    $paidAmount = null;
    if ($status === 'PAYMENT_CONFIRMED' && !empty($cb['CallbackMetadata']['Item'])) {
        foreach ($cb['CallbackMetadata']['Item'] as $item) {
            $name = $item['Name'] ?? '';
            if ($name === 'MpesaReceiptNumber') {
                $receipt = (string) ($item['Value'] ?? '');
            } elseif ($name === 'Amount') {
                $paidAmount = $item['Value'] ?? null;
            }
        }
    }

    $pdo = require __DIR__ . '/db.php';

    // Load the known row so we can validate the amount before confirming anything.
    $sel = $pdo->prepare('SELECT * FROM payments WHERE checkout_request_id = ? LIMIT 1');
    $sel->execute([$checkoutId]);
    $row = $sel->fetch();

    if ($row) {
        $amountMismatch = $status === 'PAYMENT_CONFIRMED'
            && $paidAmount !== null
            && (int) round((float) $paidAmount) !== (int) $row['amount'];

        if ($amountMismatch) {
            // Paid amount does not match the recomputed price → DO NOT confirm.
            // Leave status as-is (stays PAYMENT_REQUESTED) and flag for review.
            $note = 'FLAGGED amount mismatch: paid ' . (int) round((float) $paidAmount)
                  . ' expected ' . (int) $row['amount'] . '. ' . $resultDesc;
            $pdo->prepare(
                'UPDATE payments
                    SET result_code = ?, result_desc = ?, updated_at = NOW()
                  WHERE id = ? AND status = ?'
            )->execute([
                (string) $resultCode,
                substr($note, 0, 191),
                $row['id'],
                'PAYMENT_REQUESTED',
            ]);
        } else {
            // Normal path. We never write `amount`, so the recomputed price stands.
            // The `status <> CONFIRMED` guard makes this atomic + idempotent: only the
            // callback that actually flips REQUESTED→CONFIRMED gets rowCount 1, so
            // Daraja's duplicate callbacks never trigger a second fulfilment SMS.
            $upd = $pdo->prepare(
                'UPDATE payments
                    SET status = ?, mpesa_receipt = ?, result_code = ?, result_desc = ?, updated_at = NOW()
                  WHERE checkout_request_id = ? AND status <> ?'
            );
            $upd->execute([
                $status,
                $receipt,
                (string) $resultCode,
                $resultDesc,
                $checkoutId,
                'PAYMENT_CONFIRMED',
            ]);
            $weConfirmed = $upd->rowCount() === 1;

            // Referral commission. Hooked to $weConfirmed on purpose: that flag is
            // the ONE observation of the REQUESTED → CONFIRMED transition, so the
            // accrual inherits the exactly-once guarantee the payment row already
            // provides. The ledger's own idempotency key is the second, independent
            // guard against a replayed callback. Best-effort by contract — it never
            // throws and never delays the ack below, because a slow ack invites
            // Safaricom to retry and pile pressure on this exact code path.
            if ($weConfirmed && $status === 'PAYMENT_CONFIRMED') {
                ref_accrue_for_payment($pdo, $row);
            }

            // Buy-for-another fulfilment signal: on the FIRST confirmation only, and
            // only when payer != recipient, send the mocked M-Pesa SMS naming the
            // recipient to the fulfilment phone. Best-effort; never blocks the 200 ack.
            $recipient = (string) ($row['recipient'] ?? '');
            $payer     = (string) ($row['payer'] ?? '');
            if ($weConfirmed
                && $status === 'PAYMENT_CONFIRMED'
                && $receipt !== null && $receipt !== ''
                && $recipient !== '' && $recipient !== $payer) {
                send_mocked_mpesa_sms($config, $receipt, (int) $row['amount'], $recipient);
            }
        }
    }
    // Unknown checkoutId → no row to update; we still ack below.
}

// Always acknowledge so Daraja does not keep retrying.
json_out(['ResultCode' => 0, 'ResultDesc' => 'Accepted']);
