<?php
/**
 * Zero-touch install. On a fresh (empty) database the very first request creates every
 * table, seeds the reference data and publishes a baseline — no phpMyAdmin, no manual SQL.
 *
 * On cPanel you still create the MySQL database + user once in the point-and-click MySQL
 * wizard and fill config/config.php (the DB user there cannot CREATE DATABASE). Everything
 * INSIDE the database is automatic from then on. Set bootstrap_admin.password in the config
 * for a fully silent install; otherwise a strong password is generated and written to
 * storage/first-login-password.txt (delete that file after your first login).
 */

namespace App\Core;

use Throwable;

final class Installer
{
    public static function autoProvision(): void
    {
        try {
            $fresh = !Database::tableExists('admin_users');
        } catch (Throwable $e) {
            return; // DB not reachable yet — the login/install page will guide the operator
        }

        $base = dirname(__DIR__, 2) . '/database';
        require_once $base . '/migrate.php';
        require_once $base . '/seed.php';

        try {
            // Always apply any pending migrations (idempotent) so both a fresh install AND
            // an upgrade of an existing install need no manual migrate step.
            $mig = \App\Database\Migrator::run();
            if ($mig['error']) {
                error_log('[skylinkbingwa-admin] auto-migrate failed: ' . $mig['error']);
                return;
            }
            // Seed reference data, the first Super Admin and the baseline publish only on a
            // brand-new database — never touch data an operator has already edited.
            if (!$fresh) {
                return;
            }
            $seed = \App\Database\Seeder::run();
            if (!$seed['ok']) {
                error_log('[skylinkbingwa-admin] auto-install seed failed: ' . $seed['error']);
                return;
            }
            if (!empty($seed['generatedPassword'])) {
                @file_put_contents(
                    dirname(__DIR__, 2) . '/storage/first-login-password.txt',
                    "Skylink Bingwa Admin — first Super Admin password (delete this file after logging in):\n"
                    . $seed['generatedPassword'] . "\n"
                );
            }
        } catch (Throwable $e) {
            error_log('[skylinkbingwa-admin] auto-install error: ' . $e->getMessage());
        }
    }
}
