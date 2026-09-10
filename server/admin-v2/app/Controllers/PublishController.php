<?php
/**
 * Publish review, publish (immutable signed release) and rollback (a new later version).
 *
 * The review page is the last stop before every Android device is affected, so it states
 * the impact in plain terms — how many values changed, which resources move, what devices
 * will actually download — and refuses to publish without an explicit confirmation.
 * Rollback requires a reason and the rollback.execute permission; it is unchanged.
 */

namespace App\Controllers;

use App\Core\Csrf;
use App\Core\Flash;
use App\Core\Rbac;
use App\Core\Request;
use App\Services\ChangeDetector;
use App\Services\PublishingService;
use App\Services\ResourceVersions;
use App\Services\ServiceLock;

final class PublishController extends Controller
{
    /** Pre-publish review: impact, validation, notes and the confirmation tick. */
    public function review(Request $request): void
    {
        $this->requireAuth();
        if (!Rbac::canAny(['publish.execute', 'rollback.execute', 'offers.view'])) {
            Rbac::require('publish.execute');
        }

        $snapshot = PublishingService::buildWorkingSnapshot();
        $check    = PublishingService::validate($snapshot);
        $summary  = PublishingService::publishSummary();

        $this->view('publish/review', [
            'activeNav' => 'preview',
            'pageTitle' => 'Review & publish',
            'summary'   => $summary,
            'groups'    => $summary['byModule'],
            'resources' => self::impactedResources($summary),
            'errors'    => $check['errors'],
            'warnings'  => $check['warnings'],
        ]);
    }

    public function execute(Request $request): void
    {
        Csrf::check($request);
        $this->guard('publish.execute');

        // Service lock: publishing is how content reaches a device, so it is blocked too.
        // Worded so it never hints at the Super-Admin-only switch behind it.
        if (ServiceLock::isLocked()) {
            Flash::error('Request Denied — the app is currently blocked, so nothing can be published.');
            $this->redirect('/publish');
        }

        // The operator must consciously agree that this reaches real devices.
        if ((string) $request->post('confirm', '') !== 'yes') {
            Flash::error('Tick the confirmation box before publishing.');
            $this->redirect('/publish');
        }

        $notes = trim((string) $request->post('notes', ''));

        $result = PublishingService::publish($notes);
        if (!$result['ok']) {
            Flash::error('Publish blocked: ' . implode(' ', $result['errors']));
            $this->redirect('/publish');
        }
        foreach ($result['warnings'] as $wmsg) {
            Flash::warning($wmsg);
        }
        $release = PublishingService::release((int) $result['version']);
        $uid = (string) ($release['release_uid'] ?? '');
        Flash::success(
            'Published configuration v' . $result['version']
            . ($uid !== '' ? ' (' . $uid . ')' : '')
            . '. The app will pick it up on its next background sync.'
        );
        $this->redirect('/releases/' . $result['version']);
    }

    public function releases(Request $request): void
    {
        $this->requireAuth();
        if (!Rbac::canAny(['publish.execute', 'rollback.execute', 'audit.view'])) {
            Rbac::require('publish.execute');
        }
        $this->view('publish/releases', [
            'activeNav' => 'releases', 'pageTitle' => 'Release history',
            'releases' => PublishingService::releases(100),
            'currentVersion' => (int) (PublishingService::currentRelease()['version'] ?? 0),
        ]);
    }

    public function show(Request $request, string $version): void
    {
        $this->requireAuth();
        if (!Rbac::canAny(['publish.execute', 'rollback.execute', 'audit.view'])) {
            Rbac::require('publish.execute');
        }
        $release = PublishingService::release((int) $version);
        if (!$release) {
            Flash::error('Release not found.');
            $this->redirect('/releases');
        }
        $resourceVersions = ResourceVersions::forRelease($release);
        $this->view('publish/show', [
            'activeNav' => 'releases', 'pageTitle' => 'Release v' . (int) $version,
            'release'   => $release,
            'groups'    => PublishingService::releaseChanges((int) $version),
            'resourceVersions' => $resourceVersions,
            'snapshot'  => json_decode($release['snapshot_json'], true) ?: [],
            'isCurrent' => (int) $version === (int) (PublishingService::currentRelease()['version'] ?? 0),
        ]);
    }

    public function rollback(Request $request, string $version): void
    {
        Csrf::check($request);
        $this->guard('rollback.execute');

        $reason = trim((string) $request->post('reason', ''));
        if ($reason === '') {
            Flash::error('A reason is required to roll back.');
            $this->redirect('/releases/' . (int) $version);
        }

        if (!PublishingService::restoreWorkingFrom((int) $version)) {
            Flash::error('Could not load that release for rollback.');
            $this->redirect('/releases/' . (int) $version);
        }
        $result = PublishingService::publish('Rollback to v' . (int) $version . ': ' . $reason, (int) $version);
        if (!$result['ok']) {
            Flash::error('Rollback publish failed: ' . implode(' ', $result['errors']));
            $this->redirect('/releases/' . (int) $version);
        }
        Flash::success('Rolled back. Published as new configuration v' . $result['version'] . '.');
        $this->redirect('/releases/' . $result['version']);
    }

    /**
     * Sync impact: for each resource, the version devices hold now and the version they
     * will move to. A resource with no changed value keeps its number and is not
     * re-downloaded by anyone.
     *
     * @return array<int,array{key:string,label:string,from:int,to:int,moves:bool,count:int}>
     */
    private static function impactedResources(array $summary): array
    {
        $current = $summary['resourceVersions'];
        $byModule = $summary['byModule'];
        $out = [];
        foreach (ResourceVersions::keys() as $key) {
            $moves = isset($byModule[$key]);
            $from = (int) ($current[$key]['version'] ?? 0);
            $out[] = [
                'key'   => $key,
                'label' => ChangeDetector::moduleLabel($key),
                'from'  => $from,
                'to'    => $moves ? (int) $summary['draftVersion'] : $from,
                'moves' => $moves,
                'count' => (int) ($byModule[$key]['count'] ?? 0),
            ];
        }
        return $out;
    }
}
