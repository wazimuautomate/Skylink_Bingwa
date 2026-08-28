<?php
/**
 * Bulk import of billboards and notifications from JSON — pasted or uploaded.
 *
 * Why this exists: building a season's adverts or a set of message wordings one
 * web form at a time is slow, and the owner already drafts them elsewhere. This
 * takes the whole batch in one go.
 *
 * Two rules make it safe to hand a file to a live catalogue:
 *
 *  1. **Everything lands as a DRAFT.** An import can never put an advert or a
 *     message in front of a customer by itself. The operator opens each one, edits
 *     it in the normal form, then publishes — the same gate as anything else.
 *  2. **The whole file is validated before ANY row is written.** One malformed
 *     entry rejects the batch and reports which entry and why, rather than leaving
 *     half an import behind for someone to find later.
 *
 * Unknown keys are ignored rather than rejected, so a file exported from a newer
 * version, or carrying the operator's own notes, still imports.
 */

namespace App\Services;

use App\Core\Auth;
use App\Core\Database;
use Throwable;

final class JsonImporter
{
    /** Refuse absurd files outright — this is an admin form, not a data pipeline. */
    public const MAX_ITEMS = 200;
    public const MAX_BYTES = 1048576; // 1 MB

    /**
     * Decode the payload and return a plain list of item arrays.
     *
     * Accepts three shapes, because all three are things a person reasonably
     * pastes: a bare array of items, a single item object, or a wrapper object
     * with a `billboards` / `notifications` / `items` key.
     *
     * @return array{ok:bool, items:array, error:string}
     */
    public static function decode(string $raw, string $collectionKey): array
    {
        $raw = trim($raw);
        if ($raw === '') {
            return ['ok' => false, 'items' => [], 'error' => 'Paste some JSON, or choose a file to upload.'];
        }
        if (strlen($raw) > self::MAX_BYTES) {
            return ['ok' => false, 'items' => [], 'error' => 'That file is too large (limit 1 MB).'];
        }
        // Strip a UTF-8 BOM, which a file saved from Notepad or Excel will carry and
        // which json_decode rejects with an unhelpful syntax error.
        $raw = preg_replace('/^\xEF\xBB\xBF/', '', $raw);

        $decoded = json_decode($raw, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            return ['ok' => false, 'items' => [], 'error' => 'That is not valid JSON: ' . json_last_error_msg() . '.'];
        }
        if (!is_array($decoded)) {
            return ['ok' => false, 'items' => [], 'error' => 'Expected a JSON object or a list of them.'];
        }

        if (isset($decoded[$collectionKey]) && is_array($decoded[$collectionKey])) {
            $items = $decoded[$collectionKey];
        } elseif (isset($decoded['items']) && is_array($decoded['items'])) {
            $items = $decoded['items'];
        } elseif (array_keys($decoded) === range(0, count($decoded) - 1)) {
            $items = $decoded; // a bare list
        } else {
            $items = [$decoded];  // a single object
        }

        $items = array_values(array_filter($items, 'is_array'));
        if ($items === []) {
            return ['ok' => false, 'items' => [], 'error' => 'The file contains no entries.'];
        }
        if (count($items) > self::MAX_ITEMS) {
            return ['ok' => false, 'items' => [], 'error' => 'That is more than ' . self::MAX_ITEMS . ' entries in one import.'];
        }
        return ['ok' => true, 'items' => $items, 'error' => ''];
    }

    /**
     * Read the pasted textarea or the uploaded file, whichever the operator used.
     * The file wins when both are present, because choosing a file is the more
     * deliberate action.
     *
     * @return array{ok:bool, raw:string, error:string}
     */
    public static function readPayload(?array $file, string $pasted): array
    {
        if ($file !== null && ($file['error'] ?? UPLOAD_ERR_NO_FILE) !== UPLOAD_ERR_NO_FILE) {
            if (($file['error'] ?? 1) !== UPLOAD_ERR_OK) {
                return ['ok' => false, 'raw' => '', 'error' => 'The upload did not complete. Try again.'];
            }
            if ((int) ($file['size'] ?? 0) > self::MAX_BYTES) {
                return ['ok' => false, 'raw' => '', 'error' => 'That file is too large (limit 1 MB).'];
            }
            $contents = @file_get_contents($file['tmp_name'] ?? '');
            if ($contents === false) {
                return ['ok' => false, 'raw' => '', 'error' => 'The uploaded file could not be read.'];
            }
            return ['ok' => true, 'raw' => $contents, 'error' => ''];
        }
        return ['ok' => true, 'raw' => $pasted, 'error' => ''];
    }

    /* ==================================================================== billboards */

