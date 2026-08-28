<?php
/**
 * Reads and writes for the referral & commission system.
 *
 * The write surface here is deliberately tiny. There is exactly ONE way money
 * moves in this system — an append-only row in mb_commission_ledger — and that
 * holds for the admin too: `adjust()` writes a ledger entry, it does not UPDATE a
 * balance. Nothing in this class, and nothing in the controller above it, may
 * ever write mb_referrers.balance_cents except as the cached mirror of a ledger
 * insert made in the same transaction.
 *
 * @see docs/REFERRAL_COMMISSION_SPEC.md
 */

namespace App\Repositories;

use App\Core\Database;

final class ReferralRepository
{
    /* ------------------------------------------------------------- dashboard */

    /**
     * Headline figures.
     *
     * `liability_cents` is the one to watch: it is money the business owes to
     * referrers but has not yet paid. It grows quietly with every sale and is
     * invisible unless something puts it on a screen.
     */
    public static function summary(): array
    {
        $r = Database::table('referrers');
        $l = Database::table('commission_ledger');
        $w = Database::table('withdrawals');
        $rf = Database::table('referrals');

        $totals = Database::fetch(
            "SELECT
                COUNT(*)                                  AS referrers,
                COALESCE(SUM(balance_cents), 0)           AS liability_cents,
                COALESCE(SUM(lifetime_earned_cents), 0)   AS earned_cents,
                COALESCE(SUM(lifetime_paid_cents), 0)     AS paid_cents,
                SUM(status <> 'ACTIVE')                   AS restricted
             FROM {$r}"
        ) ?: [];

        $referrals = Database::fetch(
            "SELECT COUNT(*) AS total, SUM(first_purchase_at IS NOT NULL) AS converted FROM {$rf}"
        ) ?: [];

        // Anything not yet resolved is money that may still leave the account.
        $inflight = Database::fetch(
            "SELECT COUNT(*) AS c, COALESCE(SUM(amount_cents), 0) AS cents
               FROM {$w} WHERE status IN ('REQUESTED','SUBMITTING','SUBMITTED','UNKNOWN')"
        ) ?: [];

        // UNKNOWN older than an hour is the alert that matters most: a payout
        // whose fate nobody knows.
        $stuck = Database::fetch(
            "SELECT COUNT(*) AS c FROM {$w}
              WHERE status = 'UNKNOWN' AND requested_at <= (UTC_TIMESTAMP() - INTERVAL 1 HOUR)"
        ) ?: [];

        $unmatured = Database::fetch(
            "SELECT COALESCE(SUM(amount_cents), 0) AS cents FROM {$l} WHERE matures_at > UTC_TIMESTAMP()"
        ) ?: [];

        return [
            'referrers'        => (int) ($totals['referrers'] ?? 0),
            'restricted'       => (int) ($totals['restricted'] ?? 0),
            'liability_cents'  => (int) ($totals['liability_cents'] ?? 0),
            'earned_cents'     => (int) ($totals['earned_cents'] ?? 0),
            'paid_cents'       => (int) ($totals['paid_cents'] ?? 0),
            'referrals_total'  => (int) ($referrals['total'] ?? 0),
            'referrals_conv'   => (int) ($referrals['converted'] ?? 0),
            'inflight_count'   => (int) ($inflight['c'] ?? 0),
            'inflight_cents'   => (int) ($inflight['cents'] ?? 0),
            'stuck_count'      => (int) ($stuck['c'] ?? 0),
            'unmatured_cents'  => (int) ($unmatured['cents'] ?? 0),
        ];
    }

    /* ------------------------------------------------------------- referrers */

