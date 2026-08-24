<?php
/**
 * Shared helpers: JSON output, app-key auth, Daraja calls and result mapping.
 * Included by stk.php, status.php and callback.php.
 */

/** Send a JSON response and stop. */
function json_out(array $data, int $code = 200): void
{
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode($data);
    exit;
}

/**
 * Reject the request unless it carries the correct X-App-Key header.
 * Constant-time compare. If app_key is not configured we FAIL CLOSED (401) so a
 * blank/placeholder secret can never be bypassed with an empty header.
 */
function require_app_key(array $config): void
{
    $expected = (string) ($config['app_key'] ?? '');
    $sent     = (string) ($_SERVER['HTTP_X_APP_KEY'] ?? '');
    if ($expected === '' || !hash_equals($expected, $sent)) {
        json_out(['status' => 'PAYMENT_FAILED', 'errorCode' => 'UNAUTHORISED'], 401);
    }
}

/**
 * The latest PUBLISHED app snapshot from the admin (Admin V2 writes it into the SAME
 * database under the mb_ prefix). Returns the decoded array, or null if the admin is
 * not installed / nothing is published yet — so the app-facing endpoints below can
 * serve exactly what the owner published, and otherwise fall back to the legacy tables.
 */
function published_snapshot(PDO $pdo): ?array
{
    try {
        $row = $pdo->query(
            'SELECT snapshot_json FROM mb_configuration_releases ORDER BY version DESC LIMIT 1'
        )->fetch();
        if (!$row || empty($row['snapshot_json'])) {
            return null;
        }
        $decoded = json_decode((string) $row['snapshot_json'], true);
        return is_array($decoded) ? $decoded : null;
    } catch (Throwable $e) {
        return null; // mb_configuration_releases absent → legacy fallback
    }
}

/**
 * The authoritative price for an offer, in KSh, or null when the offer is not
 * currently sellable.
 *
 * The SERVER decides the price — the app's amount is never trusted (CLAUDE.md §7).
 * Resolution order, most authoritative first:
 *
 *   1. The published admin snapshot (`mb_configuration_releases`). This is exactly
 *      what `get_offers.php` serves to the app, so what the customer sees on the
 *      card is what the STK charges. An offer the owner un-published is NOT here,
 *      so it is no longer payable.
 *   2. The legacy unprefixed `offers` table (`active = 1`), for installs that
 *      predate the admin panel.
 *   3. [$fallback], the static `offers.php` map — used only when neither table can
 *      be read (fresh install, DB hiccup), so payments never break outright.
 *
 * Keeping this in step with `get_offers.php` is what stops the two from drifting:
 * before this existed, editing a price in the admin changed the displayed price but
 * not the charged one, and the callback's amount cross-check then held the customer's
 * real payment as an unconfirmed mismatch.
 */
function offer_price(PDO $pdo, string $offerId, array $fallback): ?int
{
    // 1. Published admin snapshot — the same source get_offers.php serves.
    $snap = published_snapshot($pdo);
    if ($snap !== null && !empty($snap['offers'])) {
        foreach ($snap['offers'] as $o) {
            if ((string) ($o['id'] ?? '') === $offerId) {
                $price = (int) ($o['price'] ?? 0);
                return $price > 0 ? $price : null;
            }
        }
        // A published catalogue exists and does not contain this offer → not sellable.
        return null;
    }

    // 2. Legacy active offers table.
    try {
        $stmt = $pdo->prepare('SELECT price FROM offers WHERE offer_id = ? AND active = 1 LIMIT 1');
        $stmt->execute([$offerId]);
        $row = $stmt->fetch();
        if ($row !== false) {
            $price = (int) $row['price'];
            return $price > 0 ? $price : null;
        }
        // The table exists but has no active row for this id → not sellable.
        return null;
    } catch (Throwable $e) {
        // No offers table at all → fall through to the static map.
    }

    // 3. Static offers.php map (last resort, never blocks a payment on a fresh install).
    $price = (int) ($fallback[$offerId] ?? 0);
    return $price > 0 ? $price : null;
}