    /** The sample the import page shows, and what `?sample=1` downloads. */
    public static function billboardSample(): array
    {
        return [
            'billboards' => [
                [
                    'name' => 'Evening data push',
                    'kind' => 'simple',
                    'linkedOfferId' => 'data_6',
                    'tag' => 'Popular',
                    'headline' => '{{allowance}} for {{price}}',
                    'body' => 'Valid {{validity}}. Tap to buy in seconds.',
                    'ctaLabel' => 'Buy now',
                    'targetAction' => 'offer',
                    'priority' => 3,
                    'displayOrder' => 1,
                    'enabled' => true,
                ],
                [
                    'name' => 'Weekend announcement',
                    'kind' => 'advanced',
                    'headline' => 'Weekend bundles are here',
                    'body' => 'Browse the offers made for the weekend.',
                    'ctaLabel' => 'See offers',
                    'targetAction' => 'category',
                    'targetCategory' => 'DATA',
                    'altText' => 'Weekend data offers',
                    'startsAt' => '2026-08-09 06:00',
                    'endsAt' => '2026-08-11 23:59',
                    'priority' => 5,
                    'displayOrder' => 2,
                    'frequencyCap' => 0,
                    'enabled' => true,
                ],
            ],
        ];
    }

    /**
     * Validate a whole billboard batch. Returns the rows ready to insert, or the
     * per-entry problems — never a partial result.
     *
     * @return array{ok:bool, rows:array, errors:string[]}
     */
    public static function validateBillboards(array $items, array $categoryKeys): array
    {
        $rows = [];
        $errors = [];
        $actions = BillboardService::TARGET_ACTIONS;

        foreach ($items as $i => $item) {
            $label = 'Entry ' . ($i + 1);
            $name = self::str($item, ['name', 'title']);
            if ($name === '') {
                $errors[] = "{$label}: needs a \"name\".";
                continue;
            }
            $label .= ' (' . $name . ')';

            $kind = strtolower(self::str($item, ['kind', 'type'])) === 'advanced' ? 'advanced' : 'simple';
            $targetAction = strtolower(self::str($item, ['targetAction', 'target_action']));
            if (!in_array($targetAction, $actions, true)) {
                $targetAction = 'none';
            }
            $linkedOffer = self::str($item, ['linkedOfferId', 'linked_offer_id', 'offerId']);
            $headline = self::str($item, ['headline']);
            $body = self::str($item, ['body', 'message']);
            $targetCategory = strtoupper(self::str($item, ['targetCategory', 'target_category', 'category']));

            if ($kind === 'simple') {
                if ($linkedOffer === '') {
                    $errors[] = "{$label}: a \"simple\" billboard needs \"linkedOfferId\".";
                    continue;
                }
                $offer = Database::fetch(
                    'SELECT status FROM ' . Database::table('offers') . ' WHERE offer_id = ?',
                    [$linkedOffer]
                );
                if (!$offer) {
                    $errors[] = "{$label}: offer \"{$linkedOffer}\" does not exist.";
                    continue;
                }
                foreach (['tag' => self::str($item, ['tag']), 'headline' => $headline, 'body' => $body] as $field => $value) {
                    $bad = BillboardService::unsupportedTokens($value);
                    if ($bad) {
                        $errors[] = "{$label}: unsupported token(s) in \"{$field}\": " . implode(', ', $bad) . '.';
                        continue 2;
                    }
                }
            } else {
                if ($headline === '') {
                    $errors[] = "{$label}: an \"advanced\" billboard needs a \"headline\".";
                    continue;
                }
                if (BillboardService::hasToken($headline) || BillboardService::hasToken($body)) {
                    $errors[] = "{$label}: an \"advanced\" billboard must not contain {{tokens}}.";
                    continue;
                }
            }

            if ($targetCategory !== '' && $categoryKeys !== [] && !in_array($targetCategory, $categoryKeys, true)) {
                $errors[] = "{$label}: unknown category \"{$targetCategory}\".";
                continue;
            }
            if ($targetAction === 'url') {
                $url = self::str($item, ['clickUrl', 'click_url', 'url']);
                if (!preg_match('#^https?://#i', $url)) {
                    $errors[] = "{$label}: \"clickUrl\" must be an http(s) address.";
                    continue;
                }
            }

            $startsAt = self::toUtc(self::str($item, ['startsAt', 'starts_at']));
            $endsAt = self::toUtc(self::str($item, ['endsAt', 'ends_at']));
            if ($kind === 'simple') {
                // Simple billboards are always-on, exactly as the form enforces, so an
                // accidental window can never hide one from the app.
                $startsAt = null;
                $endsAt = null;
            } elseif ($startsAt !== null && $endsAt !== null && $endsAt <= $startsAt) {
                $errors[] = "{$label}: \"endsAt\" must come after \"startsAt\".";
                continue;
            }

            $rows[] = [
                'name' => mb_substr($name, 0, 120),
                'kind' => $kind,
                // ALWAYS a draft: an import never puts an advert in front of a customer.
                'status' => 'draft',
                'priority' => self::int($item, ['priority'], 5),
                'display_order' => max(0, self::int($item, ['displayOrder', 'display_order'], 0)),
                'linked_offer_id' => $linkedOffer !== '' ? $linkedOffer : null,
                'tag' => mb_substr(self::str($item, ['tag']), 0, 40),
                'headline' => mb_substr($headline, 0, 160),
                'body' => mb_substr($body, 0, 255),
                'cta_label' => mb_substr(self::str($item, ['ctaLabel', 'cta_label']) ?: 'Buy now', 0, 40),
                'cta_destination' => $kind === 'simple' ? '' : mb_substr(self::str($item, ['ctaDestination', 'cta_destination']), 0, 120),
                'alt_text' => $kind === 'simple' ? '' : mb_substr(self::str($item, ['altText', 'alt_text']), 0, 160),
                'audience_rule' => mb_substr(self::str($item, ['audienceRule', 'audience_rule']) ?: 'all', 0, 40),
                'frequency_cap' => max(0, self::int($item, ['frequencyCap', 'frequency_cap'], 0)),
                'starts_at' => $startsAt,
                'ends_at' => $endsAt,
                'target_action' => $targetAction,
                'click_url' => $targetAction === 'url' ? mb_substr(self::str($item, ['clickUrl', 'click_url', 'url']), 0, 255) : '',
                'internal_action' => $targetAction === 'internal' ? mb_substr(self::str($item, ['internalAction', 'internal_action']), 0, 60) : '',
                'target_category' => $targetAction === 'category' ? $targetCategory : '',
                'enabled' => self::bool($item, ['enabled'], true) ? 1 : 0,
            ];
        }

        return ['ok' => $errors === [], 'rows' => $rows, 'errors' => $errors];
    }

