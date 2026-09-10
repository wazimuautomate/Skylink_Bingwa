<?php
/**
 * GET get_offers.php — the catalogue the app syncs when online and caches offline.
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON): { offers: [ { id, category, name, price, validity, band, dailyRule,
 *                                  availableFrom, availableTo } ] }
 *
 * availableFrom/availableTo are the time-of-day window Safaricom sells the offer in,
 * "HH:MM" in Nairobi time. Both empty = sold at any hour. The app shows the window on
 * every offer card and refuses checkout outside it; stk.php refuses it server-side.
 * Only active offers are returned. Managed from the admin panel.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';

require_app_key($config);
// Global service lock (Admin V2 -> Danger zone). Refuses with 503 "Request Denied".
deny_if_service_locked($config);

$offers = [];
try {
    $pdo = require __DIR__ . '/db.php';
    $snap = published_snapshot($pdo);
    if ($snap !== null && !empty($snap['offers'])) {
        // Serve exactly what the owner published in the admin.
        foreach ($snap['offers'] as $o) {
            $offers[] = [
                'id'        => $o['id'] ?? '',
                'category'  => $o['category'] ?? '',
                'name'      => $o['name'] ?? '',
                'price'     => (int) ($o['price'] ?? 0),
                'validity'  => $o['validity'] ?? '',
                'band'      => $o['band'] ?? '',
                'dailyRule' => $o['dailyRule'] ?? '',
                'availableFrom' => hhmm_or_empty($o['availableFrom'] ?? null),
                'availableTo'   => hhmm_or_empty($o['availableTo'] ?? null),
            ];
        }
    } else {
        // Legacy fallback: the unprefixed offers table.
        // available_from/available_to are optional on the legacy table (older
        // installs never had them), so a missing column must not blank the list.
        try {
            $rows = $pdo->query(
                'SELECT offer_id, category, name, price, validity, band, daily_rule,
                        available_from, available_to
                   FROM offers WHERE active = 1 ORDER BY sort_order, category, price'
            )->fetchAll();
        } catch (Throwable $legacy) {
            $rows = $pdo->query(
                'SELECT offer_id, category, name, price, validity, band, daily_rule
                   FROM offers WHERE active = 1 ORDER BY sort_order, category, price'
            )->fetchAll();
        }
        foreach ($rows as $r) {
            $offers[] = [
                'id'        => $r['offer_id'],
                'category'  => $r['category'],
                'name'      => $r['name'],
                'price'     => (int) $r['price'],
                'validity'  => $r['validity'],
                'band'      => $r['band'],
                'dailyRule' => $r['daily_rule'],
                'availableFrom' => hhmm_or_empty($r['available_from'] ?? null),
                'availableTo'   => hhmm_or_empty($r['available_to'] ?? null),
            ];
        }
    }
} catch (Throwable $e) {
    // Nothing available yet → empty list; the app keeps its cached/local catalogue.
    $offers = [];
}

json_out(['offers' => $offers]);
