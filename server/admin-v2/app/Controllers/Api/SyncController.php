<?php
/**
 * Read-only public synchronisation API — the ONLY interface the Android app talks to.
 *
 *   GET /api/health                 liveness + what the server currently publishes
 *   GET /api/app-data               the whole published snapshot (v1 contract, unchanged)
 *   GET /api/sync/manifest          the cheap "what changed?" call
 *   GET /api/sync/resource/{key}    one resource
 *   GET /api/sync/resources         several resources in one round trip
 *
 * Design rules that must not be broken:
 *
 *  - OFFLINE FIRST. Every response either carries valid published content or an error
 *    status. Nothing here ever tells a device to clear what it already holds; a failed
 *    or partial sync simply leaves the cached copy in place.
 *  - PUBLISH ONLY. The server hands out published configuration. No endpoint accepts a
 *    phone number, a purchase, a favourite or any other personal value. The single
 *    identifier that may ever be recorded is an anonymous installation id, and it is
 *    optional.
 *  - BACKWARDS COMPATIBLE. /api/app-data still returns the stored snapshot bytes
 *    verbatim with the same ETag semantics it has always had.
 *  - EXTENSIBLE. Everything is driven by ResourceVersions::RESOURCES and the section
 *    keys of the published snapshot, so a future resource needs no protocol change.
 */

namespace App\Controllers\Api;

use App\Core\Config;
use App\Core\Database;
use App\Core\Request;
use App\Core\Response;
use App\Services\PublishingService;
use App\Services\RateLimiter;
use App\Services\ResourceVersions;
use App\Services\ServiceLock;
use Throwable;

final class SyncController
{
    /** Most resources a single batch request may ask for. */
    public const MAX_BATCH_KEYS = 20;

    /** Route shape for a single resource. RELATIVE — the app resolves it against its own base URL. */
    public const RESOURCE_PATH = 'api/sync/resource/';

    /** Decoded published snapshot, cached for the lifetime of this request. */
    private ?array $snapshotAssoc = null;

    /** Object-decoded published snapshot: keeps `{}` a map instead of collapsing it to `[]`. */
    private $snapshotObject = null;

    /* ------------------------------------------------------------------ gate */

    /** The global service lock, then a per-IP rate limit + an optional shared X-Sync-Key. */
    private function gate(Request $request): void
    {
        // The service lock is checked FIRST and refuses every endpoint on this controller,
        // health included: while it is on, this server publishes nothing to any device.
        if (ServiceLock::isLocked()) {
            Response::json(ServiceLock::payload(), 503, ['Retry-After' => '3600', 'Cache-Control' => 'no-store']);
        }
        $perMinute = (int) Config::get('sync.rate_limit_per_minute', 60);
        if (!RateLimiter::allow('sync:' . $request->ip(), $perMinute)) {
            Response::json(['error' => 'rate_limited'], 429, ['Retry-After' => '60']);
        }
        $required = (string) Config::get('security.sync_api_key', '');
        if ($required !== '') {
            $sent = $request->header('X-Sync-Key');
            if (!hash_equals($required, (string) $sent)) {
                Response::json(['error' => 'unauthorised'], 401);
            }
        }
    }

    /* ------------------------------------------------------------ v1 endpoint */

    public function appData(Request $request): void
    {
        $this->gate($request);
        $rel = PublishingService::currentRelease();
        if (!$rel) {
            $this->recordEvent($request, 'snapshot', '503');
            Response::json(['error' => 'no_published_configuration'], 503);
        }
        $etag = '"' . $rel['checksum'] . '"';
        if (trim((string) $request->header('If-None-Match')) === $etag) {
            $this->recordEvent($request, 'not_modified', '304');
            Response::json([], 304, ['ETag' => $etag, 'Cache-Control' => 'no-cache']);
        }
        // snapshot_json is already canonical and app-safe: it contains configVersion,
        // publishedAt, offers, billboards, support, appConfig and version (the update
        // info). Serve it verbatim so empty maps stay {} and the bytes match the checksum.
        $this->recordEvent($request, 'snapshot', '200');
        http_response_code(200);
        header('Content-Type: application/json; charset=utf-8');
        header('ETag: ' . $etag);
        header('Cache-Control: no-cache');
        header('X-Config-Version: ' . (int) $rel['version']);
        echo $rel['snapshot_json'];
        exit;
    }

