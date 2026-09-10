<?php
/**
 * Danger zone — the global service lock.
 *
 * SUPER ADMIN ONLY. Every action here calls Rbac::requireSuperAdmin(), so a partner
 * Admin gets the 403 page even if they type the URL: the page key is never added to
 * SettingsController::PAGES, which means it cannot be granted to anyone.
 *
 * Turning the switch on refuses every app-facing request on this server immediately —
 * no publish, no deploy and no new app build is involved. Turning it off restores
 * service just as immediately. See App\Services\ServiceLock for what is and is not
 * covered (Safaricom's payment callbacks are deliberately never blocked).
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Csrf;
use App\Core\Flash;
use App\Core\Rbac;
use App\Core\Request;
use App\Services\ServiceLock;

final class DangerZoneController extends Controller
{
    /** Highest amount the form will accept, so a slipped keystroke cannot store nonsense. */
    private const MAX_AMOUNT = 100000000;

    public function index(Request $request): void
    {
        $this->requireAuth();
        Rbac::requireSuperAdmin();

        $this->view('danger/index', [
            'activeNav'      => 'danger',
            'pageTitle'      => 'Danger zone',
            'lock'           => ServiceLock::state(),
            'defaultReason'  => ServiceLock::DEFAULT_REASON,
        ]);
    }

    public function save(Request $request): void
    {
        Csrf::check($request);
        $this->requireAuth();
        Rbac::requireSuperAdmin();

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