// ---------------------------------------------------------------------------
// Purchase eligibility: Safaricom's own selling rules, enforced on the server.
//
// The app already shows both of these and blocks the button, but the app is not
// the authority: an older build, a replayed request or a hand-crafted call must
// not be able to take money for a bundle Safaricom will not deliver. Both checks
// run before the STK push is fired, so no money moves when they refuse.
// ---------------------------------------------------------------------------

/** A stored TIME / "HH:MM" as "HH:MM", or '' when there is no usable value. */
function hhmm_or_empty($time): string
{
    $text = trim((string) ($time ?? ''));
    if ($text === '') {
        return '';
    }
    $parts = explode(':', $text);
    $h = isset($parts[0]) ? (int) $parts[0] : -1;
    $m = isset($parts[1]) ? (int) $parts[1] : 0;
    if ($h < 0 || $h > 24 || $m < 0 || $m > 59) {
        return '';
    }
    return sprintf('%02d:%02d', $h, $m);
}

/** "HH:MM" as minutes past midnight, or null when absent/unusable. */
function hhmm_minutes($time): ?int
{
    $text = hhmm_or_empty($time);
    if ($text === '') {
        return null;
    }
    $parts = explode(':', $text);
    return ((int) $parts[0]) * 60 + (int) $parts[1];
}

/**
 * The published rules for one offer: its time-of-day selling window and its
 * per-day policy. Resolution order mirrors offer_price() exactly — published
 * snapshot first, then the legacy `offers` table — so what the app was shown and
 * what the server enforces can never come from different places.
 *
 * @return array{availableFrom:?int, availableTo:?int, policy:string, maxPerDay:?int}
 */
function offer_rules(PDO $pdo, string $offerId): array
{
    $none = ['availableFrom' => null, 'availableTo' => null, 'policy' => '', 'maxPerDay' => null];

    $snap = published_snapshot($pdo);
    if ($snap !== null && !empty($snap['offers'])) {
        foreach ($snap['offers'] as $o) {
            if ((string) ($o['id'] ?? '') === $offerId) {
                $max = $o['maxPerDay'] ?? null;
                return [
                    'availableFrom' => hhmm_minutes($o['availableFrom'] ?? null),
                    'availableTo'   => hhmm_minutes($o['availableTo'] ?? null),
                    'policy'        => (string) ($o['policy'] ?? ($o['dailyRule'] ?? '')),
                    'maxPerDay'     => $max === null ? null : (int) $max,
                ];
            }
        }
        return $none;
    }

    try {
        $stmt = $pdo->prepare(
            'SELECT daily_rule, available_from, available_to FROM offers WHERE offer_id = ? AND active = 1 LIMIT 1'
        );
        $stmt->execute([$offerId]);
        $row = $stmt->fetch();
        if ($row !== false) {
            return [
                'availableFrom' => hhmm_minutes($row['available_from'] ?? null),
                'availableTo'   => hhmm_minutes($row['available_to'] ?? null),
                'policy'        => (string) ($row['daily_rule'] ?? ''),
                'maxPerDay'     => null,
            ];
        }
    } catch (Throwable $e) {
        // Column or table missing (an install predating selling windows) → no rules,
        // which keeps every existing offer buyable exactly as it is today.
    }
    return $none;
}

/**
 * Is the offer inside its time-of-day selling window right now?
 *
 * Evaluated on the Nairobi wall clock, never the server's timezone, because the
 * window is a customer-facing "5pm to 11pm". A window whose start is later than
 * its end crosses midnight (22:00 → 02:00) and is open on both sides of it. No
 * window (either end missing) is always open, so nothing changes for the offers
 * Safaricom sells all day.
 */
