<?php
/**
 * Danger zone — the global service lock.
 *
 * SUPER ADMIN ONLY, and INVISIBLE to everyone else.
 *
 * A partner Admin must not merely be refused this page — they must not be able to learn
 * that it exists. So both actions answer a plain 404 (the same page a typo produces)
 * rather than the 403 "you do not have permission" screen, which would confirm the URL
 * is real and name the permission it wants. The page key is also never added to
 * SettingsController::PAGES, so it cannot be granted to anyone, and the sidebar item is
 * only rendered for a Super Admin.
 *
 * The one thing a partner Admin does see is the dashboard notice while the lock is on —
 * the warning that the app is blocked, with no hint of where the switch lives.
 *
 * Turning the switch on refuses every app-facing request on this server immediately —
 * no publish, no deploy and no new app build is involved. Turning it off restores
 * service just as immediately. See App\Services\ServiceLock for what is and is not
 * covered (Safaricom's payment callbacks are deliberately never blocked).
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Auth;
use App\Core\Csrf;
use App\Core\Flash;
use App\Core\Request;
use App\Core\Response;
use App\Services\ServiceLock;

final class DangerZoneController extends Controller
{
    /** Highest amount the form will accept, so a slipped keystroke cannot store nonsense. */
    private const MAX_AMOUNT = 100000000;

    /**
     * Signed in, and a Super Admin — or this page does not exist.
     *
     * requireAuth() sends a signed-out visitor to the login page exactly as every other
     * admin route does, so that step reveals nothing. A signed-in partner Admin gets a
     * 404: indistinguishable from a mistyped URL.
     */
    private function requireSuperAdminOrHide(): void
    {
        $this->requireAuth();
        if (!Auth::isSuperAdmin()) {
            Response::notFound();
        }
    }

    public function index(Request $request): void
    {
        $this->requireSuperAdminOrHide();

        $this->view('danger/index', [
            'activeNav'      => 'danger',
            'pageTitle'      => 'Danger zone',
            'lock'           => ServiceLock::state(),
            'defaultReason'  => ServiceLock::DEFAULT_REASON,
        ]);
    }

    public function save(Request $request): void
    {
        // Hide BEFORE the CSRF check, so a partner Admin gets a uniform 404 here rather
        // than a 419 that would distinguish this route from one that does not exist.
        // The check reads the session and the user row only — it changes nothing.
        $this->requireSuperAdminOrHide();
        Csrf::check($request);

        $before = ServiceLock::state();

        $enabled = (string) $request->post('enabled', '0') === '1';
        $amount  = (int) preg_replace('/\D/', '', (string) $request->post('amount', '0'));
        $reason  = trim((string) $request->post('reason', ''));

        if ($amount > self::MAX_AMOUNT) {
            Flash::error('That amount looks wrong. Enter the outstanding balance in whole shillings.');
            $this->redirect('/danger-zone');
        }

        ServiceLock::save($enabled, $amount, $reason);
        $after = ServiceLock::state();

        // Audited like any other state change: who flipped it, when, and to what.
        Audit::log([
            'action'      => $enabled ? 'service_lock.enable' : 'service_lock.disable',
            'module'      => 'service_lock',
            'entity_type' => 'service_lock',
            'entity_id'   => 'global',
            'before'      => ['enabled' => $before['enabled'], 'amount' => $before['amount']],
            'after'       => ['enabled' => $after['enabled'], 'amount' => $after['amount']],
            'reason'      => $after['reason'],
        ]);

        Flash::success($enabled
            ? 'Service lock is ON. Every app and server request is now refused with “Request Denied”.'
            : 'Service lock is OFF. The app and the server are working normally again.');
        $this->redirect('/danger-zone');
    }
}
