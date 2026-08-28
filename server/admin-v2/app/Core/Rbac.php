<?php
/**
 * Simple two-role access control, enforced ON THE SERVER for every request.
 * Hiding a sidebar item is never authorisation — controllers call Rbac::require('perm').
 *
 * There are only two kinds of admin:
 *   - Super Admin (is_super_admin = 1): full control of every page.
 *   - Admin: can only reach the sidebar pages listed in admin_users.allowed_pages.
 *
 * Controllers still call guard('offers.view') etc.; the old dotted permission codes
 * are mapped down to a single page key (offers.* -> 'offers') so no controller needs
 * to change. There is no granular permission matrix any more.
 */

namespace App\Core;

final class Rbac
{
    private static ?array $cache = null;

    /** Sidebar page keys the current user may access ('*' means every page). */
    public static function permissions(): array
    {
        if (self::$cache !== null) {
            return self::$cache;
        }
        $user = Auth::user();
        if (!$user) {
            return self::$cache = [];
        }
        if ((int) $user['is_super_admin'] === 1) {
            return self::$cache = ['*'];
        }
        $pages = json_decode((string) ($user['allowed_pages'] ?? '[]'), true);
        return self::$cache = is_array($pages) ? array_values(array_map('strval', $pages)) : [];
    }

    /** Map an old permission code (or a bare page key) to the page it belongs to. */
    private static function pageForCode(string $code): string
    {
        $prefix = explode('.', $code, 2)[0];
        static $map = [
            'dashboard'     => 'dashboard',
            'offers'        => 'offers',
            'billboards'    => 'billboards',
            'notifications' => 'push',
            'push'          => 'push',
            'referrals'     => 'referrals',
            'payments'      => 'payments',
            'support'       => 'support',
            'config'        => 'config',
            'releases'      => 'versions',
            'publish'       => 'versions',
            'rollback'      => 'versions',
            'audit'         => 'audit',
            'admins'        => 'settings',
        ];
        return $map[$prefix] ?? $prefix;
    }

    public static function can(string $permission): bool
    {
        $perms = self::permissions();
        if (in_array('*', $perms, true)) {
            return true;
        }
        return in_array(self::pageForCode($permission), $perms, true);
    }

    public static function canAny(array $permissions): bool
    {
        foreach ($permissions as $p) {
            if (self::can($p)) {
                return true;
            }
        }
        return false;
    }

    /** Abort with 403 unless the current user may reach the page. */
    public static function require(string $permission): void
    {
        if (!self::can($permission)) {
            self::deny($permission);
        }
    }

    public static function requireSuperAdmin(): void
    {
        if (!Auth::isSuperAdmin()) {
            self::deny('super-admin');
        }
    }

    private static function deny(string $permission): void
    {
        $html = View::render('errors/forbidden', ['permission' => $permission], null);
        Response::html($html, 403);
    }

    public static function invalidate(): void
    {
        self::$cache = null;
    }
}
