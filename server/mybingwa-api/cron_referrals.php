<?php
/**
 * cron_referrals.php — every scheduled job the referral system needs.
 *
 * Shared cPanel hosting has no long-running workers, so this is a short script
 * run repeatedly by cron. Invoke a single task with ?task=NAME (or `php
 * cron_referrals.php NAME` from the CLI); with no task it runs the ones that are
 * safe to run every minute.
 *
 *   outbox      every 1 min   deliver queued SMS and push with backoff
 *   submit      every 1 min   REQUESTED withdrawals -> Daraja B2C
 *   reconcile   every 5 min   resolve SUBMITTED/UNKNOWN via TransactionStatus
 *   float       every 15 min  utility-account balance check
 *   velocity    hourly        flag farming patterns
 *   integrity   nightly       assert cached balances match the ledger
 *
 * EVERY TASK TAKES A NAMED LOCK FIRST. Two overlapping runs of `submit` would be
 * two processes deciding what to pay out at the same instant — precisely the race
 * the rest of this system is built to avoid. A run that cannot get the lock exits
 * immediately and silently; the next tick picks the work up.
 *
 * Over HTTP the script requires ?key=<cron_key>. From the CLI it runs unguarded,
 * because reaching the CLI already means shell access.
 */

@set_time_limit(300);
ignore_user_abort(true);

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';
require_once __DIR__ . '/referrals.php';
require_once __DIR__ . '/b2c.php';
require_once __DIR__ . '/fcm.php';

$isCli = PHP_SAPI === 'cli';

if (!$isCli) {
    $key = (string) ($_GET['key'] ?? '');
    $expected = (string) ($config['cron_key'] ?? '');
    if ($expected === '' || strpos($expected, 'PUT_') === 0 || !hash_equals($expected, $key)) {
        http_response_code(403);
        echo "forbidden\n";
        exit;
    }
    header('Content-Type: text/plain; charset=utf-8');
}

$task = $isCli
    ? (string) ($argv[1] ?? 'all')
    : (string) ($_GET['task'] ?? 'all');

$pdo = require __DIR__ . '/db.php';
ref_provision($pdo);
$settings = ref_settings($pdo);

// Safaricom expires the B2C initiator password periodically. Whoever last saved
// one on the Referrals admin page wins over whatever (if anything) is in
// config.php, so a reset never needs a code change or a redeploy.
if (($dbPassword = b2c_password_from_db($pdo)) !== null) {
    $config['b2c_initiator_password'] = $dbPassword;
}

$out = static function (string $line): void {
    echo $line . "\n";
};

/**
 * Run $fn while holding a MySQL named lock. Returns false if another run holds it.
 * GET_LOCK is released automatically if the connection dies, so a crashed run
 * never wedges the schedule.
 */
function ref_with_lock(PDO $pdo, string $name, callable $fn)
{
    $stmt = $pdo->prepare('SELECT GET_LOCK(?, 0) AS got');
    $stmt->execute(['skylink_' . $name]);
    if ((int) ($stmt->fetch()['got'] ?? 0) !== 1) {
        return false;
    }
    try {
        return $fn();
    } finally {
        $rel = $pdo->prepare('SELECT RELEASE_LOCK(?)');
        $rel->execute(['skylink_' . $name]);
    }
}

/* ========================================================================== */
/* outbox — queued SMS and push                                               */
/* ========================================================================== */

