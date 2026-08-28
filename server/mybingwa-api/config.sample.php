<?php
/**
 * SAMPLE config. Copy this to `config.php` ON THE SERVER and fill in the real
 * values. `config.php` is git-ignored and must NEVER be committed — it holds your
 * Daraja secrets, which live ONLY on the cPanel server.
 *
 * (The .htaccess in this folder also blocks the web from downloading either file,
 * and PHP never serves its source as text.)
 */

$config = [

    // ---- Shared secret with the Android app -------------------------------
    // Any long random string. The app sends it as the "X-App-Key" header so only
    // your app can trigger an STK push. Put the SAME value in the app's
    // PAYMENTS_APP_KEY build config (GitHub secret).
    'app_key' => 'PUT_A_LONG_RANDOM_STRING_HERE',

    // ---- Daraja environment ----------------------------------------------
    // 'sandbox' while testing, 'production' when live.
    'daraja_env' => 'sandbox',

    // ---- Daraja credentials (from the Safaricom Daraja portal) ------------
    'consumer_key'    => 'PUT_CONSUMER_KEY',
    'consumer_secret' => 'PUT_CONSUMER_SECRET',

    // The Lipa na M-Pesa Online passkey for your short code.
    'passkey' => 'PUT_PASSKEY',

    // Your short code used to build the request password. For a Buy Goods (Till)
    // set this to the Head Office / store number tied to the till. For a Paybill,
    // this is the Paybill number.
    'business_shortcode' => 'PUT_SHORTCODE',

    // The number that actually RECEIVES the money.
    //  - Buy Goods (Till): your Till number.
    //  - Paybill: your Paybill number.
    'party_b' => 'PUT_TILL_NUMBER',

    // 'CustomerBuyGoodsOnline' for a Till, 'CustomerPayBillOnline' for a Paybill.
    // This is the SELF / buy-for-myself route (Till). Buy-for-another always uses
    // 'CustomerPayBillOnline' regardless of this value (see paybill_shortcode below).
    'transaction_type' => 'CustomerBuyGoodsOnline',

    // ---- Buy-for-another (Paybill) route ----------------------------------
    // When the app sends forSelf=false (route "another"), the STK is sent as a
    // Paybill payment and the AccountReference is the bundle recipient's number.
    // paybill_shortcode is the Paybill number used BOTH as BusinessShortCode and
    // in the STK password. If omitted, it falls back to business_shortcode above.
    'paybill_shortcode' => 'PUT_PAYBILL_SHORTCODE',
    // Optional: a separate passkey for the Paybill product. If omitted, the Till
    // 'passkey' above is reused. Only set this if Daraja gave you a distinct one.
    'paybill_passkey' => 'PUT_PAYBILL_PASSKEY_OPTIONAL',

    // ---- Daraja callback (result webhook) authenticity --------------------
    // IMPORTANT: Daraja STRIPS the query string from your CallbackURL, so a ?token=
    // cannot be relied on (it silently rejects every real callback). callback.php
    // therefore authenticates the webhook by SOURCE IP — Safaricom's callback block
    // (196.201.212/213/214.x, accepted in code) plus the explicit allowlist below —
    // combined with an amount cross-check. A shared-secret token is still honoured if
    // it ever survives (path or query), but it is not required.
    'callback_secret' => 'PUT_A_LONG_RANDOM_CALLBACK_TOKEN',

    // Safaricom Daraja callback source IPs — these authenticate the webhook. Keep the
    // current published list here; the code also accepts the whole 196.201.212/213/214
    // block as a resilience fallback. Empty = rely on the code's block match only.
    'callback_ip_allowlist' => [
        '196.201.214.200', '196.201.214.206', '196.201.213.114', '196.201.214.207',
        '196.201.214.208', '196.201.213.44',  '196.201.212.127', '196.201.212.138',
        '196.201.212.129', '196.201.212.136', '196.201.212.74',  '196.201.212.69',
    ],

    // Optional: if a proxy/CDN you CONTROL fronts this server, name the PHP
    // $_SERVER key that carries the real client IP (e.g. 'HTTP_X_FORWARDED_FOR').
    // Leave '' when Daraja hits this server directly — otherwise it is spoofable.
    'trusted_proxy_header' => '',

    // Public HTTPS URL where Daraja posts the result — your callback.php. A ?token=
    // is optional (Daraja usually drops it); IP auth above is what secures it. e.g.
    //   https://mybingwa.blazetechscope.com/callback.php
    'callback_url' => 'https://PUT_YOUR_DOMAIN/callback.php',

    // ---- SMS (HostPinnacle) -------------------------------------------------
    // Every SMS this app sends — referral OTPs, referral "someone joined"
    // texts, and the mocked fulfilment message below — goes through the one
    // hostpinnacle_send_sms() function in lib.php. Auth is userid+password, NOT
    // an apikey: verified live against a real account, the apikey-header mode
    // failed on every value tried while userid+password worked immediately.
    // Leave sms_userid empty to disable all SMS sending (referral OTP requests
    // then fail cleanly with SMS_UNAVAILABLE rather than pretending to send).
    'sms_api_url'   => 'https://smsportal.hostpinnacle.co.ke/SMSApi/send',
    'sms_userid'    => 'PUT_YOUR_HOSTPINNACLE_USERID',
    'sms_password'  => 'PUT_YOUR_HOSTPINNACLE_PASSWORD',
    'sms_sender_id' => 'PUT_YOUR_REGISTERED_SENDER_ID',   // MUST be registered with HostPinnacle

    // ---- Buy-for-another fulfilment SMS -----------------------------------
    // On a CONFIRMED buy-for-another payment (payer != recipient) the server sends a
    // MOCKED M-Pesa SMS whose "received from" number is the RECIPIENT (not the payer),
    // to your fulfilment phone, so your operator loads the bundle for the right line.
    'fulfilment_phone' => 'PUT_FULFILMENT_PHONE',   // your operator's phone — receives the mocked SMS
    'business_name'    => 'SkylinkBingwa',                // shown UPPERCASED inside the SMS body

    // ---- Admin panel login (admin/ folder) --------------------------------
    // Used to sign in to the offers/settings/templates manager. Change these.
    'admin_user' => 'admin',
    'admin_pass' => 'PUT_A_STRONG_ADMIN_PASSWORD',

    // ---- Fallback seller details (only used if the settings table is empty) --
    // Leave these BLANK. You set the offline Till/Paybill and support numbers from the
    // admin Support page — they are never hardcoded here.
    'paybill_number'   => '',
    'support_number'   => '',
    'support_whatsapp' => '',

    // ---- MySQL database (create it in cPanel → MySQL Databases) -----------
    'db_host' => 'localhost',
    'db_name' => 'PUT_DB_NAME',
    'db_user' => 'PUT_DB_USER',
    'db_pass' => 'PUT_DB_PASSWORD',
];

