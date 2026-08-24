<?php
/**
 * Idempotent seeder. Installs the permission set, default roles, single-row config
 * rows, the shipped catalogue/templates, and the first Super Admin. Safe to re-run:
 * it never overwrites offers/templates/config an administrator has already edited.
 *
 * CLI:  php database/seed.php
 * Web:  handled by InstallController after migrations (Super Admin bootstrap).
 */

namespace App\Database;

use App\Core\Config;
use App\Core\Database;
use Throwable;

final class Seeder
{
    /** @return array{ok:bool, messages:string[], generatedPassword:?string, error:?string} */
    public static function run(): array
    {
        $messages = [];
        $generatedPassword = null;
        try {
            $data = require __DIR__ . '/seed_data.php';

            self::seedPermissions($data['permissions'], $messages);
            self::seedRoles($data['roles'], $messages);
            self::seedSingletons($data, $messages);
            self::seedCatalogue($data, $messages);
            self::seedTemplates($data, $messages);
            $generatedPassword = self::seedSuperAdmin($messages);
            self::publishBaseline($messages);

            return ['ok' => true, 'messages' => $messages, 'generatedPassword' => $generatedPassword, 'error' => null];
        } catch (Throwable $e) {
            return ['ok' => false, 'messages' => $messages, 'generatedPassword' => $generatedPassword, 'error' => $e->getMessage()];
        }
    }

    private static function seedPermissions(array $perms, array &$msg): void
    {
        $t = Database::table('permissions');
        $stmt = Database::pdo()->prepare(
            "INSERT INTO {$t} (perm_key, perm_group, label) VALUES (?, ?, ?)
             ON DUPLICATE KEY UPDATE perm_group = VALUES(perm_group), label = VALUES(label)"
        );
        foreach ($perms as $key => [$group, $label]) {
            $stmt->execute([$key, $group, $label]);
        }
        $msg[] = count($perms) . ' permissions ensured.';
    }

    private static function seedRoles(array $roles, array &$msg): void
    {
        $rt = Database::table('roles');
        $rpt = Database::table('role_permissions');
        $pt = Database::table('permissions');
        foreach ($roles as $key => [$name, $desc, $isSystem, $permKeys]) {
            $existing = Database::fetch("SELECT id FROM {$rt} WHERE role_key = ?", [$key]);
            if ($existing) {
                continue; // don't overwrite an operator's permission edits
            }
            Database::run(
                "INSERT INTO {$rt} (role_key, name, description, is_system, created_at)
                 VALUES (?, ?, ?, ?, UTC_TIMESTAMP())",
                [$key, $name, $desc, $isSystem]
            );
            $roleId = (int) Database::pdo()->lastInsertId();
            foreach ($permKeys as $pk) {
                $pid = Database::scalar("SELECT id FROM {$pt} WHERE perm_key = ?", [$pk]);
                if ($pid) {
                    Database::run(
                        "INSERT IGNORE INTO {$rpt} (role_id, permission_id) VALUES (?, ?)",
                        [$roleId, (int) $pid]
                    );
                }
            }
            $msg[] = "Role '{$name}' created with " . count($permKeys) . ' permissions.';
        }
    }

