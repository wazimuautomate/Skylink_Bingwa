<?php
/**
 * Draft → validate → preview → publish → rollback engine.
 *
 * The "working state" is the live editable tables (offers, billboards, templates,
 * support_config, app_config, app_versions). Publishing serialises the CURRENT working
 * state into a canonical, app-safe snapshot, validates it, and writes an IMMUTABLE row
 * to configuration_releases with an incrementing version, a SHA-256 checksum and a
 * signature. A "draft change" is simply any difference between the working state and
 * the latest published snapshot — surfaced as the pending-changes list.
 *
 * Rollback never mutates an old snapshot: it copies a chosen version's contents back
 * into the working tables, and the subsequent publish creates a NEW, later version.
 */

namespace App\Services;

use App\Core\Audit;
use App\Core\Auth;
use App\Core\Config;
use App\Core\Database;
use App\Core\Signer;
use App\Core\Snapshot;
use Throwable;

final class PublishingService
{
    public const SCHEMA_VERSION = 1;

    /* -------------------------------------------------------- shell status */

    public static function status(): array
    {
        $latest = self::currentRelease();
        $pending = self::pendingChanges();
        return [
            'environment'   => Config::isProduction() ? 'Production' : 'Staging',
            'version'       => $latest['version'] ?? 0,
            'lastPublishAt' => $latest['created_at'] ?? null,
            'signed'        => $latest ? ($latest['signature'] !== null && $latest['signature'] !== '') : false,
            'draftCount'    => count($pending),
        ];
    }

    public static function currentRelease(): ?array
    {
        return Database::fetch(
            'SELECT * FROM ' . Database::table('configuration_releases') . ' ORDER BY version DESC LIMIT 1'
        );
    }

    public static function release(int $version): ?array
    {
        return Database::fetch(
            'SELECT * FROM ' . Database::table('configuration_releases') . ' WHERE version = ? LIMIT 1',
            [$version]
        );
    }

    public static function releases(int $limit = 50): array
    {
        return Database::fetchAll(
            'SELECT * FROM ' . Database::table('configuration_releases') . ' ORDER BY version DESC LIMIT ' . (int) $limit
        );
    }

    public static function currentSnapshot(): ?array
    {
        $rel = self::currentRelease();
        if (!$rel) {
            return null;
        }
        $decoded = json_decode($rel['snapshot_json'], true);
        return is_array($decoded) ? $decoded : null;
    }

    private static function nextVersion(): int
    {
        $max = Database::scalar('SELECT MAX(version) FROM ' . Database::table('configuration_releases'));
        return ((int) $max) + 1;
    }

    /* -------------------------------------------------- build working snapshot */

    /**
     * Serialise the current working state into the app-safe snapshot structure.
     *
     * Section keys are the synchronisation contract (see ResourceVersions::RESOURCES).
     * Keys are only ever ADDED — `offers`, `billboards`, `support`, `appConfig` and
     * `version` keep the exact shape the shipped app already reads.
     */
    public static function buildWorkingSnapshot(): array
    {
        return [
            'schemaVersion' => self::SCHEMA_VERSION,
            'offers'        => self::buildOffers(),
            'categories'    => self::buildCategories(),
            'billboards'    => self::buildBillboards(),
            'support'       => self::buildSupport(),
            'appConfig'     => self::buildAppConfig(),
            'featureFlags'  => self::buildFeatureFlags(),
            'version'       => self::buildVersion(),
        ];
    }

    private static function buildOffers(): array
    {
        $rows = Database::fetchAll(
            "SELECT * FROM " . Database::table('offers') . "
              WHERE status = 'active' ORDER BY sort_hint, category, price"
        );
        $out = [];
        foreach ($rows as $r) {
            $out[] = [
                'id'             => $r['offer_id'],
                'category'       => $r['category'],
                'name'           => $r['name'],
                'price'          => (int) $r['price'],
                'validity'       => $r['validity'],
                'band'           => $r['band'],
                'dailyRule'      => self::appDailyRule($r['daily_rule']),
                'policy'         => $r['daily_rule'],
                'maxPerDay'      => $r['max_per_day'] !== null ? (int) $r['max_per_day'] : null,
                // Safaricom's time-of-day selling window, "HH:MM" in Nairobi time
                // ('' = no restriction on that end). The app shows this on every
                // offer card and refuses checkout outside it.
                'availableFrom'  => \App\Repositories\OfferRepository::hhmm($r['available_from'] ?? null),
                'availableTo'    => \App\Repositories\OfferRepository::hhmm($r['available_to'] ?? null),
                'commercialTag'  => $r['commercial_tag'],
                'offlineEligible'=> (int) $r['offline_eligible'] === 1,
                'restrictions'   => $r['restrictions'],
            ];
        }
        return $out;
    }