    public static function search(array $filters, int $page, int $perPage): array
    {
        $r = Database::table('referrers');
        $c = Database::table('customers');

        $where = ['1=1'];
        $args = [];

        if (($filters['q'] ?? '') !== '') {
            $where[] = '(c.name LIKE ? OR c.msisdn LIKE ? OR r.code LIKE ?)';
            $like = '%' . $filters['q'] . '%';
            $args[] = $like;
            $args[] = '%' . preg_replace('/\D/', '', $filters['q']) . '%';
            $args[] = '%' . strtoupper($filters['q']) . '%';
        }
        if (($filters['status'] ?? '') !== '') {
            $where[] = 'r.status = ?';
            $args[] = $filters['status'];
        }
        if (!empty($filters['withdrawable'])) {
            $where[] = 'r.balance_cents > 0';
        }

        $sql = implode(' AND ', $where);
        $total = (int) (Database::fetch(
            "SELECT COUNT(*) AS c FROM {$r} r JOIN {$c} c ON c.id = r.customer_id WHERE {$sql}",
            $args
        )['c'] ?? 0);

        $offset = ($page - 1) * $perPage;
        $rows = Database::fetchAll(
            "SELECT r.*, c.name, c.msisdn, c.fcm_token
               FROM {$r} r JOIN {$c} c ON c.id = r.customer_id
              WHERE {$sql}
              ORDER BY r.balance_cents DESC, r.lifetime_earned_cents DESC
              LIMIT {$perPage} OFFSET {$offset}",
            $args
        );

        return ['rows' => $rows, 'total' => $total];
    }

    public static function find(int $id): ?array
    {
        $r = Database::table('referrers');
        $c = Database::table('customers');
        return Database::fetch(
            "SELECT r.*, c.name, c.msisdn, c.device_hash
               FROM {$r} r JOIN {$c} c ON c.id = r.customer_id WHERE r.id = ? LIMIT 1",
            [$id]
        ) ?: null;
    }

    /** Ledger truth for one referrer, alongside the cached figure, so drift is visible. */
    public static function balances(int $referrerId): array
    {
        $l = Database::table('commission_ledger');
        $rf = Database::table('referrals');

        $total = (int) (Database::fetch(
            "SELECT COALESCE(SUM(amount_cents), 0) AS t FROM {$l} WHERE referrer_id = ?",
            [$referrerId]
        )['t'] ?? 0);

        $available = (int) (Database::fetch(
            "SELECT COALESCE(SUM(l.amount_cents), 0) AS a
               FROM {$l} l LEFT JOIN {$rf} rf ON rf.id = l.referral_id
              WHERE l.referrer_id = ?
                AND l.matures_at <= UTC_TIMESTAMP()
                AND (l.entry_type <> 'SIGNUP_BONUS' OR rf.first_purchase_at IS NOT NULL)",
            [$referrerId]
        )['a'] ?? 0);

        return ['total' => $total, 'available' => max(0, $available), 'pending' => max(0, $total - $available)];
    }

    public static function ledger(int $referrerId, int $limit = 200): array
    {
        $l = Database::table('commission_ledger');
        return Database::fetchAll(
            "SELECT * FROM {$l} WHERE referrer_id = ? ORDER BY id DESC LIMIT {$limit}",
            [$referrerId]
        );
    }

    public static function referees(int $customerId): array
    {
        $rf = Database::table('referrals');
        $c = Database::table('customers');
        return Database::fetchAll(
            "SELECT rf.*, c.name, c.msisdn
               FROM {$rf} rf JOIN {$c} c ON c.id = rf.referred_customer_id
              WHERE rf.referrer_customer_id = ?
              ORDER BY rf.attributed_at DESC",
            [$customerId]
        );
    }

    public static function setStatus(int $referrerId, string $status, string $reason): void
    {
        Database::run(
            'UPDATE ' . Database::table('referrers') . '
                SET status = ?, status_reason = ?, updated_at = UTC_TIMESTAMP() WHERE id = ?',
            [$status, mb_substr($reason, 0, 191), $referrerId]
        );
    }

