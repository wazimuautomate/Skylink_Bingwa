<?php
/**
 * Migration runner. Applies every database/migrations/*.sql file that has not run yet,
 * in filename order, recording each in `mb_migrations`. Idempotent and safe to re-run.
 *
 * It runs BY ITSELF: App\Core\Installer::autoProvision() is invoked from index.php on
 * every request and always calls Migrator::run(), so loading any admin page applies
 * whatever is pending. Nothing needs to be triggered by hand.
 *
 * It can also be run directly from the CLI (cPanel Terminal / SSH):
 *   php database/migrate.php
 *
 * NOTE: there is deliberately no /migrate route and no MigrateController — this comment
 * used to claim there was, and sent people to a 404.
 *
 * Migration files are plain SQL; statements are separated by a line that is exactly
 * "-- @@" so multi-statement files apply cleanly on shared hosting.
 */

namespace App\Database;

use App\Core\Config;
use App\Core\Database;
use Throwable;

final class Migrator
{
    /** @return array{applied:string[], skipped:string[], error:?string} */
    public static function run(): array
    {
        $applied = [];
        $skipped = [];
        try {
            $pdo = Database::pdo();
            $mig = Database::table('migrations');
            $pdo->exec("CREATE TABLE IF NOT EXISTS {$mig} (
                id INT AUTO_INCREMENT PRIMARY KEY,
                filename VARCHAR(191) NOT NULL UNIQUE,
                applied_at DATETIME NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            $done = [];
            foreach ($pdo->query("SELECT filename FROM {$mig}") as $r) {
                $done[$r['filename']] = true;
            }

            $dir = __DIR__ . '/migrations';
            $files = glob($dir . '/*.sql') ?: [];
            sort($files);

            foreach ($files as $file) {
                $name = basename($file);
                if (isset($done[$name])) {
                    $skipped[] = $name;
                    continue;
                }
                $sql = file_get_contents($file) ?: '';
                // Interpolate the table prefix placeholder {p} used inside migration SQL.
                $sql = str_replace('{p}', Database::prefix(), $sql);
                foreach (self::split($sql) as $stmt) {
                    $pdo->exec($stmt);
                }
                $ins = $pdo->prepare("INSERT INTO {$mig} (filename, applied_at) VALUES (?, UTC_TIMESTAMP())");
                $ins->execute([$name]);
                $applied[] = $name;
            }
            return ['applied' => $applied, 'skipped' => $skipped, 'error' => null];
        } catch (Throwable $e) {
            return ['applied' => $applied, 'skipped' => $skipped, 'error' => $e->getMessage()];
        }
    }

    /** Split a migration file into statements on lines that are exactly "-- @@". */
    private static function split(string $sql): array
    {
        $parts = preg_split('/^\s*--\s*@@\s*$/m', $sql) ?: [];
        $out = [];
        foreach ($parts as $part) {
            $trimmed = trim($part);
            if ($trimmed !== '' && !preg_match('/^(--.*\s*)+$/', $trimmed)) {
                $out[] = $trimmed;
            }
        }
        return $out;
    }
}

// Allow running directly from the CLI without the web front controller.
if (PHP_SAPI === 'cli' && realpath($argv[0] ?? '') === realpath(__FILE__)) {
    require __DIR__ . '/../app/Core/Autoloader.php';
    \App\Core\Autoloader::register(__DIR__ . '/../app');
    Config::load(__DIR__ . '/../config/config.php');
    Database::boot();
    $result = Migrator::run();
    if ($result['error']) {
        fwrite(STDERR, "Migration failed: {$result['error']}\n");
        exit(1);
    }
    echo 'Applied: ' . (implode(', ', $result['applied']) ?: '(none)') . "\n";
    echo 'Already up to date: ' . count($result['skipped']) . " file(s)\n";
    exit(0);
}