    /** Map a v2 daily policy to the string the shipped v1 app understands. */
    public static function appDailyRule(string $policy): string
    {
        return $policy === 'ONCE_PER_RECIPIENT_PER_DAY' ? 'ONCE_PER_DAY' : 'MULTIPLE_PER_DAY';
    }

    private static function buildBillboards(): array
    {
        $rows = Database::fetchAll(
            "SELECT b.*, a.stored_name AS image_name, COALESCE(t.stored_name, a.thumb_name, '') AS thumb_name
               FROM " . Database::table('billboards') . " b
               LEFT JOIN " . Database::table('billboard_assets') . " a ON a.id = b.image_asset_id
               LEFT JOIN " . Database::table('billboard_assets') . " t ON t.id = b.thumb_asset_id
              WHERE b.status IN ('active','scheduled') AND b.enabled = 1
              ORDER BY b.display_order ASC, b.priority ASC, b.id ASC"
        );
        $offersById = [];
        foreach (Database::fetchAll("SELECT offer_id, name, price, validity FROM " . Database::table('offers') . " WHERE status='active'") as $o) {
            $offersById[$o['offer_id']] = $o;
        }
        $out = [];
        foreach ($rows as $b) {
            $resolved = BillboardService::resolveContent($b, $offersById[$b['linked_offer_id']] ?? null);
            if ($resolved === null) {
                continue; // linked offer unavailable → billboard is disabled (never publish unresolved tokens)
            }
            // The app understands PROMOTION kinds (offer/announcement/update), not the
            // authoring mode ('simple'/'advanced'). Map by content: a linked offer becomes
            // an OFFER slide, otherwise an announcement.
            $isSimple = ($b['kind'] === 'simple');
            $appKind  = ($b['linked_offer_id'] ?? '') !== '' ? 'offer' : 'announcement';
            // Simple billboards are ALWAYS-ON: their schedule is ignored so they can never
            // silently expire (an empty window = show whenever active). Advanced billboards
            // keep their scheduled start/end window.
            $out[] = [
                'id'          => (int) $b['id'],
                'kind'        => $appKind,
                'priority'    => (int) $b['priority'],
                'displayOrder'=> (int) $b['display_order'],
                'linkedOfferId' => $b['linked_offer_id'],
                'tag'         => $resolved['tag'],
                'headline'    => $resolved['headline'],
                'body'        => $resolved['body'],
                'ctaLabel'    => $b['cta_label'],
                'ctaDestination' => $b['cta_destination'],
                // Media: `imageUrl` keeps its v1 meaning (the asset to draw). `mediaType`
                // tells a newer app whether that asset animates, and `thumbUrl` gives it a
                // cheap still frame to show first.
                'mediaType'   => (string) $b['media_type'],
                'imageUrl'    => $b['image_name'] ? ('uploads/' . $b['image_name']) : '',
                'thumbUrl'    => $b['thumb_name'] ? ('uploads/' . $b['thumb_name']) : '',
                'altText'     => $b['alt_text'],
                // Tap target. v1 apps keep reading ctaDestination; newer apps read these.
                'targetAction'  => (string) $b['target_action'],
                'clickUrl'      => (string) $b['click_url'],
                'internalAction'=> (string) $b['internal_action'],
                'targetCategory'=> (string) $b['target_category'],
                'audienceRule'=> $b['audience_rule'],
                'frequencyCap'=> (int) $b['frequency_cap'],
                'startsAt'    => $isSimple ? null : self::iso($b['starts_at']),
                'endsAt'      => $isSimple ? null : self::iso($b['ends_at']),
            ];
        }
        return $out;
    }