    /* -------------------------------------------------------------- manifest */

    /**
     * The cheap call a device makes first: release identity plus a per-resource version,
     * checksum, item count and relative URL. Nothing here is large, so a device on a poor
     * connection can decide what to fetch for a few hundred bytes.
     */
    public function manifest(Request $request): void
    {
        $this->gate($request);
        $rel = $this->requireRelease($request, 'manifest');

        $etag = '"' . $rel['checksum'] . '"';
        if (self::etagMatches($request->header('If-None-Match'), $etag)) {
            $this->recordEvent($request, 'not_modified', '304');
            Response::json([], 304, $this->cacheHeaders($etag, $rel));
        }

        $map = $this->resourceMap($rel);
        $resources = [];
        foreach ($map as $key => $entry) {
            $resources[$key] = [
                'version'  => $entry['version'],
                'checksum' => $entry['checksum'],
                'count'    => $entry['count'],
                'url'      => self::resourceUrl($key),
            ];
        }

        $payload = [
            'configVersion'        => (int) $rel['version'],
            'publishedAt'          => $this->publishedAt($rel),
            'checksum'             => (string) $rel['checksum'],
            'schemaVersion'        => $this->schemaVersion($rel),
            'signed'               => $this->isSigned($rel),
            'signatureAlgo'        => (string) ($rel['signature_algo'] ?? ''),
            'minClientVersionCode' => (int) ($rel['min_client_version_code'] ?? 1),
            'releaseUid'           => $this->releaseUid($rel),
            'resources'            => $resources === [] ? new \stdClass() : $resources,
        ];

        // `since` is the config version the device already holds. It never filters the
        // full resource map away — the device may still want to verify its checksums.
        $since = self::parseSince($request->get('since'));
        if ($since !== null) {
            $changed = [];
            foreach ($map as $key => $entry) {
                if ($entry['version'] > $since) {
                    $changed[] = $key;
                }
            }
            $payload['since']    = $since;
            $payload['changed']  = $changed;
            $payload['upToDate'] = $changed === [];
        }

        $this->recordEvent($request, 'manifest', '200', '', $since);
        Response::json($payload, 200, $this->cacheHeaders($etag, $rel));
    }

    /* --------------------------------------------------------- one resource */

    /**
     * A single resource from the PUBLISHED snapshot. Never from the working/draft tables:
     * a device must only ever see content an operator deliberately published.
     */
    public function resource(Request $request, string $key): void
    {
        $this->gate($request);
        $key = self::normaliseKey($key);
        $rel = $this->requireRelease($request, 'resource', $key);

        $map = $this->resourceMap($rel);
        if ($key === '' || !isset($map[$key])) {
            $this->recordEvent($request, 'resource', '404', $key);
            Response::json(['error' => 'unknown_resource', 'supported' => array_keys($map)], 404);
        }

        $entry = $map[$key];
        // The RESOURCE checksum, not the snapshot checksum: a device gets a 304 for a
        // resource that did not move even when the release version did.
        $etag = '"' . $entry['checksum'] . '"';
        if (self::etagMatches($request->header('If-None-Match'), $etag)) {
            $this->recordEvent($request, 'not_modified', '304', $key);
            Response::json([], 304, $this->cacheHeaders($etag, $rel));
        }

        $payload = [
            'resource'      => $key,
            'version'       => $entry['version'],
            'configVersion' => (int) $rel['version'],
            'checksum'      => $entry['checksum'],
            'count'         => $entry['count'],
            'publishedAt'   => $this->publishedAt($rel),
            'data'          => $this->sectionData($rel, $key),
        ];

        $this->recordEvent($request, 'resource', '200', $key);
        Response::json($payload, 200, $this->cacheHeaders($etag, $rel));
    }

    /* ------------------------------------------------------ several resources */

