<?php
/**
 * Firebase Cloud Messaging (HTTP v1) for the payments API.
 *
 * A deliberately small twin of admin-v2's App\Services\FcmService. The admin
 * version is bound to that app's Config and Database classes; the payments API is
 * a set of standalone scripts with no autoloader, so it carries its own.
 *
 * Built on OpenSSL and cURL only — the same reason the admin one is: this runs on
 * shared cPanel hosting where `composer install` is not part of the deploy.
 */

/** Locate the service-account JSON. Mirrors FcmService::credentialPaths(). */
function fcm_credentials(array $config): ?array
{
    static $cached = false;
    static $creds = null;
    if ($cached) {
        return $creds;
    }
    $cached = true;

    $paths = array_values(array_filter([
        (string) ($config['fcm_service_account_file'] ?? ''),
        __DIR__ . '/firebase-service-account.json',
        dirname(__DIR__) . '/firebase-service-account.json',
        dirname(__DIR__) . '/my-bingwa-b538e0f6c645.json',
        dirname(__DIR__, 2) . '/my-bingwa-b538e0f6c645.json',
    ]));

    foreach ($paths as $path) {
        if (!is_file($path) || !is_readable($path)) {
            continue;
        }
        $data = json_decode((string) file_get_contents($path), true);
        if (is_array($data) && !empty($data['private_key']) && !empty($data['client_email']) && !empty($data['project_id'])) {
            $creds = $data;
            return $creds;
        }
    }
    return null;
}

function fcm_b64url(string $data): string
{
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

/**
 * Exchange a self-signed service-account JWT for a Google access token.
 * Cached in-process until shortly before expiry, so one cron run authenticates once.
 */
function fcm_access_token(array $config): ?string
{
    static $token = null;
    static $expires = 0;
    if ($token !== null && time() < $expires - 60) {
        return $token;
    }

    $creds = fcm_credentials($config);
    if ($creds === null) {
        return null;
    }

    $now = time();
    $header = fcm_b64url(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
    $claims = fcm_b64url(json_encode([
        'iss'   => $creds['client_email'],
        'scope' => 'https://www.googleapis.com/auth/firebase.messaging',
        'aud'   => 'https://oauth2.googleapis.com/token',
        'iat'   => $now,
        'exp'   => $now + 3600,
    ]));

    $signature = '';
    if (!openssl_sign($header . '.' . $claims, $signature, $creds['private_key'], OPENSSL_ALGO_SHA256)) {
        return null;
    }
    $jwt = $header . '.' . $claims . '.' . fcm_b64url($signature);

    $ch = curl_init('https://oauth2.googleapis.com/token');
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => http_build_query([
            'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
            'assertion'  => $jwt,
        ]),
        CURLOPT_TIMEOUT        => 20,
    ]);
    $raw = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($code !== 200 || $raw === false) {
        return null;
    }
    $json = json_decode((string) $raw, true);
    if (!is_array($json) || empty($json['access_token'])) {
        return null;
    }

    $token = (string) $json['access_token'];
    $expires = $now + (int) ($json['expires_in'] ?? 3600);
    return $token;
}

/**
 * Send one push. Returns [ok, reason].
 *
 * The reason is carried back verbatim rather than collapsed to false: FCM's own
 * error body (UNREGISTERED, SENDER_ID_MISMATCH, a wrong project id) is the only
 * thing that makes a delivery problem diagnosable from the outbox table.
 */
function fcm_send(array $config, string $deviceToken, string $title, string $body, string $route = 'referrals'): array
{
    if (trim($deviceToken) === '') {
        return [false, 'NO_TOKEN'];
    }
    $creds = fcm_credentials($config);
    if ($creds === null) {
        return [false, 'FCM_NOT_CONFIGURED'];
    }
    $accessToken = fcm_access_token($config);
    if ($accessToken === null) {
        return [false, 'FCM_AUTH_FAILED'];
    }

    // DATA-ONLY, exactly like admin-v2's FcmService::envelope(). This is not a
    // style choice: a `notification` block makes the Firebase SDK draw the tray
    // notification itself whenever the app is backgrounded, which means
    // onMessageReceived() never runs — so the message would be posted to the
    // wrong channel (and silently dropped by Android 8+) and would never reach
    // the in-app notification centre. Data-only keeps onMessageReceived() the
    // single delivery path in every app state.
    $payload = [
        'message' => [
            'token' => $deviceToken,
            'data'  => [
                'title' => $title,
                'body'  => $body,
                'route' => $route,
            ],
            // HIGH wakes the app out of Doze, so a payout confirmation arrives
            // immediately rather than at the next idle-window break.
            'android' => ['priority' => 'HIGH'],
        ],
    ];

    $url = 'https://fcm.googleapis.com/v1/projects/' . $creds['project_id'] . '/messages:send';
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => json_encode($payload),
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $accessToken,
            'Content-Type: application/json',
        ],
        CURLOPT_TIMEOUT        => 20,
    ]);
    $raw = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);

    if ($code >= 200 && $code < 300) {
        return [true, 'OK'];
    }

    $json = json_decode((string) $raw, true);
    $reason = $json['error']['status'] ?? ('HTTP_' . $code);
    return [false, (string) $reason];
}