    /**
     * Manual balance adjustment — a LEDGER ENTRY, never a balance edit.
     *
     * $amountCents is signed: positive credits the referrer, negative debits them.
     * The idempotency key carries the actor and a timestamp so an accidental
     * double-submit of the same form does not post the adjustment twice.
     */
    public static function adjust(int $referrerId, int $amountCents, string $note, string $actor): bool
    {
        $l = Database::table('commission_ledger');
        $r = Database::table('referrers');
        $key = 'adjust:' . $referrerId . ':' . $actor . ':' . gmdate('YmdHi') . ':' . $amountCents;

        try {
            Database::run(
                "INSERT INTO {$l}
                    (referrer_id, entry_type, amount_cents, matures_at, source_type, source_id,
                     referral_id, idempotency_key, note, created_by, created_at)
                 VALUES (?, 'ADJUST', ?, UTC_TIMESTAMP(), 'admin', NULL, NULL, ?, ?, ?, UTC_TIMESTAMP())",
                [$referrerId, $amountCents, $key, mb_substr($note, 0, 191), $actor]
            );
        } catch (\Throwable $e) {
            return false;   // Duplicate key — the same adjustment was already posted.
        }

        Database::run(
            "UPDATE {$r} SET balance_cents = balance_cents + ?, updated_at = UTC_TIMESTAMP() WHERE id = ?",
            [$amountCents, $referrerId]
        );
        return true;
    }

    /* ----------------------------------------------------------- withdrawals */

    public static function withdrawals(array $filters, int $page, int $perPage): array
    {
        $w = Database::table('withdrawals');
        $r = Database::table('referrers');
        $c = Database::table('customers');

        $where = ['1=1'];
        $args = [];
        if (($filters['status'] ?? '') !== '') {
            $where[] = 'w.status = ?';
            $args[] = $filters['status'];
        }
        if (($filters['q'] ?? '') !== '') {
            $where[] = '(c.name LIKE ? OR w.msisdn LIKE ? OR w.mpesa_receipt LIKE ?)';
            $like = '%' . $filters['q'] . '%';
            $args[] = $like;
            $args[] = $like;
            $args[] = $like;
        }
        $sql = implode(' AND ', $where);

        $total = (int) (Database::fetch(
            "SELECT COUNT(*) AS c FROM {$w} w
               JOIN {$r} r ON r.id = w.referrer_id JOIN {$c} c ON c.id = r.customer_id
              WHERE {$sql}",
            $args
        )['c'] ?? 0);

        $offset = ($page - 1) * $perPage;
        $rows = Database::fetchAll(
            "SELECT w.*, c.name, r.code
               FROM {$w} w
               JOIN {$r} r ON r.id = w.referrer_id JOIN {$c} c ON c.id = r.customer_id
              WHERE {$sql}
              -- UNKNOWN first: an unresolved payout is the most urgent row on the page.
              ORDER BY FIELD(w.status, 'UNKNOWN','SUBMITTING','SUBMITTED','REQUESTED','FAILED','PAID'), w.id DESC
              LIMIT {$perPage} OFFSET {$offset}",
            $args
        );

        return ['rows' => $rows, 'total' => $total];
    }

    public static function findWithdrawal(int $id): ?array
    {
        $w = Database::table('withdrawals');
        $r = Database::table('referrers');
        $c = Database::table('customers');
        return Database::fetch(
            "SELECT w.*, c.name, c.msisdn AS customer_msisdn, r.code
               FROM {$w} w
               JOIN {$r} r ON r.id = w.referrer_id JOIN {$c} c ON c.id = r.customer_id
              WHERE w.id = ? LIMIT 1",
            [$id]
        ) ?: null;
    }

