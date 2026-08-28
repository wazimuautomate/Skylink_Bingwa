<?php
/**
 * Skylink Bingwa Admin V2 — single front controller. Every request enters here (the
 * .htaccess routes all non-asset paths to this file). Bootstraps the kernel, then
 * dispatches to a controller.
 *
 * Deployment: upload this folder into public_html (e.g. public_html/admin) and open
 * https://your-domain/admin/. See docs/ADMIN_V2_DEPLOYMENT.md.
 */

declare(strict_types=1);

use App\Core\Autoloader;
use App\Core\Config;
use App\Core\Database;
use App\Core\Request;
use App\Core\Response;
use App\Core\Router;
use App\Core\Session;
use App\Core\View;
use App\Core\Auth;

require __DIR__ . '/app/Core/Autoloader.php';
Autoloader::register(__DIR__ . '/app');

// Store everything in UTC; display in Africa/Nairobi via helpers.
date_default_timezone_set('UTC');

Config::load(__DIR__ . '/config/config.php');
Database::boot();

// Zero-touch install: a fresh database provisions all tables + seed data on first hit
// (no phpMyAdmin, no manual SQL). No-op once installed. See App\Core\Installer.
App\Core\Installer::autoProvision();

$request = new Request();
$isApi = strpos($request->path(), '/api/') === 0;

// Sessions + security headers are for the admin UI, not the stateless public API.
if (!$isApi) {
    Session::start();
    Response::securityHeaders();
    View::boot(__DIR__ . '/app/Views');
    View::share('basePath', Request::basePath());
}

$router = new Router();

/* --------------------------------------------------------------- public API */
// One read-only endpoint the Android app polls: it returns the latest published
// offers, adverts, templates, support details, app config and update info.
$router->get('/api/app-data', [App\Controllers\Api\SyncController::class, 'appData']);
$router->get('/api/health',   [App\Controllers\Api\SyncController::class, 'health']);
// Incremental sync: a cheap manifest first, then only the resources that actually moved.
$router->get('/api/sync/manifest',  [App\Controllers\Api\SyncController::class, 'manifest']);
$router->get('/api/sync/resources', [App\Controllers\Api\SyncController::class, 'resources']);
$router->get('/api/sync/resource/{key}', [App\Controllers\Api\SyncController::class, 'resource']);

/* --------------------------------------------------------------- auth flow */
$router->get('/login',   [App\Controllers\AuthController::class, 'showLogin']);
$router->post('/login',  [App\Controllers\AuthController::class, 'login']);
$router->get('/forgot',  [App\Controllers\AuthController::class, 'showForgot']);
$router->post('/logout', [App\Controllers\AuthController::class, 'logout']);
// No GET /logout — sign-out is POST-only (CSRF-protected) to prevent logout CSRF.

/* --------------------------------------------------------------- install */
$router->get('/install',  [App\Controllers\InstallController::class, 'show']);
$router->post('/install', [App\Controllers\InstallController::class, 'run']);

/* --------------------------------------------------------------- dashboard */
$router->get('/',          [App\Controllers\DashboardController::class, 'index']);
$router->get('/dashboard', [App\Controllers\DashboardController::class, 'index']);
$router->get('/dashboard/export', [App\Controllers\DashboardController::class, 'exportCsv']);

/* --------------------------------------------------------------- offers */
$router->get('/offers',              [App\Controllers\OffersController::class, 'index']);
$router->get('/offers/new',          [App\Controllers\OffersController::class, 'create']);
$router->get('/offers/{id}/edit',    [App\Controllers\OffersController::class, 'edit']);
$router->post('/offers/save',        [App\Controllers\OffersController::class, 'save']);
$router->post('/offers/{id}/duplicate', [App\Controllers\OffersController::class, 'duplicate']);
$router->post('/offers/{id}/archive',[App\Controllers\OffersController::class, 'archive']);
$router->post('/offers/{id}/restore',[App\Controllers\OffersController::class, 'restore']);
$router->post('/offers/{id}/delete', [App\Controllers\OffersController::class, 'delete']);
$router->get('/offers/export',       [App\Controllers\OffersController::class, 'exportCsv']);

/* --------------------------------------------------------------- billboards */
$router->get('/billboards',            [App\Controllers\BillboardsController::class, 'index']);
$router->get('/billboards/calendar',   [App\Controllers\BillboardsController::class, 'calendar']);
$router->get('/billboards/new',        [App\Controllers\BillboardsController::class, 'create']);
$router->get('/billboards/{id}/edit',  [App\Controllers\BillboardsController::class, 'edit']);
$router->post('/billboards/save',      [App\Controllers\BillboardsController::class, 'save']);
$router->post('/billboards/{id}/status', [App\Controllers\BillboardsController::class, 'setStatus']);
$router->post('/billboards/{id}/delete', [App\Controllers\BillboardsController::class, 'delete']);
$router->get('/billboards/import',     [App\Controllers\ImportController::class, 'billboardsForm']);
$router->post('/billboards/import',    [App\Controllers\ImportController::class, 'importBillboards']);


/* ------------------------------------------------------------- instant push */
$router->get('/push',                      [App\Controllers\PushController::class, 'index']);
$router->post('/push/send',                [App\Controllers\PushController::class, 'send']);