    /**
     * Batch form. A device with three stale resources spends ONE round trip instead of
     * three. With no `keys` parameter this returns every resource — the same content as
     * /api/app-data, in the newer envelope.
     */
    public function resources(Request $request): void
    {
        $this->gate($request);
        $rel = $this->requireRelease($request, 'resources');

        $map = $this->resourceMap($rel);
        $raw = $request->get('keys');
        $parsed = self::parseKeys(is_scalar($raw) ? (string) $raw : null, array_keys($map));
        $keys = $parsed['keys'];

        $since = self::parseSince($request->get('since'));
        if ($since !== null) {
            $keys = array_values(array_filter(
                $keys,
                static fn(string $k): bool => ($map[$k]['version'] ?? 0) > $since
            ));
        }

        // A batch response varies by the requested key set, so its ETag has to as well.
        $etag = '"' . hash('sha256', $rel['checksum'] . '|' . implode(',', $keys) . '|' . (string) $since) . '"';
        if (self::etagMatches($request->header('If-None-Match'), $etag)) {
            $this->recordEvent($request, 'not_modified', '304', implode(',', $keys), $since);
            Response::json([], 304, $this->cacheHeaders($etag, $rel));
        }

        $out = [];
        foreach ($keys as $key) {
            $entry = $map[$key];
            $out[$key] = [
                'resource'      => $key,
                'version'       => $entry['version'],
                'configVersion' => (int) $rel['version'],
                'checksum'      => $entry['checksum'],
                'count'         => $entry['count'],
                'publishedAt'   => $this->publishedAt($rel),
                'data'          => $this->sectionData($rel, $key),
            ];
        }

        $payload = [
            'configVersion' => (int) $rel['version'],
            'publishedAt'   => $this->publishedAt($rel),
            'checksum'      => (string) $rel['checksum'],
            'resources'     => $out === [] ? new \stdClass() : $out,
            // Unknown keys are reported, never fatal: one stale key name in an old app
            // build must not cost it the resources it CAN read.
            'unknown'       => $parsed['unknown'],
        ];
        if ($since !== null) {
            $payload['since']    = $since;
            $payload['upToDate'] = $out === [];
        }

        $this->recordEvent($request, 'resources', '200', implode(',', $keys), $since);
        Response::json($payload, 200, $this->cacheHeaders($etag, $rel));
    }

    /* ---------------------------------------------------------------- health */

    /**
     * Liveness plus a summary of what is currently published. It must answer even when
     * the database misbehaves — hence ok:false rather than an exception.
     */
    public function health(Request $request): void
    {
        $this->gate($request);

        $dbOk = true;
        try {
            Database::scalar('SELECT 1');
        } catch (Throwable $e) {
            $dbOk = false;
        }

        $rel = null;
        try {
            $rel = PublishingService::currentRelease();
        } catch (Throwable $e) {
            $rel = null;
            $dbOk = false;
        }

        $resources = [];
        if ($rel) {
            try {
                foreach ($this->resourceMap($rel) as $key => $entry) {
                    $resources[$key] = $entry['version'];
                }
            } catch (Throwable $e) {
                $resources = [];
            }
        }

        Response::json([
            'ok'            => $dbOk,
            'configVersion' => (int) ($rel['version'] ?? 0),
            'time'          => gmdate('Y-m-d\TH:i:s\Z'),
            'schemaVersion' => $rel ? $this->schemaVersion($rel) : PublishingService::SCHEMA_VERSION,
            'signed'        => $rel ? $this->isSigned($rel) : false,
            'releaseUid'    => $rel ? $this->releaseUid($rel) : '',
            'resources'     => $resources === [] ? new \stdClass() : $resources,
        ]);
    }

    /* ------------------------------------------------------------ pure helpers */

    /**
     * Parse a `keys=offers,billboards` parameter against the resources this release offers.
     *
     * Pure and total: it never throws, never touches the database and never returns a
     * value that was not either a supported key or a sanitised echo of what was asked
     * for. An empty/absent parameter means "everything".
     *
     * @param string[] $supported
     * @return array{keys: string[], unknown: string[]}
     */
    public static function parseKeys(?string $raw, array $supported): array
    {
        $index = [];
        foreach ($supported as $key) {
            $key = self::normaliseKey((string) $key);
            if ($key !== '') {
                $index[$key] = true;
            }
        }

        $trimmed = $raw === null ? '' : trim($raw);
        if ($trimmed === '') {
            return ['keys' => array_keys($index), 'unknown' => []];
        }

        $keys = [];
        $unknown = [];
        $seen = [];
        $used = 0;
        foreach (explode(',', $trimmed) as $token) {
            $token = self::normaliseKey($token);
            if ($token === '' || isset($seen[$token])) {
                continue;
            }
            $seen[$token] = true;
            if (++$used > self::MAX_BATCH_KEYS) {
                break; // hard cap: one request can never fan out into unbounded work
            }
            if (isset($index[$token])) {
                $keys[] = $token;
            } else {
                $unknown[] = $token;
            }
        }
        return ['keys' => $keys, 'unknown' => $unknown];
    }