function task_outbox(PDO $pdo, array $config, callable $out): void
{
    // 1m, 5m, 30m, 2h, 6h. After the fifth failure the row is DEAD and a human
    // decides; retrying forever would hammer a provider that has already refused.
    $backoff = [60, 300, 1800, 7200, 21600];
    $maxAttempts = count($backoff);

    $rows = $pdo->prepare(
        'SELECT * FROM ' . ref_t('outbox') . '
          WHERE status = ? AND next_attempt_at <= ?
          ORDER BY id ASC LIMIT 100'
    );
    $rows->execute(['PENDING', ref_now()]);
    $pending = $rows->fetchAll();

    $sent = 0;
    $failed = 0;
    foreach ($pending as $row) {
        $ok = false;
        $reason = '';

        if ($row['channel'] === 'SMS') {
            $ok = ref_send_sms($config, (string) $row['target'], (string) $row['body']);
            $reason = $ok ? 'OK' : 'SMS_SEND_FAILED';
        } elseif ($row['channel'] === 'PUSH') {
            [$ok, $reason] = fcm_send(
                $config,
                (string) $row['target'],
                (string) $row['title'],
                (string) $row['body'],
                'referrals'
            );
        } else {
            $reason = 'UNKNOWN_CHANNEL';
        }

        $attempts = (int) $row['attempts'] + 1;

        if ($ok) {
            $pdo->prepare(
                'UPDATE ' . ref_t('outbox') . '
                    SET status = ?, attempts = ?, sent_at = ?, last_error = ? WHERE id = ?'
            )->execute(['SENT', $attempts, ref_now(), '', (int) $row['id']]);
            $sent++;
            continue;
        }

        // A push to a token FCM says is dead will never succeed. Retrying it just
        // burns the schedule, so it goes straight to DEAD.
        $permanent = in_array($reason, ['NO_TOKEN', 'UNREGISTERED', 'INVALID_ARGUMENT', 'UNKNOWN_CHANNEL'], true);

        if ($permanent || $attempts >= $maxAttempts) {
            $pdo->prepare(
                'UPDATE ' . ref_t('outbox') . ' SET status = ?, attempts = ?, last_error = ? WHERE id = ?'
            )->execute(['DEAD', $attempts, mb_substr($reason, 0, 191), (int) $row['id']]);
        } else {
            $pdo->prepare(
                'UPDATE ' . ref_t('outbox') . '
                    SET attempts = ?, last_error = ?, next_attempt_at = ? WHERE id = ?'
            )->execute([
                $attempts,
                mb_substr($reason, 0, 191),
                gmdate('Y-m-d H:i:s', time() + $backoff[$attempts - 1]),
                (int) $row['id'],
            ]);
        }
        $failed++;
    }

    $out('outbox: ' . count($pending) . ' due, ' . $sent . ' sent, ' . $failed . ' deferred/dead');
}

/* ========================================================================== */
/* submit — REQUESTED withdrawals to Daraja                                   */
/* ========================================================================== */

function task_submit(PDO $pdo, array $config, array $settings, callable $out): void
{
    if ((int) $settings['referral_payouts_enabled'] !== 1) {
        $out('submit: payouts disabled (kill switch) — nothing submitted');
        return;
    }
    if (!b2c_configured($config)) {
        $out('submit: B2C not configured — nothing submitted');
        return;
    }

    $rows = $pdo->prepare(
        'SELECT * FROM ' . ref_t('withdrawals') . ' WHERE status = ? ORDER BY id ASC LIMIT 20'
    );
    $rows->execute(['REQUESTED']);
    $queue = $rows->fetchAll();
    if (!$queue) {
        $out('submit: nothing queued');
        return;
    }

    $token = daraja_token($config);
    if ($token === null) {
        $out('submit: Daraja token failed — leaving queue untouched');
        return;
    }

    $submitted = 0;
    foreach ($queue as $row) {
        $id = (int) $row['id'];

        // Claim the row and persist the originator id BEFORE any network call.
        // If this process dies immediately after the commit, the row is left in
        // SUBMITTING with an id we can query — never re-submitted, never lost.
        $originatorId = b2c_new_originator_id($id);

        $pdo->beginTransaction();
        $claim = $pdo->prepare(
            'UPDATE ' . ref_t('withdrawals') . '
                SET status = ?, originator_conversation_id = ?, submit_attempts = submit_attempts + 1,
                    submitted_at = ?, updated_at = ?
              WHERE id = ? AND status = ?'
        );
        $claim->execute(['SUBMITTING', $originatorId, ref_now(), ref_now(), $id, 'REQUESTED']);
        if ($claim->rowCount() !== 1) {
            $pdo->rollBack();
            continue;   // Someone else claimed it.
        }
        $pdo->commit();

        $amountKsh = intdiv((int) $row['amount_cents'], 100);
        $result = b2c_payment_request(
            $config,
            $token,
            $originatorId,
            $amountKsh,
            (string) $row['msisdn'],
            'Skylink Bingwa referral commission'
        );

        if ($result['outcome'] === 'ACCEPTED') {
            $pdo->prepare(
                'UPDATE ' . ref_t('withdrawals') . '
                    SET status = ?, conversation_id = ?, updated_at = ? WHERE id = ? AND status = ?'
            )->execute(['SUBMITTED', $result['conversation_id'] ?? '', ref_now(), $id, 'SUBMITTING']);
            $submitted++;
            ref_log($pdo, 'WITHDRAWAL_SUBMITTED', (int) $row['referrer_id'], $id, [
                'originator' => $originatorId,
                'amount_ksh' => $amountKsh,
            ]);
        } elseif ($result['outcome'] === 'REJECTED') {
            // Safaricom refused it up front, so nothing was queued and no money
            // moved. This is the one outcome where releasing the hold is safe.
            $fresh = $pdo->prepare('SELECT * FROM ' . ref_t('withdrawals') . ' WHERE id = ? LIMIT 1');
            $fresh->execute([$id]);
            $w = $fresh->fetch();
            if ($w) {
                ref_withdrawal_refund($pdo, $w, (string) ($result['error'] ?? 'REJECTED'), (string) ($result['desc'] ?? 'Rejected by M-Pesa'));
            }
        } else {
            // INCONCLUSIVE. The request may have reached Safaricom. Park it and
            // let the reconciler find out. Never refund, never re-send.
            $pdo->prepare(
                'UPDATE ' . ref_t('withdrawals') . '
                    SET status = ?, result_desc = ?, updated_at = ? WHERE id = ? AND status = ?'
            )->execute([
                'UNKNOWN',
                mb_substr('Submit inconclusive: ' . ($result['error'] ?? ''), 0, 191),
                ref_now(),
                $id,
                'SUBMITTING',
            ]);
            ref_log($pdo, 'WITHDRAWAL_INCONCLUSIVE', (int) $row['referrer_id'], $id, [
                'originator' => $originatorId,
                'error'      => $result['error'] ?? '',
            ]);
        }
    }

    $out('submit: ' . count($queue) . ' claimed, ' . $submitted . ' accepted by Daraja');
}

