<?php
/**
 * GET get_config.php — seller config the app syncs when online and caches for
 * offline use (Till, Paybill, support number/WhatsApp).
 *   Header: X-App-Key: <shared secret>
 *
 * Response (JSON):
 *   { tillNumber, paybillNumber, supportNumber, supportWhatsapp, updatedAt }
 *
 * Values come from the `settings` table (managed from the admin panel). These are the
 * OFFLINE Till/Paybill shown to customers — NOT the server-side STK shortcode used to
 * initiate payments. If a key is missing, it falls back to blank so nothing fake is shown.
 */

$config = require __DIR__ . '/config.php';
require __DIR__ . '/lib.php';

require_app_key($config);
// Global service lock (Admin V2 -> Danger zone). Refuses with 503 "Request Denied".
deny_if_service_locked($config);

// Offline display values come from the settings table; blank until the admin sets them.
$out = [
    'tillNumber'      => (string) ($config['till_number'] ?? ''),
    'paybillNumber'   => (string) ($config['paybill_number'] ?? ''),
    'supportNumber'   => (string) ($config['support_number'] ?? ''),
    'supportWhatsapp' => (string) ($config['support_whatsapp'] ?? ''),
    'updatedAt'       => null,
];

try {
    $pdo = require __DIR__ . '/db.php';
    $snap = published_snapshot($pdo);
    if ($snap !== null && isset($snap['support'])) {
        // Serve the OFFLINE Till/Paybill + support contacts the owner published in the admin.
        $s = $snap['support'];
        $out['tillNumber']      = (string) ($s['tillNumber'] ?? '');
        $out['paybillNumber']   = (string) ($s['paybillNumber'] ?? '');
        $out['supportNumber']   = (string) ($s['supportNumber'] ?? '');
        $out['supportWhatsapp'] = (string) ($s['supportWhatsapp'] ?? '');
        $out['updatedAt']       = $snap['publishedAt'] ?? null;
    } else {
        // Legacy fallback: the unprefixed key/value settings table.
        $rows = $pdo->query('SELECT skey, svalue, updated_at FROM settings')->fetchAll();
        $latest = null;
        foreach ($rows as $row) {
            switch ($row['skey']) {
                case 'till_number':      $out['tillNumber'] = $row['svalue']; break;
                case 'paybill_number':   $out['paybillNumber'] = $row['svalue']; break;
                case 'support_number':   $out['supportNumber'] = $row['svalue']; break;
                case 'support_whatsapp': $out['supportWhatsapp'] = $row['svalue']; break;
            }
            if ($latest === null || $row['updated_at'] > $latest) {
                $latest = $row['updated_at'];
            }
        }
        $out['updatedAt'] = $latest;
    }
} catch (Throwable $e) {
    // Nothing available yet → the config.php defaults above still answer.
}

json_out($out);
