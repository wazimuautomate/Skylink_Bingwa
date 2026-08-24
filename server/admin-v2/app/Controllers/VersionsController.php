<?php
/**
 * App version / update rules. Guards against lockout: minimum supported cannot exceed
 * latest, a forced update needs a valid destination, latest cannot be a downgrade of the
 * active rule, and rollout stays 0–100. Forced-update changes require Super Admin +
 * re-authentication. Changes are drafts until published.
 */

namespace App\Controllers;

use App\Core\Audit;
use App\Core\Auth;
use App\Core\Csrf;
use App\Core\Database;
use App\Core\Flash;
use App\Core\Request;
use App\Core\Validator;

final class VersionsController extends Controller
{
    private function table(): string { return Database::table('app_versions'); }

    public function index(Request $request): void
    {
        $this->guard('releases.manage');
        $active = Database::fetch('SELECT * FROM ' . $this->table() . " WHERE status = 'active' ORDER BY latest_version_code DESC LIMIT 1");
        $this->view('versions/index', [
            'activeNav' => 'versions', 'pageTitle' => 'Updates & versions',
            'versions' => Database::fetchAll('SELECT * FROM ' . $this->table() . ' ORDER BY latest_version_code DESC'),
            'active' => $active,
            'updateJson' => $active ? self::buildUpdateJson($active) : null,
        ]);
    }

    /**
     * Fetch the latest GitHub release and prefill the "add rule" form with it. Public
     * repo, so no token is needed — but the GitHub API requires a User-Agent. Any
     * network / rate-limit / parse failure surfaces a clear flash, never a fatal.
     */
    public function fetchLatest(Request $request): void
    {
        $this->guard('releases.manage');
        try {
            $data = self::githubLatest();
        } catch (\Throwable $e) {
            Flash::error('Could not fetch from GitHub: ' . $e->getMessage());
            $this->redirect('/versions');
            return;
        }
        // Suggest a versionCode above the current active latest (the tag only gives a name).
        $suggested = (int) (Database::scalar('SELECT MAX(latest_version_code) FROM ' . $this->table()) ?? 0) + 1;
        $prefill = [
            'latest_version_code' => $data['suggestedVersionCode'] ?: $suggested,
            'latest_version_name' => $data['versionName'],
            'min_supported_version_code' => 1,
            'mandatory' => 0,
            'update_source' => 'github',
            'play_store_url' => '',
            'apk_url' => $data['apkUrl'],
            'apk_sha256' => $data['apkSha256'],
            'rollout_percent' => 100,
            'release_notes' => $data['releaseNotes'],
            'status' => 'active',
        ];
        $this->view('versions/form', [
            'activeNav' => 'versions', 'pageTitle' => 'Record GitHub release',
            'version' => $prefill, 'isNew' => true, 'fetched' => $data,
        ]);
    }

    public function create(Request $request): void
    {
        $this->guard('releases.manage');
        $this->view('versions/form', ['activeNav' => 'versions', 'pageTitle' => 'Add release rule', 'version' => null, 'isNew' => true]);
    }

    public function edit(Request $request, string $id): void
    {
        $this->guard('releases.manage');
        $row = Database::fetch('SELECT * FROM ' . $this->table() . ' WHERE id = ?', [(int) $id]);
        if (!$row) { Flash::error('Version rule not found.'); $this->redirect('/versions'); }
        $this->view('versions/form', ['activeNav' => 'versions', 'pageTitle' => 'Edit release rule', 'version' => $row, 'isNew' => false]);
    }