function offer_window_open(array $rules, ?int $nowMinuteOverride = null): bool
{
    $from = $rules['availableFrom'] ?? null;
    $to   = $rules['availableTo'] ?? null;
    if ($from === null || $to === null || $from === $to) {
        return true;
    }
    if ($nowMinuteOverride !== null) {
        $now = $nowMinuteOverride;
    } else {
        $nbo = new DateTimeImmutable('now', new DateTimeZone('Africa/Nairobi'));
        $now = ((int) $nbo->format('G')) * 60 + (int) $nbo->format('i');
    }
    if ($from < $to) {
        return $now >= $from && $now < $to;
    }
    return $now >= $from || $now < $to;
}

/** The window as the customer-facing "5:00 PM to 11:00 PM", for the error message. */
function offer_window_label(array $rules): string
{
    $from = $rules['availableFrom'] ?? null;
    $to   = $rules['availableTo'] ?? null;
    if ($from === null || $to === null) {
        return '';
    }
    $fmt = function (int $minutes): string {
        $m = (($minutes % 1440) + 1440) % 1440;
        return date('g:i A', mktime(intdiv($m, 60), $m % 60, 0, 1, 1, 2000));
    };
    return $fmt($from) . ' to ' . $fmt($to);
}

/**
 * The start and end of "today in Nairobi", expressed on the DATABASE clock so they
 * can be compared against `payments.created_at` (written with NOW(), i.e. MySQL's
 * own timezone).
 *
 * The shift between the two clocks is measured rather than assumed: PHP's timezone,
 * MySQL's timezone and Nairobi are three independent settings on shared hosting,
 * and guessing wrong would silently move the daily reset off midnight.
 *
 * @return array{0:string, 1:string} [startInclusive, endExclusive] as 'Y-m-d H:i:s'
 */
function nairobi_day_bounds_db(PDO $pdo): array
{
    $utc = new DateTimeZone('UTC');
    $nboNow = new DateTimeImmutable('now', new DateTimeZone('Africa/Nairobi'));
    // Both clocks re-read as if they were UTC, so subtracting them gives the pure
    // wall-clock offset between them, whatever either timezone actually is.
    $nboWall = new DateTimeImmutable($nboNow->format('Y-m-d H:i:s'), $utc);

    $shift = 0;
    try {
        $dbNowText = (string) $pdo->query('SELECT NOW()')->fetchColumn();
        if ($dbNowText !== '') {
            $dbWall = new DateTimeImmutable($dbNowText, $utc);
            $shift = $dbWall->getTimestamp() - $nboWall->getTimestamp();
        }
    } catch (Throwable $e) {
        $shift = 0; // Unreadable clock → assume the DB agrees with Nairobi wall time.
    }
    // Round to the nearest minute: NOW() and PHP's clock differ by fractions of a
    // second, which must not leak into the day boundary.
    $shift = (int) (round($shift / 60) * 60);

    $startWall = new DateTimeImmutable($nboNow->format('Y-m-d') . ' 00:00:00', $utc);
    $endWall = $startWall->modify('+1 day');
    $apply = function (DateTimeImmutable $t) use ($shift): string {
        return $t->modify(($shift >= 0 ? '+' : '-') . abs($shift) . ' seconds')->format('Y-m-d H:i:s');
    };

    return [$apply($startWall), $apply($endWall)];
}

/**
 * How many times this recipient has already been given this offer today (Nairobi
 * day). Counts confirmed payments, plus requests started in the last few minutes,
 * so a customer with an M-Pesa prompt still on their screen cannot be sent a
 * second one for the same once-a-day bundle.
 *
 * The count resets by itself at Nairobi midnight because the day boundary — not a
 * stored flag — decides: a purchase at 23:58 stops counting at 00:00.
 */
