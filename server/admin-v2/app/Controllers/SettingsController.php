<?php
/**
 * Settings for a two-person panel: your own name/email, change password,
 * and (Super Admin only) managing the single partner Admin — which sidebar pages
 * they may see and edit. There are no roles, no permission matrix and no 2FA.
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Auth;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Rbac;
use App\Core\Request;
use App\Core\Session;
use App\Core\Validator;

final class SettingsController extends Controller
{
    /** The sidebar pages a partner Admin can be granted. Super Admin always has all. */
    public const PAGES = [
        'dashboard'     => 'Dashboard',
        'offers'        => 'Offers',
        'billboards'    => 'Billboard adverts',
        'push'          => 'Instant push',
        'payments'      => 'Payments',
        'customers'     => 'Customers',
        'referrals'     => 'Referrals & commissions',
        'support'       => 'Support details',
        'config'        => 'App configuration',
        'versions'      => 'Updates & versions',
        'audit'         => 'Audit log',
    ];

    private function usersTable(): string { return Database::table('admin_users'); }

    /* -------------------------------------------------------------- profile */

    public function index(Request $request): void
    {
        $this->requireAuth();
        $this->view('settings/index', [
            'activeNav' => 'settings', 'pageTitle' => 'Settings',
            'user' => Auth::user(),
            'isSuperAdmin' => Auth::isSuperAdmin(),
        ]);
    }

    public function saveProfile(Request $request): void
    {
        Csrf::check($request);
        $this->requireAuth();
        $user = Auth::user();
        $name = trim((string) $request->post('name', ''));
        $email = strtolower(trim((string) $request->post('email', '')));
        $v = Validator::make(['name' => $name, 'email' => $email]);
        $v->validate(['name' => 'required|max:120', 'email' => 'required|email|max:190']);
        $dupe = Database::fetch('SELECT id FROM ' . $this->usersTable() . ' WHERE email = ? AND id <> ?', [$email, (int) $user['id']]);
        if ($dupe) { $v->add('email', 'That email is already in use.'); }
        if ($v->fails()) { Flash::error(implode(' ', array_values($v->firstErrors()))); $this->redirect('/settings'); }
        Database::run('UPDATE ' . $this->usersTable() . ' SET name=?, email=?, updated_at=UTC_TIMESTAMP() WHERE id=?', [$name, $email, (int) $user['id']]);
        Audit::log(['action' => 'profile.update', 'entity_type' => 'admin_user', 'entity_id' => (int) $user['id']]);
        Flash::success('Profile updated.');
        $this->redirect('/settings');
    }

    public function savePassword(Request $request): void
    {
        Csrf::check($request);
        $this->requireAuth();
        $user = Auth::user();
        $current = (string) $request->post('current_password', '');
        $new = (string) $request->post('new_password', '');
        if (!password_verify($current, (string) $user['password_hash'])) {
            Flash::error('Current password is incorrect.'); $this->redirect('/settings');
        }
        if (strlen($new) < 10) {
            Flash::error('New password must be at least 10 characters.'); $this->redirect('/settings');
        }
        Database::run('UPDATE ' . $this->usersTable() . ' SET password_hash=?, updated_at=UTC_TIMESTAMP() WHERE id=?', [password_hash($new, PASSWORD_DEFAULT), (int) $user['id']]);
        Session::regenerate(); // rotate after a credential change
        Audit::log(['action' => 'password.change', 'entity_type' => 'admin_user', 'entity_id' => (int) $user['id']]);
        Flash::success('Password changed.');
        $this->redirect('/settings');
    }

    /* ------------------------------------------------- manage partner Admin */

    public function admins(Request $request): void
    {
        $this->requireAuth();
        Rbac::requireSuperAdmin();
        $this->view('settings/admins', [
            'activeNav' => 'settings', 'pageTitle' => 'Manage partner Admin',
            'admins' => Database::fetchAll('SELECT * FROM ' . $this->usersTable() . ' ORDER BY is_super_admin DESC, name'),
            'pages' => self::PAGES,
        ]);
    }

    public function saveAdmin(Request $request): void
    {
        Csrf::check($request);
        $this->requireAuth();
        Rbac::requireSuperAdmin();
        $id = (int) $request->post('id', 0);
        $isNew = $id === 0;
        $name = trim((string) $request->post('name', ''));
        $email = strtolower(trim((string) $request->post('email', '')));
        $password = (string) $request->post('password', '');
        $wantSuper = $request->post('is_super_admin') ? 1 : 0;

        // Keep only known page keys.
        $pages = array_values(array_intersect(
            array_map('strval', (array) $request->post('pages', [])),
            array_keys(self::PAGES)
        ));

        $v = Validator::make(['name' => $name, 'email' => $email]);
        $v->validate(['name' => 'required|max:120', 'email' => 'required|email|max:190']);
        if ($isNew && strlen($password) < 10) { $v->add('password', 'Set an initial password (min 10 chars).'); }
        $dupe = Database::fetch('SELECT id FROM ' . $this->usersTable() . ' WHERE email = ? AND id <> ?', [$email, $id]);
        if ($dupe) { $v->add('email', 'Email already in use.'); }
        if ($v->fails()) { Flash::error(implode(' ', array_values($v->firstErrors()))); $this->redirect('/settings/admins'); }

        $allowed = $wantSuper ? null : json_encode($pages);

        if ($isNew) {
            Database::run(
                'INSERT INTO ' . $this->usersTable() . ' (name, email, password_hash, is_super_admin, allowed_pages, status, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())',
                [$name, $email, password_hash($password, PASSWORD_DEFAULT), $wantSuper, $allowed]
            );
            $id = (int) Database::pdo()->lastInsertId();
        } else {
            if ($password !== '' && strlen($password) >= 10) {
                Database::run('UPDATE ' . $this->usersTable() . ' SET password_hash=? WHERE id=?', [password_hash($password, PASSWORD_DEFAULT), $id]);
            }
            // Prevent removing the last Super Admin.
            if (!$wantSuper && $this->isLastSuperAdmin($id)) {
                Flash::error('Cannot remove the last Super Admin.'); $this->redirect('/settings/admins');
            }
            Database::run(
                'UPDATE ' . $this->usersTable() . ' SET name=?, email=?, is_super_admin=?, allowed_pages=?, updated_at=UTC_TIMESTAMP() WHERE id=?',
                [$name, $email, $wantSuper, $allowed, $id]
            );
        }
        Rbac::invalidate();
        Audit::log(['action' => $isNew ? 'admin.create' : 'admin.update', 'entity_type' => 'admin_user', 'entity_id' => $id, 'after' => ['name' => $name, 'email' => $email, 'super' => $wantSuper]]);
        Flash::success('Administrator saved.');
        $this->redirect('/settings/admins');
    }

    public function disableAdmin(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->requireAuth();
        Rbac::requireSuperAdmin();
        $id = (int) $id;
        if ($id === Auth::id()) { Flash::error('You cannot disable your own account.'); $this->redirect('/settings/admins'); }
        if ($this->isLastSuperAdmin($id)) { Flash::error('Cannot disable the last Super Admin.'); $this->redirect('/settings/admins'); }
        Database::run('UPDATE ' . $this->usersTable() . ' SET status=0, updated_at=UTC_TIMESTAMP() WHERE id=?', [$id]);
        Audit::log(['action' => 'admin.disable', 'entity_type' => 'admin_user', 'entity_id' => $id]);
        Flash::success('Administrator disabled and signed out.');
        $this->redirect('/settings/admins');
    }

    /* -------------------------------------------------------------- helpers */

    private function isLastSuperAdmin(int $id): bool
    {
        $supers = (int) (Database::scalar('SELECT COUNT(*) FROM ' . $this->usersTable() . ' WHERE is_super_admin = 1 AND status = 1') ?? 0);
        $isSuper = (int) (Database::scalar('SELECT is_super_admin FROM ' . $this->usersTable() . ' WHERE id = ?', [$id]) ?? 0) === 1;
        return $isSuper && $supers <= 1;
    }
}