/* ========================================================================== */
/* reconcile — resolve anything still in flight                               */
/* ========================================================================== */

function task_reconcile(PDO $pdo, array $config, callable $out): void
{
    if (!b2c_configured($config)) {
        $out('reconcile: B2C not configured');
        return;
    }

    // Five minutes is long enough for a healthy ResultURL callback to have
    // arrived, and short enough that a customer is not left wondering.
    $cutoff = gmdate('Y-m-d H:i:s', time() - 300);

    // SUBMITTING is included deliberately: a row in that state means a process
    // died mid-submit, and the ONLY safe move is to ask Safaricom what happened.
    $rows = $pdo->prepare(
        'SELECT * FROM ' . ref_t('withdrawals') . '
          WHERE status IN (?, ?, ?) AND updated_at <= ?
          ORDER BY id ASC LIMIT 25'
    );
    $rows->execute(['SUBMITTED', 'UNKNOWN', 'SUBMITTING', $cutoff]);
    $stuck = $rows->fetchAll();
    if (!$stuck) {
        $out('reconcile: nothing stuck');
        return;
    }

    $token = daraja_token($config);
    if ($token === null) {
        $out('reconcile: Daraja token failed');
        return;
    }

    $queried = 0;
    $escalated = 0;
    foreach ($stuck as $row) {
        $id = (int) $row['id'];

        // Move it out of SUBMITTED/SUBMITTING into the honest UNKNOWN state, so
        // the admin queue and the app both stop implying it is progressing.
        $pdo->prepare(
            'UPDATE ' . ref_t('withdrawals') . '
                SET status = ?, updated_at = ? WHERE id = ? AND status IN (?, ?)'
        )->execute(['UNKNOWN', ref_now(), $id, 'SUBMITTED', 'SUBMITTING']);

        // The query carries its OWN originator id, encoding the withdrawal id so
        // the answer (which arrives at b2c_result.php, not here) can find the row.
        $queryId = 'SKBQ-' . $id . '-' . bin2hex(random_bytes(6));
        b2c_transaction_status($config, $token, $queryId, (string) ($row['transaction_id'] ?? ''));
        $queried++;

        // Unresolved for over an hour is no longer a timing problem. Flag it for
        // a human rather than letting it sit silently.
        if (strtotime((string) $row['requested_at']) < time() - 3600) {
            $escalated++;
            ref_log($pdo, 'WITHDRAWAL_ESCALATED', (int) $row['referrer_id'], $id, [
                'age_minutes' => intdiv(time() - strtotime((string) $row['requested_at']), 60),
            ]);
        }
    }

    $out('reconcile: ' . $queried . ' queried, ' . $escalated . ' over an hour old');
}

/* ========================================================================== */
/* float — is there money to pay out with?                                    */
/* ========================================================================== */

function task_float(PDO $pdo, array $config, array $settings, callable $out): void
{
    if (!b2c_configured($config)) {
        $out('float: B2C not configured');
        return;
    }
    $token = daraja_token($config);
    if ($token === null) {
        $out('float: Daraja token failed');
        return;
    }
    // The balance itself arrives asynchronously at the ResultURL; this only asks.
    $res = b2c_account_balance($config, $token);
    ref_log($pdo, 'FLOAT_CHECK', null, null, ['outcome' => $res['outcome']]);
    $out('float: query ' . $res['outcome']);
}

/* ========================================================================== */
/* velocity — farming patterns                                                */
/* ========================================================================== */

