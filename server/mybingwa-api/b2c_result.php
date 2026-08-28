<?php
/**
 * POST b2c_result.php — Daraja's ResultURL for B2C payouts AND for the
 * TransactionStatus queries the reconciler fires at stuck withdrawals.
 *
 * This is the ONLY place a withdrawal is allowed to become PAID or FAILED.
 * Nothing else may conclude that a payout succeeded: not the accept response
 * from the payment request, not a timeout, not elapsed time.
 *
 * Authenticated the same way callback.php is — by Safaricom source IP, failing
 * closed — because Daraja cannot send a custom header. An unauthenticated
 * request is acked so Safaricom stops retrying, but applies NO database change.
 *
 * TWO RESULT SHAPES ARRIVE HERE:
 *
 *   SKB-<id>-...   the payout itself. ResultCode 0 means paid; anything else
 *                  means the money did not move, so the hold is released.
 *
 *   SKBQ-<id>-...  the answer to a TransactionStatus query. Here ResultCode 0
 *                  only means "the query worked" — the payment's real fate is in
 *                  ResultParameters. Reading the query's own ResultCode as the
 *                  payment's outcome would mark every stuck payout as paid.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';
require_once __DIR__ . '/b2c.php';

$rawInput = file_get_contents('php://input');

if (!callback_authenticated($config)) {
    callback_ack_ignore();
}

$payload = json_decode($rawInput, true) ?: [];
$parsed = b2c_parse_result($payload);
$originatorId = $parsed['originator_id'];

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);

ref_log($pdo, 'B2C_RESULT', null, null, [
    'originator' => $originatorId,
    'code'       => $parsed['result_code'],
    'desc'       => $parsed['result_desc'],
]);

if ($originatorId === '') {
    json_out(['ResultCode' => 0, 'ResultDesc' => 'Accepted']);
}

// Resolve the withdrawal: by the stored originator id first, then by parsing the
// id we encoded into the string (which is how a TransactionStatus reply, whose
// originator id belongs to the QUERY rather than the payout, finds its row).
$stmt = $pdo->prepare('SELECT * FROM ' . ref_t('withdrawals') . ' WHERE originator_conversation_id = ? LIMIT 1');
$stmt->execute([$originatorId]);
$withdrawal = $stmt->fetch();

if (!$withdrawal) {
    $guessId = ref_withdrawal_id_from_originator($originatorId);
    if ($guessId !== null) {
        $byId = $pdo->prepare('SELECT * FROM ' . ref_t('withdrawals') . ' WHERE id = ? LIMIT 1');
        $byId->execute([$guessId]);
        $withdrawal = $byId->fetch();
    }
}

if (!$withdrawal) {
    // Nothing of ours. Ack so Safaricom stops retrying; change nothing.
    json_out(['ResultCode' => 0, 'ResultDesc' => 'Accepted']);
}

$isQuery = strpos($originatorId, 'SKBQ-') === 0;
$resultCode = $parsed['result_code'];
$receipt = $parsed['receipt'];

if (!$isQuery) {
    // --- The payout's own result ------------------------------------------
    if ($resultCode !== null && (string) $resultCode === '0') {
        ref_withdrawal_settle(
            $pdo,
            $withdrawal,
            $receipt !== '' ? $receipt : ('B2C-' . $withdrawal['id']),
            $parsed['transaction_id'] !== '' ? $parsed['transaction_id'] : null,
            $parsed['result_desc']
        );
    } else {
        // A non-zero code from the payout result is Safaricom stating the payment
        // did not happen. This is the safe, authoritative refund path.
        ref_withdrawal_refund(
            $pdo,
            $withdrawal,
            (string) ($resultCode ?? 'UNKNOWN'),
            $parsed['result_desc']
        );
    }
} else {
    // --- A TransactionStatus answer ---------------------------------------
    // The payment's fate lives in the parameters. Anything we cannot read
    // confidently leaves the row UNKNOWN for the next sweep or for a human —
    // guessing here is how a system double-pays or wrongly refuses a customer.
    $params = $parsed['params'];
    $txStatus = strtolower(trim((string) (
        $params['TransactionStatus'] ?? $params['TransactionStatusDescription'] ?? ''
    )));
    $queryReceipt = (string) ($params['ReceiptNo'] ?? $params['TransactionReceipt'] ?? $receipt);

    if ($txStatus === 'completed' || $txStatus === 'success' || $queryReceipt !== '') {
        ref_withdrawal_settle(
            $pdo,
            $withdrawal,
            $queryReceipt !== '' ? $queryReceipt : ('B2C-' . $withdrawal['id']),
            $parsed['transaction_id'] !== '' ? $parsed['transaction_id'] : null,
            'Resolved by transaction status query'
        );
    } elseif (in_array($txStatus, ['failed', 'cancelled', 'canceled', 'reversed', 'expired'], true)) {
        ref_withdrawal_refund(
            $pdo,
            $withdrawal,
            (string) ($resultCode ?? 'QUERY'),
            'Transaction status: ' . $txStatus
        );
    } else {
        $pdo->prepare(
            'UPDATE ' . ref_t('withdrawals') . '
                SET result_desc = ?, updated_at = ? WHERE id = ? AND status = ?'
        )->execute([
            mb_substr('Status query inconclusive: ' . $parsed['result_desc'], 0, 191),
            ref_now(),
            (int) $withdrawal['id'],
            'UNKNOWN',
        ]);
    }
}

// Notify the customer of the final outcome. Queued, never sent inline: this
// handler must return fast so Safaricom does not retry it.
$fresh = $pdo->prepare(
    'SELECT w.*, c.msisdn AS cust_msisdn, c.fcm_token
       FROM ' . ref_t('withdrawals') . ' w
       JOIN ' . ref_t('referrers') . ' r ON r.id = w.referrer_id
       JOIN ' . ref_t('customers') . ' c ON c.id = r.customer_id
      WHERE w.id = ? LIMIT 1'
);
$fresh->execute([(int) $withdrawal['id']]);
if ($final = $fresh->fetch()) {
    $amount = number_format(((int) $final['amount_cents']) / 100, 2);
    if ($final['status'] === 'PAID') {
        ref_outbox_queue(
            $pdo, 'PUSH', (string) $final['fcm_token'],
            'Ksh ' . $amount . ' sent to M-Pesa',
            'Your commission withdrawal is complete. M-Pesa receipt ' . $final['mpesa_receipt'] . '.',
            'withdrawal_paid', 'push:paid:' . $final['id']
        );
        ref_outbox_queue(
            $pdo, 'SMS', (string) $final['msisdn'], '',
            'Skylink Bingwa: Ksh ' . $amount . ' has been sent to your M-Pesa. Receipt '
                . $final['mpesa_receipt'] . '. Thank you for referring your friends.',
            'withdrawal_paid', 'sms:paid:' . $final['id']
        );
    } elseif ($final['status'] === 'FAILED') {
        // The message must say the money is back, or the customer assumes it was lost.
        ref_outbox_queue(
            $pdo, 'PUSH', (string) $final['fcm_token'],
            'Withdrawal did not go through',
            'Ksh ' . $amount . ' could not be sent and is back in your commission balance. You can try again.',
            'withdrawal_failed', 'push:failed:' . $final['id']
        );
        ref_outbox_queue(
            $pdo, 'SMS', (string) $final['msisdn'], '',
            'Skylink Bingwa: your withdrawal of Ksh ' . $amount . ' did not go through. '
                . 'The money is back in your commission balance and you can try again from the app.',
            'withdrawal_failed', 'sms:failed:' . $final['id']
        );
    }
}

json_out(['ResultCode' => 0, 'ResultDesc' => 'Accepted']);