function recipient_purchases_today(PDO $pdo, string $offerId, string $recipient): int
{
    $msisdn = preg_replace('/\D/', '', $recipient);
    if ($msisdn === '') {
        return 0;
    }
    // Compare the last 9 digits so 0712…, 254712… and +254712… are one number.
    $tail = substr($msisdn, -9);
    $bounds = nairobi_day_bounds_db($pdo);

    try {
        $stmt = $pdo->prepare(
            "SELECT COUNT(*) FROM payments
              WHERE offer_id = ?
                AND RIGHT(REPLACE(REPLACE(recipient, '+', ''), ' ', ''), 9) = ?
                AND created_at >= ? AND created_at < ?
                AND (status = 'PAYMENT_CONFIRMED'
                     OR (status = 'PAYMENT_REQUESTED' AND created_at >= (NOW() - INTERVAL 10 MINUTE)))"
        );
        $stmt->execute([$offerId, $tail, $bounds[0], $bounds[1]]);
        return (int) $stmt->fetchColumn();
    } catch (Throwable $e) {
        // Never let a counting query be the thing that blocks a legitimate purchase.
        return 0;
    }
}

/**
 * How many purchases per recipient per Nairobi day this offer allows, or null for
 * "no limit". Understands both the v2 policy names and the v1 rule name the
 * shipped app uses, so a snapshot from either era is enforced correctly.
 */
function offer_daily_allowance(array $rules): ?int
{
    $policy = strtoupper((string) ($rules['policy'] ?? ''));
    $max = $rules['maxPerDay'] ?? null;
    if ($policy === 'ONCE_PER_RECIPIENT_PER_DAY' || $policy === 'ONCE_PER_DAY') {
        return 1;
    }
    if ($policy === 'MAX_PER_RECIPIENT_PER_DAY') {
        return ($max !== null && (int) $max > 0) ? (int) $max : 1;
    }
    return null;
}

// ---------------------------------------------------------------------------
// Callback authenticity (Daraja cannot send custom headers, so we gate the
// CallbackURL with a shared-secret path token + an optional source-IP allowlist).
// ---------------------------------------------------------------------------

/** Ack a callback we deliberately will NOT act on. 200 so Daraja stops retrying. */
function callback_ack_ignore(): void
{
    json_out(['ResultCode' => 0, 'ResultDesc' => 'ignored']);
}

/**
 * True if the callback carries the expected shared-secret token. Daraja STRIPS the
 * query string from the CallbackURL but preserves the URL PATH, so we accept the
 * token from PATH_INFO (callback.php/<secret>) as well as ?token=<secret>. In
 * practice Daraja drops both for a plain CallbackURL, which is why IP auth below is
 * the reliable path.
 */
function callback_token_ok(array $config): bool
{
    $expected = (string) ($config['callback_secret'] ?? '');
    if ($expected === '') {
        return false;
    }
    $pathToken  = ltrim((string) ($_SERVER['PATH_INFO'] ?? ''), '/');
    $queryToken = (string) ($_GET['token'] ?? '');
    return ($pathToken !== '' && hash_equals($expected, $pathToken))
        || ($queryToken !== '' && hash_equals($expected, $queryToken));
}

/**
 * Safaricom's Daraja result callbacks originate from a small, well-known block of
 * IPs (196.201.212.x / 196.201.213.x / 196.201.214.x). Because Daraja cannot send a
 * custom header AND strips the query string, the SOURCE IP is the dependable
 * authenticator for the webhook — combined with the amount cross-check in
 * callback.php, this is the standard, secure Daraja approach.
 */
function is_safaricom_callback_ip(string $ip): bool
{
    foreach (['196.201.212.', '196.201.213.', '196.201.214.'] as $prefix) {
        if (strpos($ip, $prefix) === 0) {
            return true;
        }
    }
    return false;
}

/**
 * Authenticate the result callback. ANY one is sufficient (the amount cross-check in
 * callback.php is the final guard):
 *   1) a shared-secret token that survived in the path/query, OR
 *   2) the request coming from a Safaricom callback IP, OR
 *   3) an explicit operator IP allowlist (config `callback_ip_allowlist`).
 * This fixes the real-world case where Daraja drops the ?token= we used to require.
 */
