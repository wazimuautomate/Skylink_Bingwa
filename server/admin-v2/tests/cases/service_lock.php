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

/* ---- what the blocked message must and must not say ---- */

test('the default reason states the fact and the consequence, never a cause', function () {
    $reason = ServiceLock::DEFAULT_REASON;
    // The client reads this text. Why the service was suspended is a conversation to
    // have with them directly — it must never be published on a 503 page.
    foreach (['developer', 'invoice', 'unpaid', 'unsettled', 'owe', 'debt', ' by '] as $forbidden) {
        ok(stripos($reason, $forbidden) === false, "the default reason must not mention '{$forbidden}'");
    }
    eq($reason,
        'This service has been suspended. The app and the server remain unavailable '
        . 'until the suspension is lifted.');
});

test('the danger zone is not a grantable page', function () {
    // A partner Admin can only ever be given a key from SettingsController::PAGES.
    // 'danger' must never appear there, or the switch becomes delegable by mistake.
    ok(!array_key_exists('danger', \App\Controllers\SettingsController::PAGES), 'danger must not be grantable');
    ok(!in_array('danger', array_keys(\App\Controllers\SettingsController::PAGES), true));
});
