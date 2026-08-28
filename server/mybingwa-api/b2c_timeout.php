<?php
/**
 * POST b2c_timeout.php — Daraja's QueueTimeOutURL for B2C payouts.
 *
 * Safaricom calls this when a request sat in their queue too long to be
 * processed in the normal flow.
 *
 * CRITICAL: a queue timeout is NOT a statement that the money did not move. It
 * says only that Safaricom could not complete the request through the usual
 * path in the usual time. The payout may still settle afterwards.
 *
 * So this endpoint deliberately does exactly one thing: park the withdrawal in
 * UNKNOWN and leave it there for the reconciler to resolve with a
 * TransactionStatus query. It NEVER releases the hold, and it never marks the
 * row FAILED — refunding a payout that later completes pays the customer twice
 * out of the business's float, with no way to claw it back.
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

ref_log($pdo, 'B2C_TIMEOUT', null, null, [
    'originator' => $originatorId,
    'desc'       => $parsed['result_desc'],
]);

if ($originatorId !== '') {
    $withdrawalId = null;

    $stmt = $pdo->prepare('SELECT id FROM ' . ref_t('withdrawals') . ' WHERE originator_conversation_id = ? LIMIT 1');
    $stmt->execute([$originatorId]);
    if ($row = $stmt->fetch()) {
        $withdrawalId = (int) $row['id'];
    } else {
        $withdrawalId = ref_withdrawal_id_from_originator($originatorId);
    }

    if ($withdrawalId !== null) {
        // Only from a still-in-flight state, and only ever INTO UNKNOWN. A row
        // that already reached PAID or FAILED has an authoritative answer and
        // must not be dragged back into ambiguity by a late timeout notice.
        $pdo->prepare(
            'UPDATE ' . ref_t('withdrawals') . '
                SET status = ?, result_desc = ?, updated_at = ?
              WHERE id = ? AND status IN (?, ?, ?)'
        )->execute([
            'UNKNOWN',
            mb_substr('Queue timeout — awaiting transaction status: ' . $parsed['result_desc'], 0, 191),
            ref_now(),
            $withdrawalId,
            'REQUESTED', 'SUBMITTING', 'SUBMITTED',
        ]);
    }
}

json_out(['ResultCode' => 0, 'ResultDesc' => 'Accepted']);