/* ------------------------------------------------------------- customers */
$router->get('/customers',                  [App\Controllers\CustomersController::class, 'index']);
$router->get('/customers-export',           [App\Controllers\CustomersController::class, 'exportCsv']);
$router->post('/customers/delete-bulk',     [App\Controllers\CustomersController::class, 'deleteBulk']);
$router->post('/customers/{id}/delete',     [App\Controllers\CustomersController::class, 'delete']);

/* ------------------------------------------------------- referrals & commissions */
$router->get('/referrals',                        [App\Controllers\ReferralsController::class, 'index']);
$router->get('/referrals/referrers',              [App\Controllers\ReferralsController::class, 'referrers']);
$router->get('/referrals/referrers/{id}',         [App\Controllers\ReferralsController::class, 'show']);
$router->get('/referrals/withdrawals',            [App\Controllers\ReferralsController::class, 'withdrawals']);
$router->get('/referrals-export',                 [App\Controllers\ReferralsController::class, 'exportCsv']);
$router->post('/referrals/settings',              [App\Controllers\ReferralsController::class, 'saveSettings']);
$router->post('/referrals/referrers/{id}/status', [App\Controllers\ReferralsController::class, 'setStatus']);
$router->post('/referrals/referrers/{id}/adjust', [App\Controllers\ReferralsController::class, 'adjust']);
$router->post('/referrals/withdrawals/{id}/resolve', [App\Controllers\ReferralsController::class, 'resolveWithdrawal']);

/* --------------------------------------------------------------- payments */
$router->get('/payments',            [App\Controllers\PaymentsController::class, 'index']);
$router->get('/payments/{id}',       [App\Controllers\PaymentsController::class, 'show']);
$router->get('/payments-export',     [App\Controllers\PaymentsController::class, 'exportCsv']);
$router->post('/payments/delete-bulk', [App\Controllers\PaymentsController::class, 'deleteBulk']);
$router->post('/payments/{id}/delete', [App\Controllers\PaymentsController::class, 'delete']);

/* --------------------------------------------------------------- support */
$router->get('/support',      [App\Controllers\SupportController::class, 'index']);
$router->post('/support/save',[App\Controllers\SupportController::class, 'save']);

/* --------------------------------------------------------------- app config */
$router->get('/app-config',      [App\Controllers\AppConfigController::class, 'index']);
$router->post('/app-config/save',[App\Controllers\AppConfigController::class, 'save']);
$router->post('/app-config/categories/save', [App\Controllers\AppConfigController::class, 'saveCategories']);
$router->post('/app-config/flags/save',      [App\Controllers\AppConfigController::class, 'saveFlags']);

/* --------------------------------------------------------------- versions */
$router->get('/versions',           [App\Controllers\VersionsController::class, 'index']);
$router->get('/versions/fetch',     [App\Controllers\VersionsController::class, 'fetchLatest']);
$router->get('/versions/new',       [App\Controllers\VersionsController::class, 'create']);
$router->get('/versions/{id}/edit', [App\Controllers\VersionsController::class, 'edit']);
$router->post('/versions/save',     [App\Controllers\VersionsController::class, 'save']);
$router->post('/versions/{id}/activate', [App\Controllers\VersionsController::class, 'activate']);

/* --------------------------------------------------------------- audit */
$router->get('/audit',        [App\Controllers\AuditController::class, 'index']);
$router->get('/audit/{id}',   [App\Controllers\AuditController::class, 'show']);
$router->get('/audit-export', [App\Controllers\AuditController::class, 'exportCsv']);

/* --------------------------------------------------------------- settings */
$router->get('/settings',                  [App\Controllers\SettingsController::class, 'index']);
$router->post('/settings/profile',         [App\Controllers\SettingsController::class, 'saveProfile']);
$router->post('/settings/password',        [App\Controllers\SettingsController::class, 'savePassword']);
$router->get('/settings/admins',           [App\Controllers\SettingsController::class, 'admins']);
$router->post('/settings/admins/save',     [App\Controllers\SettingsController::class, 'saveAdmin']);
$router->post('/settings/admins/{id}/disable', [App\Controllers\SettingsController::class, 'disableAdmin']);

/* --------------------------------------------------------------- publishing */
$router->get('/preview',              [App\Controllers\PreviewController::class, 'index']);
$router->get('/preview/diff',         [App\Controllers\PreviewController::class, 'diff']);
$router->get('/publish',              [App\Controllers\PublishController::class, 'review']);
$router->post('/publish/execute',     [App\Controllers\PublishController::class, 'execute']);
$router->get('/releases',             [App\Controllers\PublishController::class, 'releases']);
$router->get('/releases/{version}',   [App\Controllers\PublishController::class, 'show']);
$router->post('/releases/{version}/rollback', [App\Controllers\PublishController::class, 'rollback']);

try {
    $router->dispatch($request);
} catch (Throwable $e) {
    if (!Config::isProduction()) {
        Response::html('<pre style="padding:24px;font:13px/1.5 monospace;color:#b00">'
            . e($e->getMessage()) . "\n\n" . e($e->getTraceAsString()) . '</pre>', 500);
    }
    error_log('[skylinkbingwa-admin] ' . $e->getMessage() . ' @ ' . $e->getFile() . ':' . $e->getLine());
    if ($isApi) {
        Response::json(['error' => 'server_error'], 500);
    }
    Response::html('<!doctype html><meta charset="utf-8"><title>Error</title>'
        . '<div style="font-family:system-ui;padding:40px">Something went wrong. Please try again.</div>', 500);
}
