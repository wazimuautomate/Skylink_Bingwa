<?php
/**
 * Data access for offers (the mb_offers working table) plus immutable per-save
 * revisions. Optimistic locking via row_version prevents two admins silently
 * overwriting each other.
 */

namespace App\Repositories;

use App\Core\Auth;
use App\Core\Database;
use App\Services\PublishingService;

final class OfferRepository
{
    public const CATEGORIES = ['DATA', 'SMS', 'MINUTES', 'SPECIAL'];
    public const BANDS = ['Hourly', 'Daily', 'Weekly', 'Monthly'];
    public const RULES = [
        'MULTIPLE_PER_DAY'            => 'Multiple per day',
        'ONCE_PER_RECIPIENT_PER_DAY' => 'Once per recipient per day',
        'MAX_PER_RECIPIENT_PER_DAY'  => 'Max per recipient per day',
    ];

    public static function find(string $offerId): ?array
    {
        return Database::fetch('SELECT * FROM ' . Database::table('offers') . ' WHERE offer_id = ? LIMIT 1', [$offerId]);
    }

    public static function exists(string $offerId): bool
    {
        return self::find($offerId) !== null;
    }

    /**
     * Generate the next stable offer id for a category, e.g. DATA -> "data_14".
     * The admin never types an id; it is derived from the category and the highest
     * existing number, and guaranteed unique.
     */
    public static function nextOfferId(string $category): string
    {
        $prefix = strtolower(trim($category));
        if ($prefix === '') {
            $prefix = 'offer';
        }
        $rows = Database::fetchAll(
            'SELECT offer_id FROM ' . Database::table('offers') . ' WHERE offer_id LIKE ?',
            [$prefix . '\_%']
        );
        $max = 0;
        foreach ($rows as $r) {
            if (preg_match('/^' . preg_quote($prefix, '/') . '_(\d+)$/', (string) $r['offer_id'], $m)) {
                $max = max($max, (int) $m[1]);
            }
        }
        $next = $max + 1;
        while (self::exists($prefix . '_' . $next)) {
            $next++;
        }
        return $prefix . '_' . $next;
    }

    /** @return array filtered offer rows */
    public static function search(array $f): array
    {
        $clauses = [];
        $params = [];
        if (!empty($f['q'])) {
            $clauses[] = '(offer_id LIKE ? OR name LIKE ?)';
            $params[] = '%' . $f['q'] . '%'; $params[] = '%' . $f['q'] . '%';
        }
        if (!empty($f['category'])) { $clauses[] = 'category = ?'; $params[] = $f['category']; }
        if (!empty($f['status']))   { $clauses[] = 'status = ?';   $params[] = $f['status']; }
        if (!empty($f['rule']))     { $clauses[] = 'daily_rule = ?'; $params[] = $f['rule']; }
        if (!empty($f['band']))     { $clauses[] = 'band = ?'; $params[] = $f['band']; }
        if (isset($f['min']) && $f['min'] !== '') { $clauses[] = 'price >= ?'; $params[] = (int) $f['min']; }
        if (isset($f['max']) && $f['max'] !== '') { $clauses[] = 'price <= ?'; $params[] = (int) $f['max']; }
        $where = $clauses ? ('WHERE ' . implode(' AND ', $clauses)) : '';
        return Database::fetchAll(
            'SELECT * FROM ' . Database::table('offers') . " {$where} ORDER BY sort_hint, category, price",
            $params
        );
    }

    /**
     * Insert or update. On update, enforces optimistic locking against $expectedVersion.
     * @return array{ok:bool, conflict:bool}
     */
    public static function save(array $data, bool $isNew, ?int $expectedVersion = null): array
    {
        $t = Database::table('offers');
        $actor = Auth::user()['name'] ?? 'system';
        if ($isNew) {
            Database::run(
                "INSERT INTO {$t}
                    (offer_id, category, name, price, validity, band, daily_rule, max_per_day,
                     available_from, available_to,
                     commercial_tag, offline_eligible, restrictions, status, starts_at, ends_at, sort_hint,
                     commission_bps, margin_bps,
                     row_version, created_at, updated_at, updated_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), ?)",
                [
                    $data['offer_id'], $data['category'], $data['name'], (int) $data['price'], $data['validity'],
                    $data['band'], $data['daily_rule'], $data['max_per_day'],
                    $data['available_from'] ?? null, $data['available_to'] ?? null,
                    $data['commercial_tag'],
                    $data['offline_eligible'], $data['restrictions'], $data['status'], $data['starts_at'],
                    $data['ends_at'], (int) $data['sort_hint'],
                    $data['commission_bps'] ?? null, $data['margin_bps'] ?? null, $actor,
                ]
            );
            self::writeRevision($data['offer_id'], 'create');
            return ['ok' => true, 'conflict' => false];
        }

