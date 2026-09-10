<?php
/**
 * GET get_sync_manifest.php — the tiny fingerprint document that drives INCREMENTAL
 * sync in the app. App-key guarded.
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON):
 *   { publishVersion, generatedAt,
 *     resources: { CONFIG:  { version, updatedAt, checksum },
 *                  OFFERS:  { ... }, BILLBOARDS: { ... },
 *                  NOTIFICATION_TEMPLATES: { ... }, REMOTE_NOTIFICATIONS: { ... } } }
 *
 * WHY IT MATTERS
 * --------------
 * The app downloads a resource only when its version AND checksum differ from the copy
 * it already holds, so a device that is already current transfers a few hundred bytes
 * instead of the whole catalogue. `publishVersion` is the admin's release revision:
 * when it moves, the owner pressed Publish (e.g. the displayed Paybill or Till number
 * stopped working and was replaced) and every online app force-syncs within ~90s — no
 * Play Store update, no reinstall, no manual refresh, no cache clearing.
 *
 * THIS ENDPOINT MUST STAY TINY AND CHEAP: every online client polls it roughly every
 * 90 seconds while in the foreground.
 *   - One indexed row is read to learn the current release version.
 *   - The per-section fingerprints are computed once per publish and cached in a small
 *     temp file keyed by that version, so the normal poll never decodes the snapshot.
 *   - It contains NO content and NO secrets — only versions, timestamps and checksums.
 *
 * VERSION DERIVATION
 * ------------------
 * A resource's `version` is derived from the CONTENT of its snapshot section
 * (crc32 of the canonical JSON), not from the release revision. That is deliberate: the
 * release revision bumps on every publish, so using it would make every device
 * re-download every resource after any publish — the exact waste incremental sync
 * exists to remove. A content-derived version changes if and only if that section
 * changed, which is what "offers changed → offers only" requires. The release revision
 * is still exposed, once, as `publishVersion`.
 *
 * ADMIN CONSOLE
 * -------------
 * Populated automatically by Preview & Publish (PublishingService::buildWorkingSnapshot):
 * `appConfig`/`support`/`version` → CONFIG, `offers`/`categories` → OFFERS,
 * `billboards` → BILLBOARDS, `notifications` → NOTIFICATION_TEMPLATES and
 * REMOTE_NOTIFICATIONS. Nothing extra to fill in.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';

require_app_key($config);
// Global service lock (Admin V2 -> Danger zone). Refuses with 503 "Request Denied".
deny_if_service_locked($config);

/** Fingerprint one snapshot section. Missing section → the empty-array fingerprint. */
function manifest_fingerprint($section, int $updatedAtMillis): array
{
    $canonical = json_encode($section === null ? [] : $section);
    if ($canonical === false) {
        $canonical = '[]';
    }
    return [
        // Content-derived and stable: it changes iff this section changed. Masked to 31
        // bits so the value is a positive int on 32-bit PHP too (crc32 can go negative
        // there). Collisions are harmless — `checksum` below is compared as well.
        'version'   => crc32($canonical) & 0x7fffffff,
        'updatedAt' => $updatedAtMillis,
        'checksum'  => md5($canonical),
    ];
}