    /**
     * A resource key reduced to a safe identifier. Keys are ONLY ever compared against
     * ResourceVersions::keys() — they are never interpolated into SQL or a file path —
     * but they are also echoed back in `unknown`, so they are sanitised here.
     */
    public static function normaliseKey(string $raw): string
    {
        $clean = preg_replace('/[^A-Za-z0-9._-]/', '', trim($raw));
        return substr((string) $clean, 0, 40);
    }

    /** A non-negative integer parameter (`since`, a version code), or null when absent/invalid. */
    public static function parseSince($raw): ?int
    {
        if ($raw === null || !is_scalar($raw)) {
            return null;
        }
        $value = trim((string) $raw);
        if ($value === '' || preg_match('/^\d{1,9}$/', $value) !== 1) {
            return null;
        }
        return (int) $value;
    }

    /** Relative URL for a resource, built from the route shape — never from a host name. */
    public static function resourceUrl(string $key): string
    {
        return self::RESOURCE_PATH . $key;
    }

    /**
     * The anonymous installation id, or null when it is absent or does not look like one.
     * At most 64 characters of [A-Za-z0-9._-]. Anything else is dropped silently: a device
     * with a malformed id still gets its data, it simply is not counted.
     */
    public static function installId(?string $raw): ?string
    {
        $value = trim((string) $raw);
        return preg_match('/^[A-Za-z0-9._-]{1,64}$/', $value) === 1 ? $value : null;
    }

    /** RFC-shaped If-None-Match test: handles a list of tags, weak tags and `*`. */
    public static function etagMatches(?string $headerValue, string $etag): bool
    {
        $header = trim((string) $headerValue);
        if ($header === '' || $etag === '') {
            return false;
        }
        if ($header === '*') {
            return true;
        }
        foreach (explode(',', $header) as $candidate) {
            $candidate = trim($candidate);
            if (stripos($candidate, 'W/') === 0) {
                $candidate = trim(substr($candidate, 2));
            }
            if ($candidate !== '' && $candidate === $etag) {
                return true;
            }
        }
        return false;
    }

    /* --------------------------------------------------------- release helpers */

    /** The published release, or a 503 that leaves the device's cache untouched. */
    private function requireRelease(Request $request, string $event, string $resource = ''): array
    {
        $rel = null;
        try {
            $rel = PublishingService::currentRelease();
        } catch (Throwable $e) {
            $rel = null;
        }
        if (!$rel) {
            $this->recordEvent($request, $event, '503', $resource);
            Response::json(['error' => 'no_published_configuration'], 503);
        }
        return $rel;
    }

    /** @return array<string, array{version:int, checksum:string, count:int}> */
    private function resourceMap(array $release): array
    {
        $snapshot = $this->snapshot($release);
        $stored = ResourceVersions::forRelease($release);
        $releaseVersion = (int) $release['version'];
        $fallback = null; // computed only if the release predates resource versioning

        $out = [];
        foreach (ResourceVersions::keys() as $key) {
            if (!array_key_exists($key, $snapshot)) {
                continue; // this release simply does not publish that section
            }
            $row = is_array($stored[$key] ?? null) ? $stored[$key] : [];
            $checksum = (string) ($row['checksum'] ?? '');
            if ($checksum === '') {
                if ($fallback === null) {
                    $fallback = ResourceVersions::checksums($snapshot);
                }
                $checksum = (string) ($fallback[$key] ?? '');
            }
            $version = (int) ($row['version'] ?? 0);
            $out[$key] = [
                // A release published before resource versioning existed reports every
                // resource at its own config version: "assume it all changed" is the only
                // honest answer, and it is never destructive.
                'version'  => $version > 0 ? $version : $releaseVersion,
                'checksum' => $checksum,
                'count'    => isset($row['count'])
                    ? (int) $row['count']
                    : ResourceVersions::countOf($key, $snapshot[$key]),
            ];
        }
        return $out;
    }