    private static function buildCategories(): array
    {
        $rows = Database::fetchAll(
            "SELECT * FROM " . Database::table('offer_categories') . "
              WHERE enabled = 1 ORDER BY sort_order, category_key"
        );
        $out = [];
        foreach ($rows as $r) {
            $out[] = [
                'id'          => $r['category_key'],
                'label'       => $r['label'],
                'description' => $r['description'],
                'accent'      => $r['accent'],
                'sortOrder'   => (int) $r['sort_order'],
            ];
        }
        return $out;
    }

    /** flagKey => bool. A map, so the app can read a flag it does not yet know about. */
    private static function buildFeatureFlags(): array
    {
        $out = [];
        foreach (Database::fetchAll(
            "SELECT flag_key, enabled FROM " . Database::table('feature_flags') . " ORDER BY flag_key"
        ) as $r) {
            $out[$r['flag_key']] = (int) $r['enabled'] === 1;
        }
        return $out;
    }

    private static function buildSupport(): array
    {
        $r = Database::fetch("SELECT * FROM " . Database::table('support_config') . " WHERE id = 1") ?: [];
        return [
            'tillNumber'      => $r['till_number'] ?? '',
            'paybillNumber'   => $r['paybill_number'] ?? '',
            'supportNumber'   => $r['support_number'] ?? '',
            'supportWhatsapp' => $r['support_whatsapp'] ?? '',
            'offlineSelfInstructions'  => $r['offline_self_instructions'] ?? '',
            'offlineOtherInstructions' => $r['offline_other_instructions'] ?? '',
            'supportBanner'   => $r['support_banner'] ?? '',
            'workingHours'    => $r['working_hours'] ?? '',
        ];
    }

    private static function buildAppConfig(): array
    {
        $r = Database::fetch("SELECT * FROM " . Database::table('app_config') . " WHERE id = 1") ?: [];
        $sync = (int) ($r['sync_interval_minutes'] ?? 360);
        $sync = max(60, min(1440, $sync));
        return [
            'maintenanceMode'       => (int) ($r['maintenance_mode'] ?? 0) === 1,
            'maintenanceMessage'    => $r['maintenance_message'] ?? '',
            'syncIntervalMinutes'   => $sync,
            'generalSupportMessage' => $r['general_support_message'] ?? '',
        ];
    }

    private static function buildVersion(): array
    {
        $r = Database::fetch(
            "SELECT * FROM " . Database::table('app_versions') . " WHERE status = 'active' ORDER BY latest_version_code DESC LIMIT 1"
        ) ?: [];
        $source = $r['update_source'] ?? 'github';
        if (!in_array($source, ['github', 'play'], true)) {
            $source = 'github';
        }
        return [
            'latestVersionCode' => (int) ($r['latest_version_code'] ?? 1),
            'latestVersionName' => $r['latest_version_name'] ?? '1.0.0',
            'minSupportedVersionCode' => (int) ($r['min_supported_version_code'] ?? 1),
            'mandatory'         => (int) ($r['mandatory'] ?? 0) === 1,
            'updateSource'      => $source,
            'playStoreUrl'      => $r['play_store_url'] ?? '',
            'apkUrl'            => $r['apk_url'] ?? '',
            'apkSha256'         => $r['apk_sha256'] ?? '',
            'rolloutPercent'    => (int) ($r['rollout_percent'] ?? 100),
            'releaseNotes'      => $r['release_notes'] ?? '',
        ];
    }

    /* ---------------------------------------------------------- validation */