function callback_authenticated(array $config): bool
{
    if (callback_token_ok($config)) {
        return true;
    }
    $ip = client_ip($config);
    if (is_safaricom_callback_ip($ip)) {
        return true;
    }
    $allow = $config['callback_ip_allowlist'] ?? [];
    return is_array($allow) && count($allow) > 0 && in_array($ip, $allow, true);
}

/**
 * Resolve the client IP, honouring a configured trusted proxy header if set.
 * `trusted_proxy_header` is a PHP $_SERVER key such as 'HTTP_X_FORWARDED_FOR'.
 * Only set it when a proxy you control fronts this server, otherwise the header
 * is client-spoofable.
 */
function client_ip(array $config): string
{
    $header = (string) ($config['trusted_proxy_header'] ?? '');
    if ($header !== '' && !empty($_SERVER[$header])) {
        // A forwarded header may be "client, proxy1, proxy2" — take the first hop.
        $parts = explode(',', (string) $_SERVER[$header]);
        return trim($parts[0]);
    }
    return (string) ($_SERVER['REMOTE_ADDR'] ?? '');
}

/** True if the source IP is allowed. An empty allowlist means "allow all". */
function callback_ip_allowed(array $config): bool
{
    $allow = $config['callback_ip_allowlist'] ?? [];
    if (!is_array($allow) || count($allow) === 0) {
        return true;
    }
    return in_array(client_ip($config), $allow, true);
}

/**
 * Decide the payment route from the app's request body.
 * Preference order: explicit `forSelf` boolean, then a `route` string
 * ("self"/"another"), then default to self (the original Till behaviour).
 */
function stk_is_self(array $body): bool
{
    if (array_key_exists('forSelf', $body)) {
        return (bool) filter_var($body['forSelf'], FILTER_VALIDATE_BOOLEAN);
    }
    $route = strtolower(trim((string) ($body['route'] ?? '')));
    if ($route === 'another' || $route === 'other') {
        return false;
    }
    // "self", "", or anything unrecognised → keep the backward-compatible Till path.
    return true;
}

/** The Daraja base host for the configured environment. */
function daraja_base(array $config): string
{
    return $config['daraja_env'] === 'production'
        ? 'https://api.safaricom.co.ke'
        : 'https://sandbox.safaricom.co.ke';
}

/** Small cURL POST/GET helper returning [httpCode, decodedJsonOrNull]. */
function http_json(string $method, string $url, array $headers, ?string $body = null): array
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_CUSTOMREQUEST  => $method,
        CURLOPT_HTTPHEADER     => $headers,
        CURLOPT_TIMEOUT        => 30,
    ]);
    if ($body !== null) {
        curl_setopt($ch, CURLOPT_POSTFIELDS, $body);
    }
    $raw  = curl_exec($ch);
    $code = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    curl_close($ch);
    return [$code, $raw ? json_decode($raw, true) : null];
}

/** Get a Daraja OAuth access token, or null on failure. */
function daraja_token(array $config): ?string
{
    $auth = base64_encode($config['consumer_key'] . ':' . $config['consumer_secret']);
    [$code, $json] = http_json(
        'GET',
        daraja_base($config) . '/oauth/v1/generate?grant_type=client_credentials',
        ['Authorization: Basic ' . $auth]
    );
    return ($code === 200 && !empty($json['access_token'])) ? $json['access_token'] : null;
}

/**
 * Send an STK push. Returns the decoded Daraja response, or a synthetic
 * ['errorCode' => ...] array on transport failure (never a bare null, so callers
 * can report a clean status instead of tripping over a null offset).
 *
 * $route selects the M-Pesa product:
 *   'self'    → Till / Buy-Goods (CustomerBuyGoodsOnline), PartyB = configured Till.
 *   'another' → Paybill (CustomerPayBillOnline), PartyB = Paybill shortcode,
 *               AccountReference = the recipient MSISDN.
 * Defaults to 'self' so existing callers keep the original behaviour unchanged.
 * The STK Password ALWAYS uses the same shortcode set as BusinessShortCode.
 */
