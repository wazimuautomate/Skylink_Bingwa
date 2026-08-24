<?php
/**
 * Idempotent importer: maps the LEGACY tables (offers / settings / templates used by
 * server/mybingwa-api) into the Admin V2 working tables (mb_offers, mb_support_config,
 * mb_message_templates, mb_message_sender_ids). It never modifies the legacy tables.
 *
 *   php bin/import_legacy.php            # DRY RUN — reports what it would do
 *   php bin/import_legacy.php --apply    # perform the import
 *
 * Safe to re-run: rows are upserted by their stable key.
 */

namespace App\Bin;

use App\Core\Config;
use App\Core\Database;
use Throwable;

require __DIR__ . '/../app/Core/Autoloader.php';
\App\Core\Autoloader::register(__DIR__ . '/../app');
Config::load(__DIR__ . '/../config/config.php');
Database::boot();

$apply = in_array('--apply', $argv, true);
$mode = $apply ? 'APPLY' : 'DRY RUN';
echo "Skylink Bingwa legacy import — {$mode}\n";
echo str_repeat('-', 48) . "\n";

function legacyExists(string $table): bool
{
    $row = Database::fetch(
        "SELECT COUNT(*) AS c FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
        [$table]
    );
    return (int) ($row['c'] ?? 0) > 0;
}

$report = ['offers' => 0, 'settings' => 0, 'templates' => 0, 'senders' => 0, 'skipped' => []];

try {
    /* ---- Offers ------------------------------------------------------------ */
    if (legacyExists('offers') && !isPrefixed('offers')) {
        $rows = Database::fetchAll('SELECT * FROM offers');
        foreach ($rows as $o) {
            $rule = ($o['daily_rule'] ?? '') === 'ONCE_PER_DAY' ? 'ONCE_PER_RECIPIENT_PER_DAY' : 'MULTIPLE_PER_DAY';
            $status = (int) ($o['active'] ?? 1) === 1 ? 'active' : 'draft';
            echo "  offer {$o['offer_id']} ({$o['category']} {$o['name']} KSh{$o['price']}) -> {$status}, {$rule}\n";
            if ($apply) {
                Database::run(
                    'INSERT INTO ' . Database::table('offers') . '
                        (offer_id, category, name, price, validity, band, daily_rule, offline_eligible,
                         status, sort_hint, row_version, created_at, updated_at, updated_by)
                     VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), \'legacy-import\')
                     ON DUPLICATE KEY UPDATE category=VALUES(category), name=VALUES(name), price=VALUES(price),
                        validity=VALUES(validity), band=VALUES(band), daily_rule=VALUES(daily_rule),
                        status=VALUES(status), sort_hint=VALUES(sort_hint), updated_at=UTC_TIMESTAMP(), updated_by=\'legacy-import\'',
                    [$o['offer_id'], $o['category'], $o['name'], (int) $o['price'], $o['validity'],
                     $o['band'] ?? 'Daily', $rule, $status, (int) ($o['sort_order'] ?? 0)]
                );
            }
            $report['offers']++;
        }
    } else {
        $report['skipped'][] = 'offers (legacy table not found or is the prefixed table)';
    }

    /* ---- Settings -> support_config ---------------------------------------- */
    if (legacyExists('settings') && !isPrefixed('settings')) {
        $map = [];
        foreach (Database::fetchAll('SELECT skey, svalue FROM settings') as $r) {
            $map[$r['skey']] = $r['svalue'];
        }
        if ($map) {
            echo "  settings -> support_config (till/paybill/support/whatsapp)\n";
            if ($apply) {
                Database::run(
                    'INSERT INTO ' . Database::table('support_config') . '
                        (id, till_number, paybill_number, support_number, support_whatsapp, row_version, updated_at, updated_by)
                     VALUES (1, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), \'legacy-import\')
                     ON DUPLICATE KEY UPDATE till_number=VALUES(till_number), paybill_number=VALUES(paybill_number),
                        support_number=VALUES(support_number), support_whatsapp=VALUES(support_whatsapp),
                        updated_at=UTC_TIMESTAMP(), updated_by=\'legacy-import\'',
                    [$map['till_number'] ?? '', $map['paybill_number'] ?? '', $map['support_number'] ?? '', $map['support_whatsapp'] ?? '']
                );
            }
            $report['settings'] = count($map);
        }
    } else {
        $report['skipped'][] = 'settings (legacy table not found)';
    }

    /* ---- Templates -> message_templates + sender_ids -----------------------
     * NOTE: this block targets the v1 mb_message_templates table because it exists only to
     * replay the one-off legacy cutover, which already happened on the live server. Safe to
     * re-run: rows are upserted by their stable key. */
    if (legacyExists('templates') && !isPrefixed('templates')) {
        $rows = Database::fetchAll('SELECT * FROM templates');
        foreach ($rows as $t) {
            $purpose = ($t['ttype'] ?? 'delivery') === 'low_balance' ? 'low_balance' : 'delivery';
            $status = (int) ($t['active'] ?? 1) === 1 ? 'active' : 'draft';
            echo "  template {$t['tkey']} ({$purpose}, {$t['sender_id']}) -> {$status}\n";
            if ($apply) {
                if (($t['sender_id'] ?? '') !== '') {
                    Database::run(
                        'INSERT IGNORE INTO ' . Database::table('message_sender_ids') . ' (sender_id, normalised, note, created_at)
                         VALUES (?, ?, \'imported\', UTC_TIMESTAMP())',
                        [$t['sender_id'], strtoupper($t['sender_id'])]
                    );
                    $report['senders']++;
                }
                Database::run(
                    'INSERT INTO ' . Database::table('message_templates') . '
                        (template_key, label, sender_id, purpose, category, pattern_type, pattern, status,
                         row_version, created_at, updated_at, updated_by)
                     VALUES (?, ?, ?, ?, ?, \'regex\', ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), \'legacy-import\')
                     ON DUPLICATE KEY UPDATE label=VALUES(label), sender_id=VALUES(sender_id), purpose=VALUES(purpose),
                        category=VALUES(category), pattern=VALUES(pattern), status=VALUES(status),
                        updated_at=UTC_TIMESTAMP(), updated_by=\'legacy-import\'',
                    [$t['tkey'], $t['label'] ?? $t['tkey'], $t['sender_id'] ?? '', $purpose, $t['category'] ?? 'DATA', $t['pattern'], $status]
                );
            }
            $report['templates']++;
        }
    } else {
        $report['skipped'][] = 'templates (legacy table not found)';
    }
} catch (Throwable $e) {
    fwrite(STDERR, "Import error: {$e->getMessage()}\n");
    exit(1);
}

echo str_repeat('-', 48) . "\n";
echo "Offers: {$report['offers']}, settings keys: {$report['settings']}, templates: {$report['templates']}, senders: {$report['senders']}\n";
foreach ($report['skipped'] as $s) {
    echo "  skipped: {$s}\n";
}
echo $apply ? "\nImport applied. Review in the admin, then Publish to push to the app.\n"
            : "\nDry run only. Re-run with --apply to import.\n";

/** Guard: the legacy names must not accidentally be our prefixed tables. */
function isPrefixed(string $name): bool
{
    return strncmp($name, Database::prefix(), strlen(Database::prefix())) === 0;
}