    /** Insert a validated billboard batch as drafts. Returns how many were written. */
    public static function insertBillboards(array $rows): int
    {
        $table = Database::table('billboards');
        $actor = Auth::user()['name'] ?? 'system';
        $written = 0;
        foreach ($rows as $r) {
            Database::run(
                "INSERT INTO {$table}
                    (name, kind, status, priority, display_order, linked_offer_id, tag, headline, body,
                     cta_label, cta_destination, image_asset_id, thumb_asset_id, media_type, alt_text,
                     audience_rule, frequency_cap, starts_at, ends_at,
                     target_action, click_url, internal_action, target_category, enabled,
                     row_version, created_at, updated_at, updated_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'none', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                         1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), ?)",
                [
                    $r['name'], $r['kind'], $r['status'], $r['priority'], $r['display_order'],
                    $r['linked_offer_id'], $r['tag'], $r['headline'], $r['body'],
                    $r['cta_label'], $r['cta_destination'], $r['alt_text'],
                    $r['audience_rule'], $r['frequency_cap'], $r['starts_at'], $r['ends_at'],
                    $r['target_action'], $r['click_url'], $r['internal_action'], $r['target_category'],
                    $r['enabled'], $actor,
                ]
            );
            $written++;
        }
        return $written;
    }

    private static function str(array $item, array $keys): string
    {
        foreach ($keys as $k) {
            if (isset($item[$k]) && is_scalar($item[$k])) {
                $value = trim((string) $item[$k]);
                if ($value !== '') {
                    return $value;
                }
            }
        }
        return '';
    }

    private static function int(array $item, array $keys, int $default): int
    {
        foreach ($keys as $k) {
            if (isset($item[$k]) && is_numeric($item[$k])) {
                return (int) $item[$k];
            }
        }
        return $default;
    }

    /** Accepts true/false, 1/0 and "yes"/"no", because all three appear in hand-written files. */
    private static function bool(array $item, array $keys, bool $default): bool
    {
        foreach ($keys as $k) {
            if (array_key_exists($k, $item)) {
                $v = $item[$k];
                if (is_bool($v)) {
                    return $v;
                }
                if (is_numeric($v)) {
                    return (int) $v === 1;
                }
                if (is_string($v)) {
                    return in_array(strtolower(trim($v)), ['1', 'true', 'yes', 'on'], true);
                }
            }
        }
        return $default;
    }

    /** A plain Y-m-d, or null — the campaign date columns are DATE NULL, never ''. */
    private static function dateOrNull(string $value): ?string
    {
        return preg_match('/^\d{4}-\d{2}-\d{2}$/', $value) ? $value : null;
    }

    /** A Nairobi wall-clock datetime as the UTC the tables store, or null. */
    private static function toUtc(string $local): ?string
    {
        if ($local === '') {
            return null;
        }
        try {
            return (new \DateTimeImmutable($local, new \DateTimeZone('Africa/Nairobi')))
                ->setTimezone(new \DateTimeZone('UTC'))->format('Y-m-d H:i:s');
        } catch (Throwable $e) {
            return null;
        }
    }
}
