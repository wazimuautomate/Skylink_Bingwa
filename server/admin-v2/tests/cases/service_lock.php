<?php
/**
 * Service-lock pure-logic cases.
 *
 * Only the parts that are decidable from their arguments are covered here: the message
 * the operator's settings produce. Reading and writing the switch itself needs the
 * database, which this harness deliberately does not have.
 *
 * Required by tests/run.php, which already defined test(), ok() and eq().
 */

use App\Services\ServiceLock;

$lockState = static function (int $amount, string $reason): array {
    return [
        'enabled' => true,
        'amount'  => $amount,
        'reason'  => $reason !== '' ? $reason : ServiceLock::DEFAULT_REASON,
        'since'   => '2026-09-10 08:00:00',
        'by'      => 'Super Admin',
    ];
};

test('message names the outstanding amount when one is set', function () use ($lockState) {
    $msg = ServiceLock::message($lockState(250000, 'Unpaid development work.'));
    eq($msg, 'Unpaid development work. Outstanding balance: KSh 250,000.');
});

test('message omits the amount entirely when it is zero', function () use ($lockState) {
    $msg = ServiceLock::message($lockState(0, 'Unpaid development work.'));
    eq($msg, 'Unpaid development work.');
    ok(strpos($msg, 'KSh') === false, 'a zero amount must not be quoted');
});

test('an empty reason falls back to the default wording', function () use ($lockState) {
    $msg = ServiceLock::message($lockState(0, ''));
    eq($msg, ServiceLock::DEFAULT_REASON);
});

test('the refusal title is exactly "Request Denied"', function () {
    eq(ServiceLock::TITLE, 'Request Denied');
});