function daraja_stk_push(
    array $config,
    string $token,
    int $amount,
    string $payerMsisdn,
    string $accountRef,
    string $route = 'self'
): ?array {
    $isAnother = ($route === 'another');

    // Shortcode used both as BusinessShortCode and inside the password hash.
    $shortcode = $isAnother
        ? (string) ($config['paybill_shortcode'] ?? $config['business_shortcode'])
        : (string) $config['business_shortcode'];

    // Passkey may be overridden for the Paybill product, else reuse the Till passkey.
    $passkey = $isAnother
        ? (string) ($config['paybill_passkey'] ?? $config['passkey'])
        : (string) $config['passkey'];

    $txType = $isAnother
        ? 'CustomerPayBillOnline'
        : (string) ($config['transaction_type'] ?? 'CustomerBuyGoodsOnline');

    // For a Paybill the money party IS the paybill shortcode; for a Till it is the
    // configured Buy-Goods number (party_b).
    $partyB = $isAnother ? $shortcode : (string) $config['party_b'];

    $timestamp = date('YmdHis');
    $password  = base64_encode($shortcode . $passkey . $timestamp);

    $payload = [
        'BusinessShortCode' => $shortcode,
        'Password'          => $password,
        'Timestamp'         => $timestamp,
        'TransactionType'   => $txType,
        'Amount'            => $amount,
        'PartyA'            => $payerMsisdn,
        'PartyB'            => $partyB,
        'PhoneNumber'       => $payerMsisdn,
        'CallBackURL'       => $config['callback_url'],
        'AccountReference'  => substr($accountRef, 0, 12),
        'TransactionDesc'   => 'Skylink Bingwa bundle',
    ];

    [$httpCode, $json] = http_json(
        'POST',
        daraja_base($config) . '/mpesa/stkpush/v1/processrequest',
        ['Authorization: Bearer ' . $token, 'Content-Type: application/json'],
        json_encode($payload)
    );

    // Transport-level failure (no/invalid JSON) → return a clean error shape.
    if (!is_array($json)) {
        return ['errorCode' => 'STK_TRANSPORT_ERROR', 'httpCode' => $httpCode];
    }
    return $json;
}

/** Query the status of an STK push (fallback when the callback is slow/absent). */
function daraja_stk_query(array $config, string $token, string $checkoutId): ?array
{
    $timestamp = date('YmdHis');
    $password  = base64_encode($config['business_shortcode'] . $config['passkey'] . $timestamp);

    [, $json] = http_json(
        'POST',
        daraja_base($config) . '/mpesa/stkpushquery/v1/query',
        ['Authorization: Bearer ' . $token, 'Content-Type: application/json'],
        json_encode([
            'BusinessShortCode' => $config['business_shortcode'],
            'Password'          => $password,
            'Timestamp'         => $timestamp,
            'CheckoutRequestID' => $checkoutId,
        ])
    );
    return $json;
}

/**
 * Map an M-Pesa ResultCode to one of the app's status strings.
 *   0    → PAYMENT_CONFIRMED
 *   1032 → CANCELLED (customer cancelled the prompt)
 *   1037 → TIMED_OUT (no response / could not be reached)
 *   any other numeric code → PAYMENT_FAILED (e.g. 1 insufficient, 2001 wrong PIN)
 */
function map_result_code($resultCode): string
{
    switch ((string) $resultCode) {
        case '0':    return 'PAYMENT_CONFIRMED';
        case '1032': return 'CANCELLED';
        case '1037': return 'TIMED_OUT';
        default:     return 'PAYMENT_FAILED';
    }
}

