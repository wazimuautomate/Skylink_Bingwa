<?php
/**
 * Instant push notifications (FCM HTTP v1).
 *
 * Lets an administrator compose a message and deliver it to every registered phone
 * immediately, instead of waiting for the offline-first app to pull its next sync.
 *
 * This controller deliberately uses the SAME core helpers as every other admin
 * module — Csrf::check($request), Validator::make()->validate([...]), Audit::log([...]),
 * Database::fetch()/run(). An earlier revision invented method names that do not exist
 * in these classes (Csrf::check(string), $v->rule(), $v->firstError(), Audit::log(named:),
 * Database::fetchOne(), Database::query()), which raised a fatal Error on every submit.
 * index.php catches Throwable and renders "Something went wrong. Please try again." —
 * so the real cause was invisible. Keep to the real APIs.
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Auth;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Request;
use App\Core\Validator;
use App\Services\FcmService;
use Throwable;

final class PushController extends Controller
{
    /** Screens the app can deep-link to when the notification is tapped. */
    private const ROUTES = ['notifications', 'home', 'offers', 'activity', 'help'];

    private function actor(): string
    {
        $user = Auth::user();
        return (string) ($user['name'] ?? 'admin');
    }

    /* ------------------------------------------------------------------ page */

    public function index(Request $request): void
    {
        $this->guard('notifications.create');

        $tokenCount = 0;
        $history = [];
        $historyError = null;

        try {
            $customers = Database::table('customers');
            $row = Database::fetch(
                "SELECT COUNT(DISTINCT fcm_token) AS c FROM {$customers}
                  WHERE fcm_token IS NOT NULL AND fcm_token <> ''"
            );
            $tokenCount = (int) ($row['c'] ?? 0);
        } catch (Throwable $e) {
            // The column only exists once migration 021 has run. Report it rather than
            // silently showing a confident "0", which reads as "nobody installed the app".
            $historyError = 'The customer table has no fcm_token column yet — run the pending database migrations.';
            error_log('[skylinkbingwa-admin] push token count failed: ' . $e->getMessage());
        }

        try {
            $broadcasts = Database::table('push_broadcasts');
            $history = Database::fetchAll("SELECT * FROM {$broadcasts} ORDER BY created_at DESC LIMIT 20");
        } catch (Throwable $e) {
            $historyError = $historyError
                ?? 'The push history table is missing — run the pending database migrations.';
            error_log('[skylinkbingwa-admin] push history failed: ' . $e->getMessage());
        }

        $this->view('push/index', [
            'pageTitle'     => 'Instant Push Notifications',
            'activeNav'     => 'push',
            'isConfigured'  => FcmService::isConfigured(),
            'configError'   => FcmService::configurationError(),
            'tokenCount'    => $tokenCount,
            'history'       => $history,
            'historyError'  => $historyError,
            'routes'        => self::ROUTES,
        ]);
    }

    /* ------------------------------------------------------------------ send */

    public function send(Request $request): void
    {
        Csrf::check($request);
        $this->guard('notifications.create');

        $input = [
            'title' => trim((string) $request->post('title', '')),
            'body'  => trim((string) $request->post('body', '')),
            'route' => trim((string) $request->post('route', 'notifications')),
        ];

        $v = Validator::make($input);
        $v->validate([
            'title' => 'required|maxlen:120',
            'body'  => 'required|maxlen:500',
        ]);
        if (!in_array($input['route'], self::ROUTES, true)) {
            $v->add('route', 'Choose a screen the app can actually open.');
        }

        if ($v->fails()) {
            $first = $v->firstErrors();
            Flash::error((string) (reset($first) ?: 'Please fill in the title and the message.'));
            $this->back('/push', $input);
            return;
        }

        $result = FcmService::broadcast($input['title'], $input['body'], $input['route'], $this->actor());

        // A push cannot be recalled. Once FCM has accepted it, an audit-table problem must
        // not turn the response into the generic 500 page — that would tell the operator
        // the send failed and invite them to send the whole broadcast a second time.
        try {
            Audit::log([
                'action'      => 'push.broadcast',
                'entity_type' => 'push_notification',
                'entity_id'   => (string) ($result['broadcast_id'] ?? 'fcm_broadcast'),
                'before'      => null,
                'after'       => [
                    'title'     => $input['title'],
                    'body'      => $input['body'],
                    'route'     => $input['route'],
                    'targets'   => $result['total_targets'],
                    'delivered' => $result['sent_count'],
                    'failed'    => $result['failed_count'],
                ],
                'success'     => $result['success'],
            ]);
        } catch (Throwable $e) {
            error_log('[skylinkbingwa-admin] push audit log failed: ' . $e->getMessage());
        }

        if ($result['success']) {
            if ($result['total_targets'] === 0) {
                // Nothing registered yet, but the all_users topic was accepted. Say exactly
                // that: FCM cannot tell us how many phones are subscribed to a topic, so
                // quoting a delivery count here would be inventing one.
                $msg = 'Push sent to the all_users topic. No device has registered its token'
                    . ' yet, so the exact number of phones reached is not known.';
            } else {
                $msg = "Push sent. Delivered to {$result['sent_count']} of {$result['total_targets']} device(s).";
                if ($result['failed_count'] > 0) {
                    $msg .= " {$result['failed_count']} failed";
                    if (($result['pruned_count'] ?? 0) > 0) {
                        $msg .= " ({$result['pruned_count']} stale token(s) removed)";
                    }
                    $msg .= '.';
                }
            }
            Flash::success($msg);
        } else {
            Flash::error('Could not send the push notification: ' . ($result['error'] ?? 'unknown error.'));
            $this->back('/push', $input);
            return;
        }

        $this->redirect('/push');
    }
}