function task_velocity(PDO $pdo, array $settings, callable $out): void
{
    $dayStart = ref_nairobi_day_start_utc();
    $flagged = 0;

    // 1. Too much earned in one day by one referrer.
    $stmt = $pdo->prepare(
        'SELECT referrer_id, SUM(amount_cents) AS earned
           FROM ' . ref_t('commission_ledger') . '
          WHERE entry_type IN (?, ?) AND created_at >= ?
          GROUP BY referrer_id
         HAVING earned > ?'
    );
    $stmt->execute(['EARN', 'SIGNUP_BONUS', $dayStart, (int) $settings['referral_max_daily_earn_cents']]);
    foreach ($stmt->fetchAll() as $row) {
        $pdo->prepare(
            'UPDATE ' . ref_t('referrers') . '
                SET status = ?, status_reason = ?, risk_score = risk_score + 30, updated_at = ?
              WHERE id = ? AND status = ?'
        )->execute([
            'PAYOUT_BLOCKED', 'Daily earning velocity exceeded', ref_now(),
            (int) $row['referrer_id'], 'ACTIVE',
        ]);
        $flagged++;
    }

    // 2. One handset behind several referees. A genuine phone shared in a
    //    household reaches two or three; a farm keeps climbing.
    $dev = $pdo->prepare(
        'SELECT device_hash, COUNT(*) AS c FROM ' . ref_t('referrals') . '
          WHERE device_hash IS NOT NULL
          GROUP BY device_hash HAVING c >= 3'
    );
    $dev->execute();
    foreach ($dev->fetchAll() as $row) {
        $pdo->prepare(
            'UPDATE ' . ref_t('device_registry') . '
                SET blocked = 1, block_reason = ? WHERE device_hash = ? AND blocked = 0'
        )->execute(['Multiple referrals from one device', (string) $row['device_hash']]);
        $flagged++;
    }

    $out('velocity: ' . $flagged . ' accounts/devices flagged');
}

/* ========================================================================== */
/* integrity — the cached balance must equal the ledger                       */
/* ========================================================================== */

function task_integrity(PDO $pdo, callable $out): void
{
    $stmt = $pdo->query(
        'SELECT r.id, r.balance_cents, COALESCE(SUM(l.amount_cents), 0) AS ledger_total
           FROM ' . ref_t('referrers') . ' r
           LEFT JOIN ' . ref_t('commission_ledger') . ' l ON l.referrer_id = r.id
          GROUP BY r.id, r.balance_cents
         HAVING r.balance_cents <> ledger_total'
    );
    $drift = $stmt->fetchAll();

    foreach ($drift as $row) {
        // Log loudly, but DO NOT silently "fix" the cache. Drift means something
        // wrote outside the ledger discipline, and quietly papering over it
        // destroys the evidence needed to find out what.
        ref_log($pdo, 'LEDGER_DRIFT', (int) $row['id'], null, [
            'cached' => (int) $row['balance_cents'],
            'ledger' => (int) $row['ledger_total'],
        ]);
        error_log('[referrals] LEDGER DRIFT referrer=' . $row['id']
            . ' cached=' . $row['balance_cents'] . ' ledger=' . $row['ledger_total']);
    }

    // OTP codes are hashed and single-use, so an old row carries no live risk — but
    // "no reason to keep it" is still a reason to not keep it. A full day past
    // expiry is well clear of anything a support conversation would still need.
    $purged = $pdo->exec(
        'DELETE FROM ' . ref_t('otp_challenges') . ' WHERE expires_at < ' . $pdo->quote(gmdate('Y-m-d H:i:s', time() - 86400))
    );

    $out('integrity: ' . count($drift) . ' referrers with drift'
        . (count($drift) ? ' — INVESTIGATE, cache NOT auto-corrected' : '')
        . '; ' . (int) $purged . ' expired OTP code(s) purged');
}

/* ========================================================================== */

$ran = [];
foreach (['outbox', 'submit', 'reconcile', 'float', 'velocity', 'integrity'] as $name) {
    $wanted = ($task === 'all' && in_array($name, ['outbox', 'submit'], true)) || $task === $name;
    if (!$wanted) {
        continue;
    }

    $result = ref_with_lock($pdo, $name, static function () use ($name, $pdo, $config, $settings, $out) {
        switch ($name) {
            case 'outbox':    task_outbox($pdo, $config, $out); break;
            case 'submit':    task_submit($pdo, $config, $settings, $out); break;
            case 'reconcile': task_reconcile($pdo, $config, $out); break;
            case 'float':     task_float($pdo, $config, $settings, $out); break;
            case 'velocity':  task_velocity($pdo, $settings, $out); break;
            case 'integrity': task_integrity($pdo, $out); break;
        }
        return true;
    });

    if ($result === false) {
        $out($name . ': skipped, another run holds the lock');
    }
    $ran[] = $name;
}

if (!$ran) {
    $out('unknown task "' . $task . '". Valid: outbox submit reconcile float velocity integrity all');
}