    /** @return array{errors:string[], warnings:string[]} */
    public static function validate(array $snapshot): array
    {
        $errors = [];
        $warnings = [];

        // Offers: unique ids, valid prices, sane category. Different offers MAY share the
        // same price by design (e.g. a duplicated offer placed in another category), so a
        // shared price is deliberately NOT flagged — it is not this panel's concern.
        $seen = [];
        foreach ($snapshot['offers'] as $o) {
            if (isset($seen[$o['id']])) {
                $errors[] = "Duplicate offer id: {$o['id']}.";
            }
            $seen[$o['id']] = true;
            if (!is_int($o['price']) || $o['price'] < 1) {
                $errors[] = "Offer {$o['id']} has an invalid price.";
            }
            if (!in_array($o['category'], ['DATA', 'SMS', 'MINUTES', 'SPECIAL'], true)) {
                $errors[] = "Offer {$o['id']} has an unknown category.";
            }
        }

        // Billboards: no unresolved tokens (already stripped in builder; double-check).
        foreach ($snapshot['billboards'] as $b) {
            foreach (['headline', 'body', 'tag'] as $f) {
                if (strpos((string) $b[$f], '{{') !== false) {
                    $errors[] = "Billboard #{$b['id']} still contains unresolved tokens in {$f}.";
                }
            }
        }

        // Version rules.
        $v = $snapshot['version'];
        if ($v['minSupportedVersionCode'] > $v['latestVersionCode']) {
            $errors[] = 'Minimum supported version cannot be higher than the latest version.';
        }
        if ($v['mandatory'] && $v['playStoreUrl'] === '' && $v['apkUrl'] === '') {
            $errors[] = 'A forced update needs a valid Play Store or APK destination.';
        }
        if ($v['rolloutPercent'] < 0 || $v['rolloutPercent'] > 100) {
            $errors[] = 'Rollout percent must be between 0 and 100.';
        }

        // Support routes present.
        if (($snapshot['support']['tillNumber'] ?? '') === '' && ($snapshot['support']['paybillNumber'] ?? '') === '') {
            $warnings[] = 'No Till or Paybill number is configured — offline purchase will be disabled in the app.';
        }

        return ['errors' => $errors, 'warnings' => $warnings];
    }

    /* ------------------------------------------------------------- publish */

    /**
     * Publish the current working state as a new immutable release.
     * @return array{ok:bool, version:?int, errors:string[], warnings:string[]}
     */
    public static function publish(string $reason = '', ?int $rolledBackFrom = null): array
    {
        $snapshot = self::buildWorkingSnapshot();
        $check = self::validate($snapshot);
        if ($check['errors'] !== []) {
            return ['ok' => false, 'version' => null, 'errors' => $check['errors'], 'warnings' => $check['warnings']];
        }

        $user = Auth::user() ?? []; // may be empty during the install-time baseline publish
        try {
            $version = Database::transaction(function () use ($snapshot, $reason, $rolledBackFrom, $user) {
                $previousRelease = self::currentRelease();
                $previousSnapshot = self::currentSnapshot() ?: [];
                $version = self::nextVersion();
                $snapshot['configVersion'] = $version;
                $snapshot['publishedAt'] = gmdate('Y-m-d\TH:i:s\Z');

                // Per-resource versions are derived from the section bytes, so they must be
                // computed AFTER configVersion/publishedAt are set but they deliberately
                // hash only the section itself — those two fields never disturb them.
                $resourceMap = ResourceVersions::compute(
                    $snapshot,
                    ResourceVersions::forRelease($previousRelease),
                    $version
                );
                $snapshot['resourceVersions'] = array_map(
                    static fn(array $r) => $r['version'],
                    $resourceMap
                );

                $canonical = Snapshot::canonical($snapshot);
                $checksum = Signer::checksum($canonical);
                $signature = Signer::sign($canonical);
                $algo = $signature !== null ? Signer::algorithm() : '';

                // Re-encode the stored snapshot canonically so what we sign == what we serve.
                $storeJson = $canonical;

                $changes = self::diffSnapshots($previousSnapshot, $snapshot);

                Database::run(
                    'INSERT INTO ' . Database::table('configuration_releases') . '
                        (version, schema_version, snapshot_json, checksum, signature, signature_algo,
                         min_client_version_code, published_by, published_by_id, notes, rolled_back_from,
                         release_uid, change_count, resource_versions_json, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())',
                    [
                        $version, self::SCHEMA_VERSION, $storeJson, $checksum, $signature, $algo,
                        (int) Config::get('sync.min_client_version_code', 1),
                        ($user['name'] ?? null) ?: 'system', $user['id'] ?? null,
                        substr($reason, 0, 500), $rolledBackFrom,
                        self::releaseUid($version, $checksum), count($changes), json_encode($resourceMap),
                    ]
                );
                $releaseId = (int) Database::pdo()->lastInsertId();

                self::writeReleaseItems($releaseId, $version, $changes);
                ResourceVersions::persist($resourceMap);

                Settings::set('last_publish_at', gmdate('Y-m-d\TH:i:s\Z'));
                Settings::set('sync_hint_version', (string) $version);

                Audit::log([
                    'action'      => $rolledBackFrom ? 'rollback.execute' : 'publish.execute',
                    'entity_type' => 'configuration_release',
                    'entity_id'   => $version,
                    'reason'      => $reason,
                    'version'     => $version,
                    'after'       => ['version' => $version, 'checksum' => $checksum, 'signed' => $signature !== null],
                ]);

                return $version;
            });
            return ['ok' => true, 'version' => $version, 'errors' => [], 'warnings' => $check['warnings']];
        } catch (Throwable $e) {
            return ['ok' => false, 'version' => null, 'errors' => ['Publish failed: ' . $e->getMessage()], 'warnings' => $check['warnings']];
        }
    }