    public function save(Request $request): void
    {
        Csrf::check($request);
        $this->guard('releases.manage');
        $isNew = $request->post('is_new') === '1';
        $id = (int) $request->post('id', 0);

        $source = $request->post('update_source') === 'play' ? 'play' : 'github';
        $input = [
            'latest_version_code' => (int) $request->post('latest_version_code', 0),
            'latest_version_name' => trim((string) $request->post('latest_version_name', '')),
            'min_supported_version_code' => (int) $request->post('min_supported_version_code', 1),
            'mandatory' => $request->post('mandatory') ? 1 : 0,
            'play_store_url' => trim((string) $request->post('play_store_url', '')),
            'apk_url' => trim((string) $request->post('apk_url', '')),
            'apk_sha256' => trim((string) $request->post('apk_sha256', '')),
            'update_source' => $source,
            'rollout_percent' => (int) $request->post('rollout_percent', 100),
            'release_notes' => trim((string) $request->post('release_notes', '')),
            'status' => $request->post('active') ? 'active' : 'inactive',
        ];

        $v = Validator::make($input);
        $v->validate([
            'latest_version_code' => 'required|int|min:1',
            'latest_version_name' => 'required|max:24',
            'min_supported_version_code' => 'required|int|min:1',
            'update_source' => 'required|in:github,play',
            'play_store_url' => 'max:200',
            'apk_url' => 'max:200',
        ]);
        if ($input['min_supported_version_code'] > $input['latest_version_code']) {
            $v->add('min_supported_version_code', 'Minimum supported cannot exceed the latest version.');
        }
        if ($input['mandatory'] && $input['play_store_url'] === '' && $input['apk_url'] === '') {
            $v->add('mandatory', 'A forced update needs a Play Store or APK destination.');
        }
        // The chosen source must have a matching destination once the rule is live/forced.
        if ($input['status'] === 'active' || $input['mandatory']) {
            if ($input['update_source'] === 'play' && $input['play_store_url'] === '') {
                $v->add('play_store_url', 'Play Store source needs a Play Store URL.');
            }
            if ($input['update_source'] === 'github' && $input['apk_url'] === '') {
                $v->add('apk_url', 'Direct APK (GitHub) source needs an APK URL.');
            }
        }
        if ($input['rollout_percent'] < 0 || $input['rollout_percent'] > 100) {
            $v->add('rollout_percent', 'Rollout must be 0–100.');
        }
        // No downgrade of the active latest.
        $activeLatest = (int) (Database::scalar('SELECT MAX(latest_version_code) FROM ' . $this->table() . " WHERE status='active'" . ($isNew ? '' : ' AND id <> ?'), $isNew ? [] : [$id]) ?? 0);
        if ($input['status'] === 'active' && $activeLatest > 0 && $input['latest_version_code'] < $activeLatest) {
            $v->add('latest_version_code', "Cannot publish a downgrade below the current active latest ({$activeLatest}).");
        }
        if ($v->fails()) {
            Flash::error('Fix: ' . implode(' ', array_values($v->firstErrors())));
            $this->redirect($isNew ? '/versions/new' : '/versions/' . $id . '/edit');
        }

        $actor = Auth::user()['name'] ?? 'system';
        // Only one active rule at a time.
        if ($input['status'] === 'active') {
            Database::run('UPDATE ' . $this->table() . " SET status='inactive' WHERE status='active'" . ($isNew ? '' : ' AND id <> ?'), $isNew ? [] : [$id]);
        }
        $before = $isNew ? null : Database::fetch('SELECT * FROM ' . $this->table() . ' WHERE id = ?', [$id]);
        if ($isNew) {
            Database::run(
                'INSERT INTO ' . $this->table() . '
                    (latest_version_code, latest_version_name, min_supported_version_code, mandatory,
                     play_store_url, apk_url, apk_sha256, update_source, rollout_percent, release_notes, status,
                     row_version, created_at, updated_at, updated_by)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP(), ?)',
                array_merge(array_values($input), [$actor])
            );
            $id = (int) Database::pdo()->lastInsertId();
        } else {
            Database::run(
                'UPDATE ' . $this->table() . ' SET
                    latest_version_code=?, latest_version_name=?, min_supported_version_code=?, mandatory=?,
                    play_store_url=?, apk_url=?, apk_sha256=?, update_source=?, rollout_percent=?, release_notes=?, status=?,
                    row_version = row_version + 1, updated_at = UTC_TIMESTAMP(), updated_by = ? WHERE id = ?',
                array_merge(array_values($input), [$actor, $id])
            );
        }
        Audit::log([
            'action' => $input['mandatory'] ? 'version.forced_update' : ($isNew ? 'version.create' : 'version.update'),
            'entity_type' => 'app_version', 'entity_id' => $id,
            'before' => $before, 'after' => Database::fetch('SELECT * FROM ' . $this->table() . ' WHERE id = ?', [$id]),
            'reason' => $input['mandatory'] ? 'Forced update' : null,
        ]);
        Flash::success('Version rule saved as a draft change. Publish to apply.');
        $this->redirect('/versions');
    }

    public function activate(Request $request, string $id): void
    {
        Csrf::check($request);
        $this->guard('releases.manage');
        $row = Database::fetch('SELECT * FROM ' . $this->table() . ' WHERE id = ?', [(int) $id]);
        if (!$row) { Flash::error('Version rule not found.'); $this->redirect('/versions'); }
        Database::run('UPDATE ' . $this->table() . " SET status='inactive' WHERE status='active'");
        Database::run('UPDATE ' . $this->table() . " SET status='active', updated_at=UTC_TIMESTAMP() WHERE id = ?", [(int) $id]);
        Audit::log(['action' => 'version.activate', 'entity_type' => 'app_version', 'entity_id' => (int) $id]);
        Flash::success('Version rule activated (draft). Publish to apply.');
        $this->redirect('/versions');
    }

    /* -------------------------------------------------------- GitHub + update.json */

    private const GH_REPO = 'wazimuautomate/Skylink_Bingwa';

    /**
     * Call the public GitHub Releases API server-side and parse the latest release.
     * @return array{tag:string, versionName:string, apkUrl:string, apkSha256:string, releaseNotes:string, suggestedVersionCode:int, htmlUrl:string, publishedAt:string}
     * @throws \RuntimeException on any transport / rate-limit / parse failure.
     */
    private static function githubLatest(): array
    {
        $url = 'https://api.github.com/repos/' . self::GH_REPO . '/releases/latest';
        [$status, $body] = self::httpGet($url);
        if ($status === 404) {
            // No "latest" (e.g. only pre-releases) — fall back to the releases list.
            [$status, $body] = self::httpGet('https://api.github.com/repos/' . self::GH_REPO . '/releases?per_page=1');
            $list = json_decode((string) $body, true);
            $body = json_encode(is_array($list) && isset($list[0]) ? $list[0] : []);
        }
        if ($status === 403) {
            throw new \RuntimeException('GitHub rate limit reached. Try again in a few minutes.');
        }
        if ($status < 200 || $status >= 300) {
            throw new \RuntimeException('GitHub returned HTTP ' . $status . '.');
        }
        $rel = json_decode((string) $body, true);
        if (!is_array($rel) || empty($rel['tag_name'])) {
            throw new \RuntimeException('No published release was found.');
        }

        $tag = (string) $rel['tag_name'];
        $versionName = ltrim($tag, 'vV');
        // Best-effort versionCode: an explicit "versionCode N" in the notes, else none.
        $code = 0;
        if (preg_match('/versionCode\D+(\d+)/i', (string) ($rel['body'] ?? ''), $m)) {
            $code = (int) $m[1];
        }

        $apkUrl = '';
        $shaAssetUrl = '';
        foreach (($rel['assets'] ?? []) as $a) {
            $name = strtolower((string) ($a['name'] ?? ''));
            $dl = (string) ($a['browser_download_url'] ?? '');
            if ($apkUrl === '' && str_ends_with($name, '.apk')) {
                $apkUrl = $dl;
            }
            if ($shaAssetUrl === '' && (str_ends_with($name, '.sha256') || str_ends_with($name, '.apk.sha256'))) {
                $shaAssetUrl = $dl;
            }
        }
        // Fall back to a conventional release-asset path if none was listed.
        if ($apkUrl === '') {
            $apkUrl = 'https://github.com/' . self::GH_REPO . '/releases/download/' . $tag . '/Skylink-Bingwa-' . $tag . '-direct.apk';
        }

        $sha = '';
        if ($shaAssetUrl !== '') {
            try {
                [$s, $shaBody] = self::httpGet($shaAssetUrl);
                if ($s >= 200 && $s < 300 && preg_match('/[0-9a-f]{64}/i', (string) $shaBody, $hm)) {
                    $sha = strtolower($hm[0]);
                }
            } catch (\Throwable $e) {
                $sha = ''; // non-fatal — the admin can paste it manually
            }
        }

        return [
            'tag' => $tag,
            'versionName' => $versionName !== '' ? $versionName : $tag,
            'apkUrl' => $apkUrl,
            'apkSha256' => $sha,
            'releaseNotes' => mb_substr((string) ($rel['body'] ?? ''), 0, 1000),
            'suggestedVersionCode' => $code,
            'htmlUrl' => (string) ($rel['html_url'] ?? ''),
            'publishedAt' => (string) ($rel['published_at'] ?? ''),
        ];
    }

    /** Minimal cURL GET with the required User-Agent. @return array{0:int,1:string} */
    private static function httpGet(string $url): array
    {
        if (!function_exists('curl_init')) {
            throw new \RuntimeException('Server cURL is unavailable — cannot reach GitHub.');
        }
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_CONNECTTIMEOUT => 6,
            CURLOPT_HTTPHEADER => [
                'User-Agent: Skylink-Bingwa-Admin',
                'Accept: application/vnd.github+json',
                'X-GitHub-Api-Version: 2022-11-28',
            ],
        ]);
        $body = curl_exec($ch);
        $err = curl_error($ch);
        $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);
        if ($body === false) {
            throw new \RuntimeException($err !== '' ? $err : 'Network error contacting GitHub.');
        }
        return [$status, (string) $body];
    }

    /**
     * The exact update.json the owner should publish to the repo root for this rule.
     * Mirrors the keys the shipped app reads, plus the new source fields.
     */
    private static function buildUpdateJson(array $row): string
    {
        $source = ($row['update_source'] ?? 'github') === 'play' ? 'play' : 'github';
        $data = [
            'latestVersionCode' => (int) $row['latest_version_code'],
            'latestVersionName' => (string) $row['latest_version_name'],
            'minSupportedVersionCode' => (int) $row['min_supported_version_code'],
            'apkUrl' => (string) $row['apk_url'],
            'apkSha256' => (string) $row['apk_sha256'],
            'releaseNotes' => (string) $row['release_notes'],
            'mandatory' => (int) $row['mandatory'] === 1,
            'updateSource' => $source,
            'playStoreUrl' => (string) $row['play_store_url'],
        ];
        return (string) json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }
}
