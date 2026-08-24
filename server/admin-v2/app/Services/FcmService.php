<?php
/**
 * Firebase Cloud Messaging (FCM HTTP v1).
 *
 * Signs a service-account JWT with OpenSSL (RS256), exchanges it for a short-lived
 * Google OAuth2 access token, then calls
 *   POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send
 *
 * No Composer dependency — built-in OpenSSL and cURL only, because the admin runs on
 * shared cPanel hosting where `composer install` is not part of the deploy.
 *
 * Every failure path returns a HUMAN-READABLE REASON rather than a bare false. FCM's
 * own error body ("SENDER_ID_MISMATCH", "UNREGISTERED", a mistyped project id) is the
 * only thing that makes a delivery problem diagnosable from the dashboard, so it is
 * carried all the way back to the flash message.
 */

namespace App\Services;

use App\Core\Config;
use App\Core\Database;
use Throwable;

final class FcmService
{
    private static ?string $cachedAccessToken = null;
    private static int $tokenExpiresAt = 0;
    private static ?string $lastError = null;

    /** Where a service-account JSON may live, in priority order. */
    private static function credentialPaths(): array
    {
        return array_values(array_filter([
            (string) Config::get('fcm.service_account_file', ''),
            dirname(__DIR__, 2) . '/config/firebase-service-account.json',
            dirname(__DIR__, 3) . '/firebase-service-account.json',
            dirname(__DIR__, 3) . '/my-bingwa-b538e0f6c645.json',
            dirname(__DIR__, 4) . '/my-bingwa-b538e0f6c645.json',
        ], static function ($p) {
            return $p !== '';
        }));
    }

    public static function loadCredentials(): ?array
    {
        foreach (self::credentialPaths() as $path) {
            if (!is_file($path) || !is_readable($path)) {
                continue;
            }
            $data = json_decode((string) file_get_contents($path), true);
            if (is_array($data)
                && !empty($data['private_key'])
                && !empty($data['client_email'])
                && !empty($data['project_id'])
            ) {
                return $data;
            }
            self::$lastError = 'Found ' . $path . ' but it is not a valid service-account JSON '
                . '(it needs project_id, client_email and private_key).';
        }
        return null;
    }

    public static function isConfigured(): bool
    {
        return self::loadCredentials() !== null;
    }

    /** Why configuration failed, for the dashboard. Null when everything is in place. */
    public static function configurationError(): ?string
    {
        if (self::loadCredentials() !== null) {
            return null;
        }
        return self::$lastError
            ?? 'No Firebase service-account JSON found. Looked in: ' . implode(', ', self::credentialPaths());
    }