    /** A stable, human-quotable identifier for a release (auditing + support). */
    public static function releaseUid(int $version, string $checksum): string
    {
        return sprintf('rel-%s-v%d-%s', gmdate('Ymd'), $version, substr($checksum, 0, 8));
    }

    /**
     * Record the per-entity change breakdown for a release, including which FIELDS moved.
     * Rows are written once and never updated — this is the auditable history of what each
     * release actually contained.
     *
     * @param array $items output of diffSnapshots()
     */
    private static function writeReleaseItems(int $releaseId, int $version, array $items): void
    {
        $itemStmt = Database::pdo()->prepare(
            'INSERT INTO ' . Database::table('configuration_release_items') . '
                (release_id, version, entity_type, entity_id, change_type, summary, entity_label, fields_json)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        );
        $fieldStmt = Database::pdo()->prepare(
            'INSERT INTO ' . Database::table('release_field_changes') . '
                (release_id, version, entity_type, entity_id, field, field_label, old_value, new_value)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
        );
        foreach ($items as $it) {
            $fields = $it['fields'] ?? [];
            $itemStmt->execute([
                $releaseId, $version, $it['entity_type'], $it['entity_id'], $it['change_type'],
                substr((string) $it['summary'], 0, 255),
                substr((string) ($it['entity_label'] ?? ''), 0, 160),
                $fields !== [] ? json_encode($fields) : null,
            ]);
            foreach ($fields as $f) {
                $fieldStmt->execute([
                    $releaseId, $version, $it['entity_type'], $it['entity_id'],
                    substr((string) ($f['field'] ?? ''), 0, 64),
                    substr((string) ($f['label'] ?? ($f['field'] ?? '')), 0, 80),
                    self::valueForLog($f['from'] ?? null),
                    self::valueForLog($f['to'] ?? null),
                ]);
            }
        }
    }

    /** Render a diff value as short display text (never a whole nested structure). */
    private static function valueForLog($value): ?string
    {
        if ($value === null) {
            return null;
        }
        if (is_bool($value)) {
            return $value ? 'Yes' : 'No';
        }
        if (is_scalar($value)) {
            return mb_substr((string) $value, 0, 500);
        }
        return mb_substr((string) json_encode($value), 0, 500);
    }

    /* ------------------------------------------------------- pending / diff */

    /**
     * The list of changes the working state has over the latest published snapshot.
     * Also drives the sidebar draft badge, so its count is "number of changed entities".
     */
    public static function pendingChanges(): array
    {
        $current = self::currentSnapshot() ?: [];
        $working = self::buildWorkingSnapshot();
        return self::diffSnapshots($current, $working);
    }

    /**
     * Pending changes grouped for display.
     * @return array<string, array{label:string, count:int, items:array}>
     */
    public static function pendingChangesByModule(): array
    {
        return self::groupByModule(self::pendingChanges());
    }

    /**
     * Group diff items by their snapshot section, in ChangeDetector::MODULES order.
     * Modules with zero changes are omitted entirely — an empty group box would be the
     * same lie the old "Modified" badge told.
     */
    public static function groupByModule(array $items): array
    {
        $buckets = [];
        foreach ($items as $item) {
            $module = (string) ($item['module'] ?? ChangeDetector::moduleForEntity((string) ($item['entity_type'] ?? '')));
            $buckets[$module][] = $item;
        }
        $out = [];
        foreach (array_keys(ChangeDetector::MODULES) as $key) {
            if (empty($buckets[$key])) {
                continue;
            }
            $out[$key] = [
                'label' => ChangeDetector::moduleLabel($key),
                'count' => count($buckets[$key]),
                'items' => $buckets[$key],
            ];
            unset($buckets[$key]);
        }
        // Anything recorded under a section this build no longer knows about still shows.
        foreach ($buckets as $key => $group) {
            $out[$key] = ['label' => ChangeDetector::moduleLabel((string) $key), 'count' => count($group), 'items' => $group];
        }
        return $out;
    }

