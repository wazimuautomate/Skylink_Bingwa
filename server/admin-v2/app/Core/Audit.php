<?php
/**
 * Append-only audit trail. Every state change records who did what to which entity,
 * with a before/after diff whose sensitive values are masked. Nothing here can be
 * edited or deleted from the UI. Never store passwords, tokens, PINs or secrets.
 */

namespace App\Core;

final class Audit
{
    /** Field names whose values are masked before being written to the diff. */
    private const SENSITIVE = [
        'password', 'password_hash', 'totp_secret', 'recovery_codes', 'passkey',
        'consumer_secret', 'consumer_key', 'app_key', 'callback_secret',
        'sms_userid', 'sms_password',
        'private_key', 'secret', 'db_pass',
    ];

    /**
     * @param array{
     *   action:string, entity_type?:string, entity_id?:string|int|null,
     *   before?:array|null, after?:array|null, reason?:string|null,
     *   version?:int|null, success?:bool
     * } $opts
     */
    public static function log(array $opts): void
    {
        $user = Auth::user();
        $before = self::mask($opts['before'] ?? null);
        $after  = self::mask($opts['after'] ?? null);
        $diff = self::diff($before, $after);

        Database::run(
            'INSERT INTO ' . Database::table('audit_logs') . '
                (actor_id, actor_name, actor_role, action, module, entity_type, entity_id,
                 before_json, after_json, diff_json, reason, release_version,
                 ip, user_agent, success, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())',
            [
                $user['id'] ?? null,
                $user['name'] ?? 'system',
                self::roleLabel($user),
                substr($opts['action'], 0, 80),
                substr((string) ($opts['module'] ?? self::moduleOf($opts['action'])), 0, 40),
                substr((string) ($opts['entity_type'] ?? ''), 0, 60),
                substr((string) ($opts['entity_id'] ?? ''), 0, 64),
                $before !== null ? json_encode($before) : null,
                $after !== null ? json_encode($after) : null,
                $diff !== [] ? json_encode($diff) : null,
                isset($opts['reason']) ? substr((string) $opts['reason'], 0, 500) : null,
                $opts['version'] ?? null,
                substr((string) (new Request())->ip(), 0, 45),
                substr((string) ($_SERVER['HTTP_USER_AGENT'] ?? ''), 0, 255),
                ($opts['success'] ?? true) ? 1 : 0,
            ]
        );
    }

    /**
     * The module an action belongs to, taken from the part before the first dot
     * ('offer.update' -> 'offer'). Stored as a column so the audit page can offer a
     * module filter without parsing strings in SQL.
     */
    public static function moduleOf(string $action): string
    {
        $head = explode('.', $action, 2)[0];
        return $head !== '' ? $head : 'system';
    }

    private static function roleLabel(?array $user): string
    {
        if (!$user) {
            return 'system';
        }
        return (int) $user['is_super_admin'] === 1 ? 'Super Admin' : 'Admin';
    }

    /** Replace sensitive values with a fixed mask, recursively. */
    public static function mask(?array $data): ?array
    {
        if ($data === null) {
            return null;
        }
        $out = [];
        foreach ($data as $key => $value) {
            if (is_string($key) && in_array(strtolower($key), self::SENSITIVE, true)) {
                $out[$key] = '••••••';
            } elseif (is_array($value)) {
                $out[$key] = self::mask($value);
            } else {
                $out[$key] = $value;
            }
        }
        return $out;
    }

    /** Compute changed keys between two (already masked) snapshots. */
    public static function diff(?array $before, ?array $after): array
    {
        if ($before === null || $after === null) {
            return [];
        }
        $changes = [];
        $keys = array_unique(array_merge(array_keys($before), array_keys($after)));
        foreach ($keys as $key) {
            $b = $before[$key] ?? null;
            $a = $after[$key] ?? null;
            if (json_encode($b) !== json_encode($a)) {
                $changes[$key] = ['from' => $b, 'to' => $a];
            }
        }
        return $changes;
    }
}