// ---- Optional overlay from the admin panel's "Payment gateway" page --------
// Lets you change the routing values above from the browser instead of editing this
// file. Both deployment layouts are tried: the cPanel one (this file in public_html/,
// the admin in public_html/admin/) and the repository one (admin-v2/ as a sibling).
// If neither resolves, or the admin holds no value for a key, NOTHING changes here —
// the literal values above stand. Keys not listed can never be overridden remotely.
// ---------------------------------------------------------------------------
// M-Pesa B2C — referral commission payouts.
//
// B2C is a SEPARATE Daraja product from the STK Push above: its own Go-Live, its
// own shortcode, and its own initiator credentials. It also pays from a FUNDED
// utility account — your Till and Paybill collections do NOT top it up
// automatically. Leave `b2c_shortcode` empty until Go-Live is complete; payouts
// simply stay queued rather than failing.
// ---------------------------------------------------------------------------

// Your B2C organisation shortcode (NOT the Till or Paybill above).
$config['b2c_shortcode'] = '';

// The API initiator username created in the Daraja portal.
$config['b2c_initiator_name'] = '';

// Preferred: paste the SecurityCredential the Daraja portal generates for your
// initiator. It is the initiator password encrypted with Safaricom's public
// certificate, already base64-encoded.
$config['b2c_security_credential'] = '';

// Alternative to the above: give the PLAIN initiator password plus a path to
// Safaricom's public certificate, and the server encrypts it on each call. Only
// used when b2c_security_credential is empty.
$config['b2c_initiator_password'] = '';
$config['b2c_cert_path'] = '';

// BusinessPayment is correct for a commission withdrawal. PromotionPayment would
// attach a congratulatory M-Pesa message and misrepresent it as a prize.
$config['b2c_command_id'] = 'BusinessPayment';

// Both MUST be https and publicly reachable, exactly like callback_url.
$config['b2c_result_url']  = 'https://PUT_YOUR_DOMAIN/b2c_result.php';
$config['b2c_timeout_url'] = 'https://PUT_YOUR_DOMAIN/b2c_timeout.php';

// Guards cron_referrals.php when cPanel cron calls it over HTTP. Invent a long
// random string. A CLI cron (`php cron_referrals.php outbox`) does not need it.
$config['cron_key'] = 'PUT_A_LONG_RANDOM_CRON_KEY';

// Firebase service-account JSON for push notifications. Leave empty to use the
// default search paths (the same file admin-v2's FcmService looks for).
$config['fcm_service_account_file'] = '';

$gw = false;
foreach ([__DIR__ . '/admin/cutover/gateway_bridge.php', __DIR__ . '/../admin-v2/cutover/gateway_bridge.php'] as $bridgePath) {
    if (is_file($bridgePath)) {
        $gw = @include $bridgePath;
        break;
    }
}
if (is_array($gw)) {
    foreach ([
        'transaction_type', 'business_shortcode', 'party_b', 'paybill_shortcode',
        'callback_url', 'fulfilment_phone', 'business_name', 'sms_api_url',
        'sms_sender_id', 'sms_userid', 'sms_password', 'daraja_env',
    ] as $k) {
        if (isset($gw[$k]) && $gw[$k] !== '') {
            $config[$k] = $gw[$k];
        }
    }
}

return $config;