        // Optimistic lock: only update if row_version still matches.
        $stmt = Database::run(
            "UPDATE {$t} SET
                category=?, name=?, price=?, validity=?, band=?, daily_rule=?, max_per_day=?,
                available_from=?, available_to=?,
                commercial_tag=?, offline_eligible=?, restrictions=?, status=?, starts_at=?, ends_at=?, sort_hint=?,
                commission_bps=?, margin_bps=?,
                row_version = row_version + 1, updated_at = UTC_TIMESTAMP(), updated_by = ?
             WHERE offer_id = ? AND row_version = ?",
            [
                $data['category'], $data['name'], (int) $data['price'], $data['validity'], $data['band'],
                $data['daily_rule'], $data['max_per_day'],
                $data['available_from'] ?? null, $data['available_to'] ?? null,
                $data['commercial_tag'], $data['offline_eligible'],
                $data['restrictions'], $data['status'], $data['starts_at'], $data['ends_at'], (int) $data['sort_hint'],
                $data['commission_bps'] ?? null, $data['margin_bps'] ?? null,
                $actor, $data['offer_id'], $expectedVersion,
            ]
        );
        if ($stmt->rowCount() === 0) {
            return ['ok' => false, 'conflict' => true];
        }
        self::writeRevision($data['offer_id'], 'update');
        return ['ok' => true, 'conflict' => false];
    }

    public static function setStatus(string $offerId, string $status, string $action): void
    {
        Database::run(
            'UPDATE ' . Database::table('offers') . ' SET status = ?, row_version = row_version + 1, updated_at = UTC_TIMESTAMP(), updated_by = ? WHERE offer_id = ?',
            [$status, Auth::user()['name'] ?? 'system', $offerId]
        );
        self::writeRevision($offerId, $action);
    }

    public static function delete(string $offerId): void
    {
        self::writeRevision($offerId, 'delete');
        Database::run('DELETE FROM ' . Database::table('offers') . ' WHERE offer_id = ?', [$offerId]);
    }

    /** Is this offer referenced by any payment (so it must be archived, not deleted)? */
    public static function referencedByPayments(string $offerId): bool
    {
        if (!PaymentRepository::available()) {
            return false;
        }
        return (int) (Database::scalar('SELECT COUNT(*) FROM payments WHERE offer_id = ?', [$offerId]) ?? 0) > 0;
    }

    private static function writeRevision(string $offerId, string $action): void
    {
        $row = self::find($offerId);
        Database::run(
            'INSERT INTO ' . Database::table('offer_revisions') . ' (offer_id, snapshot_json, action, actor_name, created_at)
             VALUES (?, ?, ?, ?, UTC_TIMESTAMP())',
            [$offerId, json_encode($row), $action, Auth::user()['name'] ?? 'system']
        );
    }

    /** offer_id => published serialized form, to flag unpublished working changes. */
    public static function publishedById(): array
    {
        $snap = PublishingService::currentSnapshot();
        $out = [];
        foreach (($snap['offers'] ?? []) as $o) {
            $out[$o['id']] = $o;
        }
        return $out;
    }

    /** True if the working offer differs from what is currently published (or is new). */
    public static function hasUnpublishedChange(array $offerRow, array $publishedById): bool
    {
        if ($offerRow['status'] !== 'active') {
            // A non-active offer differs from published only if it was previously published.
            return isset($publishedById[$offerRow['offer_id']]);
        }
        if (!isset($publishedById[$offerRow['offer_id']])) {
            return true;
        }
        $pub = $publishedById[$offerRow['offer_id']];
        return (int) $pub['price'] !== (int) $offerRow['price']
            || $pub['name'] !== $offerRow['name']
            || $pub['category'] !== $offerRow['category']
            || $pub['validity'] !== $offerRow['validity']
            || ($pub['policy'] ?? '') !== $offerRow['daily_rule']
            || (string) ($pub['availableFrom'] ?? '') !== self::hhmm($offerRow['available_from'] ?? null)
            || (string) ($pub['availableTo'] ?? '') !== self::hhmm($offerRow['available_to'] ?? null)
            || (bool) $pub['offlineEligible'] !== ((int) $offerRow['offline_eligible'] === 1);
    }

    /**
     * A stored TIME ("17:00:00", "17:00") as the "HH:MM" the app and the snapshot
     * use, or '' when there is no window on that end. One helper so the form, the
     * snapshot and the change detector can never disagree about the format.
     */
    public static function hhmm($time): string
    {
        $text = trim((string) ($time ?? ''));
        if ($text === '') {
            return '';
        }
        $parts = explode(':', $text);
        $h = isset($parts[0]) ? (int) $parts[0] : -1;
        $m = isset($parts[1]) ? (int) $parts[1] : 0;
        if ($h < 0 || $h > 24 || $m < 0 || $m > 59) {
            return '';
        }
        return sprintf('%02d:%02d', $h, $m);
    }
}
