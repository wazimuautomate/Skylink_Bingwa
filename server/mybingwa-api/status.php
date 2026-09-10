<?php
/**
 * GET status.php?clientRequestId=..&orderReference=.. — poll the payment result.
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON):
 *   { status, orderReference, mpesaReceipt, customerMessage, errorCode }
 *
 * If the callback has not arrived yet, we fall back to Daraja's STK query so the
 * result is still confirmed even when the callback is delayed or unreachable.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';

require_app_key($config);
// Global service lock (Admin V2 -> Danger zone). Refuses with 503 "Request Denied".
deny_if_service_locked($config);

$clientId   = (string) ($_GET['clientRequestId'] ?? '');
$orderRef   = (string) ($_GET['orderReference'] ?? '');
if ($clientId === '' && $orderRef === '') {
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'BAD_REQUEST'], 400);
}

$pdo = require __DIR__ . '/db.php';
$sel = $pdo->prepare(
    'SELECT * FROM payments WHERE client_request_id = ? OR checkout_request_id = ? LIMIT 1'
);
$sel->execute([$clientId, $orderRef]);
$row = $sel->fetch();

if (!$row) {
    json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'NOT_FOUND'], 404);
}

$status  = $row['status'];
$receipt = $row['mpesa_receipt'];

// Still waiting and it has been a few seconds → ask Daraja directly (fallback).
$ageSeconds = time() - strtotime($row['updated_at']);
if ($status === 'PAYMENT_REQUESTED' && $ageSeconds >= 3) {
    $token = daraja_token($config);
    if ($token !== null) {
        $q = daraja_stk_query($config, $token, $row['checkout_request_id']);
        if (is_array($q) && isset($q['ResultCode'])) {
            $status = map_result_code($q['ResultCode']);
            if ($status !== 'PAYMENT_REQUESTED') {
                $pdo->prepare(
                    'UPDATE payments SET status = ?, result_code = ?, updated_at = NOW()
                      WHERE id = ?'
                )->execute([$status, (string) $q['ResultCode'], $row['id']]);
            }
        }
        // If the query says "still processing", $status stays PAYMENT_REQUESTED.
    }
}

json_out([
    'status'         => $status,
    'orderReference' => $row['checkout_request_id'],
    'mpesaReceipt'   => $receipt,
]);