// ---------------------------------------------------------------------------
// Buy-for-another fulfilment signal (docs/"Buy For Another Number - Implementation
// Spec.md"). When someone pays for a DIFFERENT number, the real M-Pesa SMS the owner
// receives names the PAYER — the wrong line to serve. So on a confirmed
// buy-for-another payment we build a mocked M-Pesa-style SMS whose "received from"
// number is the RECIPIENT and send it to the fulfilment phone, so the operator loads
// the bundle for the right line. This is never sent for self-purchases.
// ---------------------------------------------------------------------------

/**
 * Build the mocked M-Pesa confirmation text. Byte-for-byte reproduction of the
 * Safaricom format quirks (see the spec): "Confirmed.on" has no space, no space
 * between AM/PM and "Ksh", day/month unpadded, 2-digit year, hour unpadded / minute
 * padded, amount always 2 dp, recipient as 254XXXXXXXXX, business name uppercased.
 */
function build_mocked_mpesa_message(string $receipt, $amount, string $recipient, string $business): string
{
    $t = time() + 3 * 3600;               // Kenya = UTC+3, no DST
    $day    = (int) gmdate('j', $t);      // not zero-padded
    $month  = (int) gmdate('n', $t);      // not zero-padded
    $year   = gmdate('y', $t);            // 2 digits
    $hour24 = (int) gmdate('G', $t);
    $ampm   = $hour24 >= 12 ? 'PM' : 'AM';
    $hour   = $hour24 % 12;
    if ($hour === 0) {
        $hour = 12;                       // midnight/noon → 12, not padded
    }
    $min = gmdate('i', $t);               // zero-padded

    $date = $day . '/' . $month . '/' . $year;
    $time = $hour . ':' . $min . ' ' . $ampm;
    $amt  = number_format((float) $amount, 2, '.', '');   // 2 dp, no thousands sep

    // recipient → bare national digits, then prefix 254.
    $num = preg_replace('/\D/', '', $recipient);
    $num = preg_replace('/^254/', '', $num);
    $num = preg_replace('/^0/', '', $num);

    $biz = strtoupper($business);

    return $receipt . ' Confirmed.on ' . $date . ' at ' . $time . 'Ksh' . $amt
        . ' received from 254' . $num . ' ' . $biz
        . '. New Account balance is Ksh0.00. Transaction cost, Ksh0.00.';
}

/**
 * Send the mocked M-Pesa SMS to the fulfilment phone via the SMS provider. Best-effort:
 * returns true/false and NEVER throws, so a callback still returns 200 to Daraja even
 * if the SMS provider is down. Skips quietly when SMS is not configured.
 */
function send_mocked_mpesa_sms(array $config, string $receipt, int $amount, string $recipient): bool
{
    $phone    = (string) ($config['fulfilment_phone'] ?? '');
    $apiKey   = (string) ($config['sms_api_key'] ?? '');
    $apiUrl   = (string) ($config['sms_api_url'] ?? 'https://sms.blazetechscope.com/v1/bulksms');
    $senderId = (string) ($config['sms_sender_id'] ?? 'MYBINGWA');
    $business = (string) ($config['business_name'] ?? 'SkylinkBingwa');
    if ($phone === '' || $apiKey === '') {
        return false;   // not configured yet → skip quietly (no fatal)
    }

    $message = build_mocked_mpesa_message($receipt, $amount, $recipient, $business);

    [$code, $json] = http_json(
        'POST',
        $apiUrl,
        ['Content-Type: application/json', 'Accept: application/json'],
        json_encode([
            'message'   => $message,
            'phones'    => [$phone],
            'sender_id' => $senderId,
            'api_key'   => $apiKey,
        ])
    );

    // Treat common success shapes as success; otherwise false (caller ignores it).
    if ($code >= 200 && $code < 300) {
        if (!is_array($json)) {
            return true;   // 2xx with a non-JSON body is still a send
        }
        return ($json['status'] ?? null) === 'success'
            || ($json['success'] ?? null) === true
            || (int) ($json['response-code'] ?? 0) === 200
            || (int) ($json['data']['statusCode'] ?? 0) === 200
            || true;       // 2xx is good enough; providers vary
    }
    return false;
}