    private static function seedSingletons(array $data, array &$msg): void
    {
        // support_config id=1
        $sc = Database::table('support_config');
        if (!Database::fetch("SELECT id FROM {$sc} WHERE id = 1")) {
            $c = $data['support_config'];
            Database::run(
                "INSERT INTO {$sc}
                 (id, till_number, paybill_number, support_number, support_whatsapp,
                  offline_self_instructions, offline_other_instructions, support_banner, working_hours,
                  row_version, updated_at, updated_by)
                 VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), 'seed')",
                [$c['till_number'], $c['paybill_number'], $c['support_number'], $c['support_whatsapp'],
                 $c['offline_self_instructions'], $c['offline_other_instructions'], $c['support_banner'], $c['working_hours']]
            );
            $msg[] = 'Support/payment config seeded.';
        }

        // app_config id=1
        $ac = Database::table('app_config');
        if (!Database::fetch("SELECT id FROM {$ac} WHERE id = 1")) {
            $a = $data['app_config'];
            Database::run(
                "INSERT INTO {$ac}
                 (id, maintenance_mode, sync_interval_minutes, general_support_message,
                  row_version, updated_at, updated_by)
                 VALUES (1, ?, ?, ?, 1, UTC_TIMESTAMP(), 'seed')",
                [$a['maintenance_mode'], $a['sync_interval_minutes'], $a['general_support_message']]
            );
            $msg[] = 'App configuration seeded.';
        }

        // app_versions: one active row
        $av = Database::table('app_versions');
        if (!Database::fetch("SELECT id FROM {$av} LIMIT 1")) {
            $v = $data['app_version'];
            Database::run(
                "INSERT INTO {$av}
                 (latest_version_code, latest_version_name, min_supported_version_code, mandatory,
                  play_store_url, apk_url, apk_sha256, rollout_percent, release_notes, status,
                  row_version, created_at, updated_at, updated_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'seed')",
                [$v['latest_version_code'], $v['latest_version_name'], $v['min_supported_version_code'],
                 $v['mandatory'], $v['play_store_url'], $v['apk_url'], $v['apk_sha256'],
                 $v['rollout_percent'], $v['release_notes']]
            );
            $msg[] = 'App version rule seeded.';
        }
    }

    private static function seedCatalogue(array $data, array &$msg): void
    {
        $ot = Database::table('offers');
        if (Database::fetch("SELECT id FROM {$ot} LIMIT 1")) {
            return; // catalogue already present — don't clobber
        }
        $stmt = Database::pdo()->prepare(
            "INSERT INTO {$ot}
             (offer_id, category, name, price, validity, band, daily_rule, offline_eligible,
              status, sort_hint, row_version, created_at, updated_at, updated_by)
             VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'active', ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), 'seed')"
        );
        $i = 0;
        foreach ($data['offers'] as [$id, $cat, $name, $price, $validity, $band, $once]) {
            $rule = $once ? 'ONCE_PER_RECIPIENT_PER_DAY' : 'MULTIPLE_PER_DAY';
            $stmt->execute([$id, $cat, $name, $price, $validity, $band, $rule, ++$i * 10]);
        }
        $msg[] = count($data['offers']) . ' offers seeded.';
    }

    private static function seedTemplates(array $data, array &$msg): void
    {
        $st = Database::table('message_sender_ids');
        $senderStmt = Database::pdo()->prepare(
            "INSERT IGNORE INTO {$st} (sender_id, normalised, note, created_at)
             VALUES (?, ?, ?, UTC_TIMESTAMP())"
        );
        foreach ($data['sender_ids'] as [$sid, $note]) {
            $senderStmt->execute([$sid, strtoupper(trim($sid)), $note]);
        }

        $msg[] = count($data['sender_ids']) . ' sender IDs ensured.';
    }

    /** Create the first Super Admin. Password from config bootstrap_admin, else generated. */
    private static function seedSuperAdmin(array &$msg): ?string
    {
        $t = Database::table('admin_users');
        if (Database::fetch("SELECT id FROM {$t} LIMIT 1")) {
            return null; // an admin already exists
        }
        $boot = Config::get('bootstrap_admin', []);
        $name = (string) ($boot['name'] ?? 'Owner');
        $email = strtolower((string) ($boot['email'] ?? 'owner@skylinkbingwa.local'));
        $password = (string) ($boot['password'] ?? '');
        $generated = null;
        if ($password === '' || strlen($password) < 10) {
            $password = self::randomPassword();
            $generated = $password;
        }
        Database::run(
            "INSERT INTO {$t}
             (name, email, password_hash, is_super_admin, status, totp_enabled, created_at, updated_at)
             VALUES (?, ?, ?, 1, 1, 0, UTC_TIMESTAMP(), UTC_TIMESTAMP())",
            [$name, $email, password_hash($password, PASSWORD_DEFAULT)]
        );
        $msg[] = "Super Admin created: {$email}";
        return $generated;
    }

    /**
     * Publish an initial baseline so the seeded catalogue/config is treated as LIVE data,
     * not a pile of pending "drafts". After this, the admin shows 0 pending changes until
     * someone actually edits something.
     */
    private static function publishBaseline(array &$msg): void
    {
        $rt = Database::table('configuration_releases');
        if (!Database::tableExists('configuration_releases') || Database::fetch("SELECT id FROM {$rt} LIMIT 1")) {
            return; // nothing to publish into, or a release already exists
        }
        $r = \App\Services\PublishingService::publish('Initial baseline (install).');
        $msg[] = $r['ok']
            ? 'Baseline configuration published (v' . $r['version'] . ').'
            : 'Baseline publish skipped: ' . implode(' ', $r['errors']);
    }

    private static function randomPassword(): string
    {
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';
        $out = '';
        for ($i = 0; $i < 16; $i++) {
            $out .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        return $out;
    }
}

if (PHP_SAPI === 'cli' && realpath($argv[0] ?? '') === realpath(__FILE__)) {
    require __DIR__ . '/../app/Core/Autoloader.php';
    \App\Core\Autoloader::register(__DIR__ . '/../app');
    Config::load(__DIR__ . '/../config/config.php');
    Database::boot();
    $r = Seeder::run();
    if (!$r['ok']) {
        fwrite(STDERR, "Seed failed: {$r['error']}\n");
        exit(1);
    }
    foreach ($r['messages'] as $m) {
        echo '- ' . $m . "\n";
    }
    if ($r['generatedPassword']) {
        echo "\n*** Super Admin password (shown once): {$r['generatedPassword']}\n";
    }
    exit(0);
}
