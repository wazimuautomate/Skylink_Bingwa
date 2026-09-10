<?php
/**
 * GET get_billboards.php — the Home billboard promotions the app syncs when online
 * and caches offline.
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON): { billboards: [ { id, kind, priority, linkedOfferId, tag, headline,
 *   body, ctaLabel, ctaDestination, imageUrl, altText, audienceRule, frequencyCap,
 *   startsAt, endsAt } ] }
 * Serves exactly what the owner published in the admin. There is no legacy billboards
 * table here, so with no published snapshot (or no key) the list is empty and the app
 * keeps its cached/local billboards.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';

require_app_key($config);
// Global service lock (Admin V2 -> Danger zone). Refuses with 503 "Request Denied".
deny_if_service_locked($config);

$billboards = [];
try {
    $pdo = require __DIR__ . '/db.php';
    $snap = published_snapshot($pdo);
    if ($snap !== null && !empty($snap['billboards'])) {
        // Serve exactly what the owner published in the admin.
        $billboards = $snap['billboards'];
    }
} catch (Throwable $e) {
    // Nothing available yet → empty list; the app keeps its cached/local billboards.
    $billboards = [];
}

json_out(['billboards' => $billboards]);
