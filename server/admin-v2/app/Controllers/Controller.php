<?php
/**
 * Base controller. Provides authentication/permission guards, view rendering with the
 * shared shell data (current user, sidebar, publish status), and redirect helpers.
 */

namespace App\Controllers;

use App\Core\Auth;
use App\Core\Flash;
use App\Core\Rbac;
use App\Core\Request;
use App\Core\Response;
use App\Core\Session;
use App\Core\View;
use App\Services\PublishingService;

abstract class Controller
{
    /** Require a signed-in admin, else send to login. Call at the top of protected actions. */
    protected function requireAuth(): void
    {
        if (!Auth::check() || Auth::user() === null) {
            Session::set('_intended', (new Request())->path());
            Response::redirect('/login');
        }
    }

    protected function guard(string $permission): void
    {
        $this->requireAuth();
        Rbac::require($permission);
    }

    /** Render a page inside the admin shell, injecting shared chrome data. */
    protected function view(string $template, array $data = [], int $code = 200): void
    {
        $shell = [
            'authUser'      => Auth::user(),
            'isSuperAdmin'  => Auth::isSuperAdmin(),
            'flashes'       => Flash::take(),
            'publishStatus' => PublishingService::status(),
            'activeNav'     => $data['activeNav'] ?? '',
            'pageTitle'     => $data['pageTitle'] ?? 'Skylink Bingwa Admin',
        ];
        // Note: repopulation data (_old) is consumed and cleared by the form view itself,
        // not here — clearing before render would wipe it before the form can read it.
        View::renderTo($template, array_merge($shell, $data), 'layout', $code);
    }

    protected function redirect(string $to): void
    {
        Response::redirect($to);
    }

    /** Redirect back to the referring page (or a fallback), keeping submitted values. */
    protected function back(string $fallback = '/', array $keepOld = []): void
    {
        if ($keepOld !== []) {
            Flash::keepOld($keepOld);
        }
        $ref = (string) ($_SERVER['HTTP_REFERER'] ?? '');
        $base = Request::basePath();
        if ($ref !== '' && ($base === '' || strpos(parse_url($ref, PHP_URL_PATH) ?? '', $base) === 0)) {
            Response::redirect($ref);
        }
        Response::redirect($fallback);
    }

    protected function json(array $data, int $code = 200): void
    {
        Response::json($data, $code);
    }
}