/** Build the whole resources map from a decoded snapshot (null = nothing published). */
function manifest_resources(?array $snap, int $updatedAtMillis): array
{
    $snap = $snap ?? [];

    // CONFIG is the seller configuration the app must never get wrong: Till/Paybill
    // route, support details and the minimum-version gate. Fingerprinted together
    // because the app refreshes them in one call (syncRemoteConfig).
    $configSection = [
        'appConfig'    => $snap['appConfig']    ?? [],
        'support'      => $snap['support']      ?? [],
        'version'      => $snap['version']      ?? [],
        'featureFlags' => $snap['featureFlags'] ?? [],
    ];
    // Offers and their categories are consumed together by the catalogue sync.
    $offersSection = [
        'offers'     => $snap['offers']     ?? [],
        'categories' => $snap['categories'] ?? [],
    ];
    // Notification templates and published notifications are both derived from the same
    // `notifications` campaigns, so they legitimately share a fingerprint: when
    // campaigns change, both endpoints have new content.
    $notifications = $snap['notifications'] ?? [];

    return [
        'CONFIG'                 => manifest_fingerprint($configSection, $updatedAtMillis),
        'OFFERS'                 => manifest_fingerprint($offersSection, $updatedAtMillis),
        'BILLBOARDS'             => manifest_fingerprint($snap['billboards'] ?? [], $updatedAtMillis),
        'NOTIFICATION_TEMPLATES' => manifest_fingerprint($notifications, $updatedAtMillis),
        'REMOTE_NOTIFICATIONS'   => manifest_fingerprint($notifications, $updatedAtMillis),
    ];
}

/** Small per-release cache file. Contains only fingerprints — never any secret. */
function manifest_cache_path(int $publishVersion): string
{
    return rtrim(sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR
        . 'mybingwa_manifest_' . substr(md5(__DIR__), 0, 12) . '_v' . $publishVersion . '.json';
}

$nowMillis = (int) round(microtime(true) * 1000);

try {
    $pdo = require __DIR__ . '/db.php';

    // Step 1 — one tiny indexed read to learn the current release. No MEDIUMTEXT.
    $publishVersion = 0;
    $updatedAtMillis = 0;
    try {
        $row = $pdo->query(
            'SELECT version, created_at FROM mb_configuration_releases ORDER BY version DESC LIMIT 1'
        )->fetch();
        if ($row) {
            $publishVersion  = (int) ($row['version'] ?? 0);
            $ts              = strtotime((string) ($row['created_at'] ?? ''));
            $updatedAtMillis = $ts ? $ts * 1000 : 0;
        }
    } catch (Throwable $e) {
        // Admin not installed / table absent → nothing published yet (version 0).
        $publishVersion = 0;
    }

    // Step 2 — serve the cached fingerprints for this release when we have them.
    $resources = null;
    $cachePath = manifest_cache_path($publishVersion);
    if (is_readable($cachePath)) {
        $cached = json_decode((string) @file_get_contents($cachePath), true);
        if (is_array($cached) && !empty($cached['resources']) && is_array($cached['resources'])) {
            $resources       = $cached['resources'];
            $updatedAtMillis = (int) ($cached['updatedAt'] ?? $updatedAtMillis);
        }
    }

    // Step 3 — cache miss (first request after a publish): decode the snapshot once.
    if ($resources === null) {
        $resources = manifest_resources(published_snapshot($pdo), $updatedAtMillis);
        try {
            @file_put_contents(
                $cachePath,
                json_encode(['updatedAt' => $updatedAtMillis, 'resources' => $resources]),
                LOCK_EX
            );
            // Drop this endpoint's caches for older releases so temp does not grow.
            $stale = glob(
                rtrim(sys_get_temp_dir(), '/\\') . DIRECTORY_SEPARATOR
                . 'mybingwa_manifest_' . substr(md5(__DIR__), 0, 12) . '_v*.json'
            );
            if (is_array($stale)) {
                foreach ($stale as $file) {
                    if ($file !== $cachePath) {
                        @unlink($file);
                    }
                }
            }
        } catch (Throwable $e) {
            // A read-only or full temp dir just means we recompute next time.
        }
    }

    json_out([
        'publishVersion' => $publishVersion,
        'generatedAt'    => $nowMillis,
        'resources'      => $resources,
    ]);
} catch (Throwable $e) {
    // Never a 500, and never a MISLEADING manifest. A 503 with an empty document makes
    // the app treat the manifest as unavailable, so it falls back to its normal
    // throttled full sync and keeps every piece of cached content.
    json_out([
        'publishVersion' => 0,
        'generatedAt'    => $nowMillis,
        'resources'      => new stdClass(),
    ], 503);
}
