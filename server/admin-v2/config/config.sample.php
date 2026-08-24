<?php
/**
 * SAMPLE configuration for Skylink Bingwa Admin V2.
 *
 * Copy this file to `config/config.php` ON THE SERVER and fill in the real values.
 * `config/config.php` is git-ignored and is blocked from web download by .htaccess.
 * You may instead place it OUTSIDE the web root and point the environment variable
 * MYBINGWA_ADMIN_CONFIG at its absolute path (recommended on cPanel).
 *
 * This config holds NO Daraja/payment secrets — those stay in the existing
 * server/mybingwa-api/config.php used by the payment endpoints. Admin V2 only reads
 * the shared `payments` table.
 */

return [

    // 'production' hides error detail and enables HSTS behaviour. Use 'staging' while testing.
    'environment' => 'production',

    // Random 64+ char string used internally (CSRF/session hardening). Generate once.
    // e.g. bin2hex(random_bytes(32)).
    'app_key' => 'CHANGE_ME_TO_A_LONG_RANDOM_STRING',

    // ---- MySQL (same database as the legacy API; admin-v2 uses the mb_ prefix) ----
    'db' => [
        'host'    => 'localhost',
        'name'    => 'PUT_DB_NAME',
        'user'    => 'PUT_DB_USER',
        'pass'    => 'PUT_DB_PASSWORD',
        'charset' => 'utf8mb4',
        'prefix'  => 'mb_',
    ],

    // ---- First Super Admin (created automatically on the first visit) ------------
    // The database provisions itself on first load. SET A PASSWORD (10+ chars) here for a
    // fully silent, zero-touch install — you then just open the admin URL and sign in.
    // If you leave it blank, a strong password is generated and written once to
    // storage/first-login-password.txt (delete that file after logging in).
    'bootstrap_admin' => [
        'name'     => 'Owner',
        'email'    => 'owner@example.com',
        'password' => '',
    ],

    // ---- Snapshot signing (app verifies published config with the PUBLIC key) -----
    // Generate a keypair ONCE, keep the private key off the repo and ideally outside
    // the web root:
    //   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out mybingwa_admin_private.pem
    //   openssl rsa -in mybingwa_admin_private.pem -pubout -out mybingwa_admin_public.pem
    // Embed the PUBLIC key in the Android app; keep the PRIVATE key here only.
    // Publishing still works without a key (checksum-only, marked unsigned).
    'signing' => [
        'algorithm'              => 'RS256',
        'private_key_path'       => '',   // absolute path to the private .pem on the server
        'private_key_passphrase' => '',
        'public_key_path'        => '',   // absolute path to the public .pem (for the health check)
    ],

    // ---- Security ---------------------------------------------------------------
    'security' => [
        // Only set if a proxy YOU control fronts this server, e.g. 'HTTP_X_FORWARDED_FOR'.
        // Leave '' when cPanel serves directly, otherwise the client IP is spoofable.
        'trusted_proxy_header' => '',
        // Optional shared key required on the public sync API (X-Sync-Key header). Leave
        // '' to serve the sync API openly (it only exposes published, app-safe data).
        'sync_api_key' => '',
    ],

    // ---- Firebase Cloud Messaging (FCM HTTP v1 API) -----------------------------
    // Path to the Firebase Service Account JSON credentials file on the server.
    // Downloaded from Firebase Console -> Project Settings -> Service accounts.
    // e.g. /home/cpaneluser/my-bingwa-b538e0f6c645.json (outside web root).
    'fcm' => [
        'service_account_file' => '', // Absolute path on server
    ],

    // ---- Sync API limits --------------------------------------------------------
    'sync' => [
        'rate_limit_per_minute' => 60,   // per client IP
        'min_client_version_code' => 1,  // reject app builds older than this
    ],
];