    private static function base64UrlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }

    /**
     * Acquire a Google OAuth2 access token for the firebase.messaging scope.
     *
     * @return array{token: ?string, error: ?string}
     */
    public static function accessToken(): array
    {
        $now = time();
        if (self::$cachedAccessToken !== null && self::$tokenExpiresAt > ($now + 120)) {
            return ['token' => self::$cachedAccessToken, 'error' => null];
        }

        $credentials = self::loadCredentials();
        if ($credentials === null) {
            return ['token' => null, 'error' => self::configurationError()];
        }

        $header = (string) json_encode(['alg' => 'RS256', 'typ' => 'JWT']);
        $payload = (string) json_encode([
            'iss'   => $credentials['client_email'],
            'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
            'aud'   => 'https://oauth2.googleapis.com/token',
            'iat'   => $now,
            'exp'   => $now + 3600,
        ]);

        $signingInput = self::base64UrlEncode($header) . '.' . self::base64UrlEncode($payload);

        $privateKey = openssl_pkey_get_private((string) $credentials['private_key']);
        if ($privateKey === false) {
            return [
                'token' => null,
                'error' => 'The service-account private key could not be read by OpenSSL: '
                    . (openssl_error_string() ?: 'unknown OpenSSL error') . '.',
            ];
        }

        $signature = '';
        if (!openssl_sign($signingInput, $signature, $privateKey, OPENSSL_ALGO_SHA256)) {
            return [
                'token' => null,
                'error' => 'Could not RS256-sign the Google auth request: '
                    . (openssl_error_string() ?: 'unknown OpenSSL error') . '.',
            ];
        }

        $jwt = $signingInput . '.' . self::base64UrlEncode($signature);

        $res = self::httpPost(
            'https://oauth2.googleapis.com/token',
            http_build_query([
                'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                'assertion'  => $jwt,
            ]),
            ['Content-Type: application/x-www-form-urlencoded'],
            15
        );

        if ($res['error'] !== null) {
            return ['token' => null, 'error' => 'Could not reach Google to authenticate: ' . $res['error']];
        }

        $tokenData = json_decode((string) $res['body'], true);
        if ($res['status'] !== 200 || !is_array($tokenData) || empty($tokenData['access_token'])) {
            $detail = is_array($tokenData)
                ? trim((string) ($tokenData['error'] ?? '') . ' ' . (string) ($tokenData['error_description'] ?? ''))
                : substr((string) $res['body'], 0, 200);
            return [
                'token' => null,
                'error' => 'Google rejected the service account (HTTP ' . $res['status'] . '): '
                    . ($detail !== '' ? $detail : 'no detail returned') . '.',
            ];
        }

        self::$cachedAccessToken = (string) $tokenData['access_token'];
        self::$tokenExpiresAt = $now + (int) ($tokenData['expires_in'] ?? 3600);

        return ['token' => self::$cachedAccessToken, 'error' => null];
    }

    /** Back-compat shim for callers that only want the token. */
    public static function getAccessToken(): ?string
    {
        return self::accessToken()['token'];
    }

    /**
     * The message envelope shared by token and topic sends.
     *
     * DATA-ONLY, deliberately — there is no `notification` block.
     *
     * When a message carries a `notification` block, the Firebase SDK draws the tray
     * notification ITSELF whenever the app is backgrounded, and the app's own
     * onMessageReceived() is never called. That cost us twice:
     *
     *   1. The SDK posts on `android.notification.channel_id`. This used to say
     *      "news_channel", but the app's channel is NotificationChannels.NEWS = "news".
     *      Android 8+ silently DROPS a notification posted to a channel that does not
     *      exist, so every background push vanished with no error anywhere.
     *   2. Even had the id matched, the SDK-drawn notification never reaches the app's
     *      code, so the message would never appear in the in-app notification centre.
     *
     * Data-only makes onMessageReceived() the single delivery path in every app state, so
     * the tray notification is posted by AppNotifier (correct channel, correct deep link)
     * AND recorded in the notification centre. The trade-off is that a user who has
     * force-stopped the app receives nothing until they reopen it — which is true of the
     * SDK path as well.
     */
    private static function envelope(array $target, string $title, string $body, string $route): array
    {
        return [
            'message' => $target + [
                'data' => [
                    'title' => $title,
                    'body'  => $body,
                    'route' => $route,
                ],
                // HIGH tells FCM to wake the app out of Doze to run onMessageReceived,
                // which is what makes an admin broadcast arrive instantly rather than
                // whenever the device next leaves an idle window.
                'android' => [
                    'priority' => 'HIGH',
                ],
            ],
        ];
    }

    /**
     * Deliver one message.
     *
     * @return array{ok: bool, error: ?string, stale: bool} `stale` marks a token FCM says
     *         no longer exists, so the caller can clear it instead of retrying forever.
     */
    private static function dispatch(
        array $target,
        string $title,
        string $body,
        string $route,
        string $accessToken,
        string $projectId
    ): array {
        $res = self::httpPost(
            'https://fcm.googleapis.com/v1/projects/' . $projectId . '/messages:send',
            (string) json_encode(self::envelope($target, $title, $body, $route)),
            ['Authorization: Bearer ' . $accessToken, 'Content-Type: application/json'],
            15
        );

        if ($res['error'] !== null) {
            return ['ok' => false, 'error' => $res['error'], 'stale' => false];
        }
        if ($res['status'] === 200) {
            return ['ok' => true, 'error' => null, 'stale' => false];
        }

        $decoded = json_decode((string) $res['body'], true);
        $status = '';
        $message = '';
        if (is_array($decoded)) {
            $status = (string) ($decoded['error']['status'] ?? '');
            $message = (string) ($decoded['error']['message'] ?? '');
        }

        // 404/NOT_FOUND and UNREGISTERED mean the app was uninstalled or the token was
        // rotated. That is not a configuration fault and must never be retried.
        $stale = $res['status'] === 404
            || $status === 'NOT_FOUND'
            || str_contains($message, 'UNREGISTERED')
            || str_contains($message, 'not a valid FCM registration token');

        $detail = trim($status . ($message !== '' ? ': ' . $message : ''));

        return [
            'ok'    => false,
            'error' => 'HTTP ' . $res['status'] . ' '
                . ($detail !== '' ? $detail : substr((string) $res['body'], 0, 200)),
            'stale' => $stale,
        ];
    }

    public static function sendToToken(string $token, string $title, string $body, string $route = 'notifications'): bool
    {
        $credentials = self::loadCredentials();
        $auth = self::accessToken();
        if ($credentials === null || $auth['token'] === null) {
            return false;
        }
        return self::dispatch(
            ['token' => $token],
            $title,
            $body,
            $route,
            $auth['token'],
            (string) $credentials['project_id']
        )['ok'];
    }

    public static function sendToTopic(string $topic, string $title, string $body, string $route = 'notifications'): bool
    {
        $credentials = self::loadCredentials();
        $auth = self::accessToken();
        if ($credentials === null || $auth['token'] === null) {
            return false;
        }
        return self::dispatch(
            ['topic' => $topic],
            $title,
            $body,
            $route,
            $auth['token'],
            (string) $credentials['project_id']
        )['ok'];
    }

    /**
     * Send to every registered device.
     *
     * Delivery is per-token so one dead handset cannot silence the whole broadcast, and
     * ALSO to the `all_users` topic, which the app subscribes to at startup — that is the
     * safety net for a phone that has not finished onboarding, so its token has never
     * reached the customer table.
     *
     * @return array{success: bool, total_targets: int, sent_count: int, failed_count: int,
     *               pruned_count: int, topic_ok: bool, broadcast_id: ?string, error: ?string}
     */
    public static function broadcast(
        string $title,
        string $body,
        string $route = 'notifications',
        string $createdBy = 'admin'
    ): array {
        $credentials = self::loadCredentials();
        if ($credentials === null) {
            return self::failure((string) self::configurationError());
        }

        $auth = self::accessToken();
        if ($auth['token'] === null) {
            return self::failure((string) $auth['error']);
        }
        $accessToken = $auth['token'];
        $projectId = (string) $credentials['project_id'];

        $tokens = [];
        $tokenReadError = null;
        try {
            $customers = Database::table('customers');
            $rows = Database::fetchAll(
                'SELECT DISTINCT fcm_token FROM ' . $customers . "
                  WHERE fcm_token IS NOT NULL AND fcm_token <> ''"
            );
            $tokens = array_values(array_filter(array_column($rows, 'fcm_token')));
        } catch (Throwable $e) {
            $tokenReadError = 'Could not read device tokens — run the pending database migrations. ('
                . $e->getMessage() . ')';
            error_log('[skylinkbingwa-admin] fcm token read failed: ' . $e->getMessage());
        }

        $sent = 0;
        $failed = 0;
        $stale = [];
        $firstError = null;

        foreach ($tokens as $token) {
            $r = self::dispatch(['token' => $token], $title, $body, $route, $accessToken, $projectId);
            if ($r['ok']) {
                $sent++;
                continue;
            }
            $failed++;
            if ($r['stale']) {
                $stale[] = $token;
            } elseif ($firstError === null) {
                $firstError = $r['error'];
            }
        }

        $pruned = self::pruneTokens($stale);

        // Topic fan-out, counted SEPARATELY from per-device delivery so the dashboard
        // numbers stay honest: FCM returns 200 for a topic with zero subscribers, and
        // reporting that as "delivered to 1" would be a lie.
        $topic = self::dispatch(['topic' => 'all_users'], $title, $body, $route, $accessToken, $projectId);
        if (!$topic['ok'] && $firstError === null) {
            $firstError = $topic['error'];
        }

        $total = count($tokens);
        $success = $sent > 0 || ($total === 0 && $topic['ok']);

        $error = null;
        if (!$success) {
            if ($tokenReadError !== null) {
                $error = $tokenReadError;
            } elseif ($total === 0) {
                $error = 'No device has registered for push yet, and the all_users topic send also failed'
                    . ($firstError !== null ? ' — ' . $firstError : '') . '.';
            } else {
                $error = 'All ' . $total . ' device(s) rejected the message'
                    . ($firstError !== null ? ' — ' . $firstError : '') . '.';
            }
        }

        $broadcastId = self::logBroadcast($title, $body, $route, $total, $sent, $failed, $createdBy);

        return [
            'success'       => $success,
            'total_targets' => $total,
            'sent_count'    => $sent,
            'failed_count'  => $failed,
            'pruned_count'  => $pruned,
            'topic_ok'      => $topic['ok'],
            'broadcast_id'  => $broadcastId,
            'error'         => $error,
        ];
    }

    /** @return array{success: bool, total_targets: int, sent_count: int, failed_count: int, pruned_count: int, topic_ok: bool, broadcast_id: ?string, error: ?string} */
    private static function failure(string $why): array
    {
        return [
            'success'       => false,
            'total_targets' => 0,
            'sent_count'    => 0,
            'failed_count'  => 0,
            'pruned_count'  => 0,
            'topic_ok'      => false,
            'broadcast_id'  => null,
            'error'         => $why,
        ];
    }

    /** Clear tokens FCM says no longer exist, so they stop costing a call on every send. */
    private static function pruneTokens(array $tokens): int
    {
        if ($tokens === []) {
            return 0;
        }
        try {
            $customers = Database::table('customers');
            $placeholders = implode(',', array_fill(0, count($tokens), '?'));
            Database::run(
                'UPDATE ' . $customers . ' SET fcm_token = NULL WHERE fcm_token IN (' . $placeholders . ')',
                $tokens
            );
            return count($tokens);
        } catch (Throwable $e) {
            error_log('[skylinkbingwa-admin] fcm token prune failed: ' . $e->getMessage());
            return 0;
        }
    }

    /** @return ?string the inserted history row id, or null when history is unavailable. */
    private static function logBroadcast(
        string $title,
        string $body,
        string $route,
        int $targets,
        int $success,
        int $failed,
        string $createdBy
    ): ?string {
        try {
            $table = Database::table('push_broadcasts');
            Database::run(
                'INSERT INTO ' . $table . '
                    (title, body, deep_link_route, recipients_count, success_count, failure_count, created_by, created_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP())',
                [$title, $body, $route, $targets, $success, $failed, $createdBy]
            );
            return (string) Database::pdo()->lastInsertId();
        } catch (Throwable $e) {
            // History is an audit convenience; never let it fail a delivery that worked.
            error_log('[skylinkbingwa-admin] push history insert failed: ' . $e->getMessage());
            return null;
        }
    }

    /**
     * One cURL POST.
     *
     * @return array{status: int, body: ?string, error: ?string}
     */
    private static function httpPost(string $url, string $payload, array $headers, int $timeout): array
    {
        $ch = curl_init($url);
        if ($ch === false) {
            return ['status' => 0, 'body' => null, 'error' => 'cURL is unavailable on this server.'];
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => $payload,
            CURLOPT_HTTPHEADER     => $headers,
            CURLOPT_TIMEOUT        => $timeout,
            CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $body = curl_exec($ch);
        $status = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $err = curl_error($ch);
        curl_close($ch);

        if ($body === false) {
            return ['status' => $status, 'body' => null, 'error' => $err !== '' ? $err : 'the request failed'];
        }
        return ['status' => $status, 'body' => (string) $body, 'error' => null];
    }
}