    /**
     * Close out a withdrawal a human has investigated.
     *
     * PAID settles the hold; FAILED releases it. This exists for the case the
     * automation cannot resolve on its own — Safaricom's records say one thing,
     * the row says another — and it is audited like every other money action.
     * It deliberately refuses to touch a row that is already final.
     */
    public static function resolveWithdrawal(int $id, string $outcome, string $note, string $actor): bool
    {
        $w = Database::table('withdrawals');
        $l = Database::table('commission_ledger');
        $r = Database::table('referrers');

        $row = self::findWithdrawal($id);
        if (!$row || in_array($row['status'], ['PAID', 'FAILED', 'CANCELLED'], true)) {
            return false;
        }

        if ($outcome === 'PAID') {
            Database::run(
                "UPDATE {$w} SET status = 'PAID', admin_note = ?, resolved_at = UTC_TIMESTAMP(),
                        updated_at = UTC_TIMESTAMP() WHERE id = ? AND status NOT IN ('PAID','FAILED','CANCELLED')",
                [mb_substr($note, 0, 191), $id]
            );
            // Zero-amount settle: the hold already removed the money.
            Database::run(
                "INSERT IGNORE INTO {$l}
                    (referrer_id, entry_type, amount_cents, matures_at, source_type, source_id,
                     referral_id, idempotency_key, note, created_by, created_at)
                 VALUES (?, 'WITHDRAW_SETTLE', 0, UTC_TIMESTAMP(), 'withdrawal', ?, NULL, ?, ?, ?, UTC_TIMESTAMP())",
                [(int) $row['referrer_id'], $id, 'settle:withdrawal:' . $id, mb_substr($note, 0, 191), $actor]
            );
            Database::run(
                "UPDATE {$r} SET lifetime_paid_cents = lifetime_paid_cents + ?, updated_at = UTC_TIMESTAMP()
                  WHERE id = ?",
                [(int) $row['amount_cents'], (int) $row['referrer_id']]
            );
            return true;
        }

        Database::run(
            "UPDATE {$w} SET status = 'FAILED', admin_note = ?, resolved_at = UTC_TIMESTAMP(),
                    updated_at = UTC_TIMESTAMP() WHERE id = ? AND status NOT IN ('PAID','FAILED','CANCELLED')",
            [mb_substr($note, 0, 191), $id]
        );
        // Release the hold so the money is spendable again.
        Database::run(
            "INSERT IGNORE INTO {$l}
                (referrer_id, entry_type, amount_cents, matures_at, source_type, source_id,
                 referral_id, idempotency_key, note, created_by, created_at)
             VALUES (?, 'WITHDRAW_REFUND', ?, UTC_TIMESTAMP(), 'withdrawal', ?, NULL, ?, ?, ?, UTC_TIMESTAMP())",
            [
                (int) $row['referrer_id'], (int) $row['amount_cents'], $id,
                'refund:withdrawal:' . $id, mb_substr($note, 0, 191), $actor,
            ]
        );
        Database::run(
            "UPDATE {$r} SET balance_cents = balance_cents + ?, updated_at = UTC_TIMESTAMP() WHERE id = ?",
            [(int) $row['amount_cents'], (int) $row['referrer_id']]
        );
        return true;
    }

    /* ---------------------------------------------------------------- health */

    /** Referrers whose cached balance disagrees with their ledger. Should always be empty. */
    public static function drift(): array
    {
        $r = Database::table('referrers');
        $l = Database::table('commission_ledger');
        return Database::fetchAll(
            "SELECT r.id, r.code, r.balance_cents, COALESCE(SUM(l.amount_cents), 0) AS ledger_total
               FROM {$r} r LEFT JOIN {$l} l ON l.referrer_id = r.id
              GROUP BY r.id, r.code, r.balance_cents
             HAVING r.balance_cents <> ledger_total"
        );
    }

    /** Handsets the anti-farming rules have blocked, newest first. */
    public static function blockedDevices(int $limit = 100): array
    {
        $d = Database::table('device_registry');
        return Database::fetchAll(
            "SELECT * FROM {$d} WHERE blocked = 1 OR msisdn_count > 2 ORDER BY msisdn_count DESC, id DESC LIMIT {$limit}"
        );
    }

    public static function recentEvents(int $limit = 100): array
    {
        $e = Database::table('commission_events');
        return Database::fetchAll("SELECT * FROM {$e} ORDER BY id DESC LIMIT {$limit}");
    }

    public static function outboxHealth(): array
    {
        $o = Database::table('outbox');
        return Database::fetch(
            "SELECT
                SUM(status = 'PENDING') AS pending,
                SUM(status = 'DEAD')    AS dead,
                SUM(status = 'SENT')    AS sent
             FROM {$o}"
        ) ?: ['pending' => 0, 'dead' => 0, 'sent' => 0];
    }
}