    /** Decoded (associative) published snapshot — used for counts and checksums. */
    private function snapshot(array $release): array
    {
        if ($this->snapshotAssoc === null) {
            $decoded = json_decode((string) ($release['snapshot_json'] ?? ''), true);
            $this->snapshotAssoc = is_array($decoded) ? $decoded : [];
        }
        return $this->snapshotAssoc;
    }

    /**
     * The section as it will be SERVED. Decoded into objects rather than associative
     * arrays so an empty published map stays `{}` and never turns into `[]` — the device
     * verifies a checksum over these bytes.
     */
    private function sectionData(array $release, string $key)
    {
        if ($this->snapshotObject === null) {
            $decoded = json_decode((string) ($release['snapshot_json'] ?? ''));
            $this->snapshotObject = is_object($decoded) ? $decoded : new \stdClass();
        }
        return property_exists($this->snapshotObject, $key) ? $this->snapshotObject->{$key} : null;
    }

    private function publishedAt(array $release): ?string
    {
        $snapshot = $this->snapshot($release);
        $published = (string) ($snapshot['publishedAt'] ?? '');
        if ($published !== '') {
            return $published;
        }
        return PublishingService::iso($release['created_at'] ?? null);
    }

    private function schemaVersion(array $release): int
    {
        $snapshot = $this->snapshot($release);
        return (int) ($release['schema_version'] ?? $snapshot['schemaVersion'] ?? PublishingService::SCHEMA_VERSION);
    }

    private function isSigned(array $release): bool
    {
        return trim((string) ($release['signature'] ?? '')) !== '';
    }

    /** The release's quotable identifier, reconstructed for rows written before it existed. */
    private function releaseUid(array $release): string
    {
        $uid = trim((string) ($release['release_uid'] ?? ''));
        if ($uid !== '') {
            return $uid;
        }
        $day = gmdate('Ymd');
        $iso = PublishingService::iso($release['created_at'] ?? null);
        if ($iso !== null) {
            $day = str_replace('-', '', substr($iso, 0, 10));
        }
        return sprintf('rel-%s-v%d-%s', $day, (int) $release['version'], substr((string) $release['checksum'], 0, 8));
    }

    /** @return array<string,string> */
    private function cacheHeaders(string $etag, array $release): array
    {
        return [
            'ETag'              => $etag,
            'Cache-Control'     => 'no-cache',
            'X-Config-Version'  => (string) (int) $release['version'],
        ];
    }

    /* --------------------------------------------------------------- telemetry */

    /**
     * Optional, anonymous sync telemetry.
     *
     * Written ONLY when the request carries a well-formed X-Install-Id. No IP address, no
     * phone number, no user agent, nothing that identifies a person — just "an install on
     * app build N holding config version M asked for X and got Y". Any failure here is
     * swallowed: telemetry is never worth a failed synchronisation.
     */
    private function recordEvent(
        Request $request,
        string $eventType,
        string $resultCode,
        string $resource = '',
        ?int $deviceConfigVersion = null
    ): void {
        try {
            $installId = self::installId($request->header('X-Install-Id'));
            if ($installId === null) {
                return;
            }
            $versionCode = self::parseSince($request->header('X-App-Version-Code'));
            $configVersion = $deviceConfigVersion ?? self::parseSince($request->header('X-Config-Version'));

            Database::run(
                'INSERT INTO ' . Database::table('sync_events') . '
                    (install_id, version_code, config_version, event_type, result_code, resource, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())',
                [
                    $installId, $versionCode, $configVersion,
                    substr($eventType, 0, 24), substr($resultCode, 0, 16), substr($resource, 0, 40),
                ]
            );
            Database::run(
                'INSERT INTO ' . Database::table('anonymous_app_versions') . '
                    (install_id, version_code, config_version, last_seen_at)
                 VALUES (?, ?, ?, UTC_TIMESTAMP())
                 ON DUPLICATE KEY UPDATE
                    version_code   = COALESCE(VALUES(version_code), version_code),
                    config_version = COALESCE(VALUES(config_version), config_version),
                    last_seen_at   = VALUES(last_seen_at)',
                [$installId, $versionCode, $configVersion]
            );
        } catch (Throwable $e) {
            // Never fatal.
        }
    }
}