    /**
     * Everything the Preview / publish header card needs, computed once.
     * @return array<string,mixed>
     */
    public static function publishSummary(): array
    {
        $current = self::currentRelease();
        $pending = self::pendingChanges();
        $byModule = self::groupByModule($pending);
        $live = (int) ($current['version'] ?? 0);

        // The mirrored table is the fast path; a release published before it existed still
        // carries its own authoritative map, so fall back to that rather than showing v0.
        $resourceVersions = ResourceVersions::current();
        if ($resourceVersions === []) {
            $resourceVersions = ResourceVersions::forRelease($current);
        }

        return [
            'liveVersion'      => $live,
            'draftVersion'     => $live + 1,
            'pendingCount'     => count($pending),
            'lastPublishedAt'  => $current['created_at'] ?? null,
            'publishedBy'      => $current['published_by'] ?? null,
            'signed'           => $current ? ((string) ($current['signature'] ?? '') !== '') : false,
            'releaseUid'       => (string) ($current['release_uid'] ?? ''),
            'resourceVersions' => $resourceVersions,
            'affectedResources'=> array_keys($byModule),
            'byModule'         => $byModule,
            'pending'          => $pending,
        ];
    }

    /**
     * The recorded change breakdown of an already published version, grouped exactly like
     * the pending list so one partial renders both.
     */
    public static function releaseChanges(int $version): array
    {
        $rows = Database::fetchAll(
            'SELECT * FROM ' . Database::table('configuration_release_items') . '
              WHERE version = ? ORDER BY entity_type, change_type, id',
            [$version]
        );
        $items = [];
        foreach ($rows as $r) {
            $type = (string) $r['entity_type'];
            $fields = json_decode((string) ($r['fields_json'] ?? ''), true);
            $items[] = [
                'entity_type'  => $type,
                'module'       => ChangeDetector::moduleForEntity($type),
                'entity_id'    => (string) $r['entity_id'],
                'entity_label' => (string) ($r['entity_label'] ?? ''),
                'change_type'  => (string) $r['change_type'],
                'summary'      => (string) $r['summary'],
                'fields'       => is_array($fields) ? $fields : [],
            ];
        }
        return self::groupByModule($items);
    }

    /**
     * Value-only diff between two snapshots, section by section.
     *
     * Every returned item is:
     *   entity_type, module, entity_id, entity_label, change_type, summary, fields
     * where `fields` is ChangeDetector::compareItems() output. An entity whose fields list
     * comes back EMPTY is never emitted — that is the whole fix: saving an item without
     * editing it produces no diff, because no value moved.
     *
     * Pure: no database access, so it stays unit-testable with plain arrays.
     *
     * @return array<int,array{entity_type:string, module:string, entity_id:string,
     *                         entity_label:string, change_type:string, summary:string, fields:array}>
     */
    public static function diffSnapshots(array $old, array $new): array
    {
        $items = [];

        self::diffList(
            $items,
            'offers',
            'offer',
            $old['offers'] ?? [],
            $new['offers'] ?? [],
            static function (array $o): string {
                $label = trim(((string) ($o['category'] ?? '')) . ' ' . ((string) ($o['name'] ?? '')));
                if ($label === '') {
                    $label = 'Offer ' . (string) ($o['id'] ?? '');
                }
                if (isset($o['price']) && is_numeric($o['price'])) {
                    $label .= ' — KSh ' . number_format((float) $o['price'], 0);
                }
                return $label;
            }
        );

        self::diffList(
            $items,
            'categories',
            'category',
            $old['categories'] ?? [],
            $new['categories'] ?? [],
            static fn(array $c): string => trim((string) ($c['label'] ?? '')) !== ''
                ? (string) $c['label']
                : ('Category ' . (string) ($c['id'] ?? ''))
        );

        self::diffList(
            $items,
            'billboards',
            'billboard',
            $old['billboards'] ?? [],
            $new['billboards'] ?? [],
            static fn(array $b): string => trim((string) ($b['headline'] ?? '')) !== ''
                ? (string) $b['headline']
                : ('Billboard #' . (string) ($b['id'] ?? ''))
        );

        // Singletons: one item whose `fields` are the changed keys inside the object.
        foreach (['support', 'appConfig', 'featureFlags', 'version'] as $key) {
            self::diffSingleton($items, $key, $old[$key] ?? null, $new[$key] ?? null);
        }

        return $items;
    }

    /** Diff a keyed collection. Identity is the item's `id`. */
    private static function diffList(array &$items, string $module, string $type, array $old, array $new, callable $describe): void
    {
        $oldById = self::indexById($old);
        $newById = self::indexById($new);

        foreach ($newById as $id => $item) {
            if (!array_key_exists($id, $oldById)) {
                $fields = ChangeDetector::compareItems($type, [], $item);
                $items[] = self::changeItem($module, $type, $id, $describe($item), 'added', $fields);
                continue;
            }
            $fields = ChangeDetector::compareItems($type, $oldById[$id], $item);
            if ($fields === []) {
                continue; // identical values — not a change, whatever any timestamp says
            }
            $items[] = self::changeItem($module, $type, $id, $describe($item), 'changed', $fields);
        }

        foreach ($oldById as $id => $item) {
            if (array_key_exists($id, $newById)) {
                continue;
            }
            $fields = ChangeDetector::compareItems($type, $item, []);
            $items[] = self::changeItem($module, $type, $id, $describe($item), 'removed', $fields);
        }
    }

    /** Diff a single object section (support, appConfig, featureFlags, version). */
    private static function diffSingleton(array &$items, string $key, $old, $new): void
    {
        $oldArr = self::asArray($old);
        $newArr = self::asArray($new);
        $fields = ChangeDetector::compareItems($key, $oldArr, $newArr);
        if ($fields === []) {
            return;
        }
        $label = ChangeDetector::moduleLabel($key);
        $items[] = [
            'entity_type'  => $key,
            'module'       => $key,
            'entity_id'    => $key,
            'entity_label' => $label,
            'change_type'  => 'changed',
            'summary'      => ChangeDetector::summarise('changed', $label, $fields),
            'fields'       => $fields,
        ];
    }

    private static function changeItem(string $module, string $type, $id, string $label, string $changeType, array $fields): array
    {
        return [
            'entity_type'  => $type,
            'module'       => $module,
            'entity_id'    => (string) $id,
            'entity_label' => $label,
            'change_type'  => $changeType,
            'summary'      => ChangeDetector::summarise($changeType, $label, $fields),
            'fields'       => $fields,
        ];
    }

    /** id => item, skipping anything that is not a usable array row. */
    private static function indexById(array $rows): array
    {
        $out = [];
        foreach ($rows as $row) {
            if (!is_array($row) || !array_key_exists('id', $row)) {
                continue;
            }
            $out[(string) $row['id']] = $row;
        }
        return $out;
    }

    private static function asArray($value): array
    {
        if (is_array($value)) {
            return $value;
        }
        if (is_object($value)) {
            return (array) $value;
        }
        return [];
    }

    /* ---------------------------------------------------------- rollback */

    /**
     * Restore a previous version's contents into the working tables (creates a "draft"
     * that the operator then previews and publishes as a new version). Never mutates the
     * old snapshot.
     */
    public static function restoreWorkingFrom(int $version): bool
    {
        $rel = self::release($version);
        if (!$rel) {
            return false;
        }
        $snap = json_decode($rel['snapshot_json'], true);
        if (!is_array($snap)) {
            return false;
        }
        Database::transaction(function () use ($snap) {
            RollbackRestorer::apply($snap);
        });
        return true;
    }

    /* ------------------------------------------------------------- helpers */

    public static function iso(?string $dbDatetime): ?string
    {
        if (!$dbDatetime) {
            return null;
        }
        try {
            return (new \DateTimeImmutable($dbDatetime, new \DateTimeZone('UTC')))->format('Y-m-d\TH:i:s\Z');
        } catch (Throwable $e) {
            return null;
        }
    }
}
