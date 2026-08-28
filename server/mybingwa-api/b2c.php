<?php
/**
 * M-Pesa B2C (Business to Customer) — the payout side of Daraja.
 *
 * Kept in its own file rather than bolted onto lib.php because this is the only
 * code in the business that moves money OUT, and it deserves to be read on its
 * own.
 *
 * THE ONE THING TO UNDERSTAND BEFORE CHANGING ANYTHING HERE
 *
 * b2c_payment_request() returning "accepted" does NOT mean the customer was
 * paid. It means Safaricom queued the request. The real outcome arrives later,
 * asynchronously, at the ResultURL. And critically: a timeout or a transport
 * error from this function does NOT mean the payment did not happen — the
 * request may have reached Safaricom and been queued before the connection
 * dropped.
 *
 * That is why the caller generates and PERSISTS an OriginatorConversationID
 * before calling, treats any inconclusive outcome as UNKNOWN rather than FAILED,
 * never auto-retries, and resolves UNKNOWN only through b2c_transaction_status().
 * Retrying with a fresh id is precisely how a payout system pays twice.
 *
 * @see docs/REFERRAL_COMMISSION_SPEC.md §6.6
 */

/**
 * The encrypted initiator password Daraja calls SecurityCredential.
 *
 * Two supported sources, in priority order:
 *  1. `b2c_security_credential` in config — the base64 blob generated in the
 *     Daraja portal. This is what most deployments use.
 *  2. `b2c_initiator_password` + `b2c_cert_path` — encrypt the plain initiator
 *     password with Safaricom's public certificate ourselves.
 *
 * Returns null when neither is configured, which the caller reports as a
 * configuration error rather than attempting a doomed payout.
 */
function b2c_security_credential(array $config): ?string
{
    $precomputed = trim((string) ($config['b2c_security_credential'] ?? ''));
    if ($precomputed !== '' && strpos($precomputed, 'PUT_') !== 0) {
        return $precomputed;
    }

    $password = (string) ($config['b2c_initiator_password'] ?? '');
    $certPath = (string) ($config['b2c_cert_path'] ?? '');
    if ($password === '' || $certPath === '' || !is_readable($certPath)) {
        return null;
    }

    $cert = file_get_contents($certPath);
    $publicKey = openssl_pkey_get_public($cert);
    if ($publicKey === false) {
        return null;
    }

    $encrypted = '';
    if (!openssl_public_encrypt($password, $encrypted, $publicKey, OPENSSL_PKCS1_PADDING)) {
        return null;
    }
    return base64_encode($encrypted);
}

/** True when every credential a payout needs is present. */
function b2c_configured(array $config): bool
{
    return trim((string) ($config['b2c_shortcode'] ?? '')) !== ''
        && trim((string) ($config['b2c_initiator_name'] ?? '')) !== ''
        && b2c_security_credential($config) !== null;
}

/**
 * Generate an OriginatorConversationID.
 *
 * This is OUR idempotency key for a payout. It is written to the withdrawal row
 * BEFORE the outbound call so that, whatever happens to the connection, we can
 * always ask Safaricom "what became of this exact request?" instead of guessing.
 */
function b2c_new_originator_id(int $withdrawalId): string
{
    return 'SKB-' . $withdrawalId . '-' . bin2hex(random_bytes(8));
}

/**
 * Submit a payout.
 *
 * Returns ['outcome' => 'ACCEPTED'|'REJECTED'|'INCONCLUSIVE', ...].
 *
 *   ACCEPTED     — Safaricom queued it. Move to SUBMITTED and wait for ResultURL.
 *   REJECTED     — Safaricom refused it up front, before any money moved. This is
 *                  the ONLY outcome from which it is safe to release the hold.
 *   INCONCLUSIVE — transport failure, timeout, or an unparseable reply. The money
 *                  may or may not be moving. Move to UNKNOWN and resolve it with
 *                  b2c_transaction_status(). NEVER refund on this outcome.
 */
function b2c_payment_request(
    array $config,
    string $token,
    string $originatorId,
    int $amountKsh,
    string $msisdn,
    string $remarks = 'Referral commission'
): array {
    $credential = b2c_security_credential($config);
    if ($credential === null) {
        return ['outcome' => 'REJECTED', 'error' => 'B2C_NOT_CONFIGURED'];
    }

    $payload = [
        'OriginatorConversationID' => $originatorId,
        'InitiatorName'            => (string) $config['b2c_initiator_name'],
        'SecurityCredential'       => $credential,
        // BusinessPayment is the neutral one. PromotionPayment adds a
        // congratulatory M-Pesa message, which would misrepresent a commission
        // withdrawal as a prize.
        'CommandID'                => (string) ($config['b2c_command_id'] ?? 'BusinessPayment'),
        'Amount'                   => $amountKsh,
        'PartyA'                   => (string) $config['b2c_shortcode'],
        'PartyB'                   => $msisdn,
        'Remarks'                  => mb_substr($remarks, 0, 100),
        'QueueTimeOutURL'          => (string) $config['b2c_timeout_url'],
        'ResultURL'                => (string) $config['b2c_result_url'],
        'Occasion'                 => 'Commission',
    ];

    [$code, $json, $raw] = b2c_http(
        daraja_base($config) . '/mpesa/b2c/v3/paymentrequest',
        $token,
        json_encode($payload)
    );

    if ($code === 0) {
        // Transport never completed. The request may still have landed.
        return ['outcome' => 'INCONCLUSIVE', 'error' => 'TRANSPORT_FAILURE', 'raw' => $raw];
    }

    if ($code === 200 && isset($json['ResponseCode']) && (string) $json['ResponseCode'] === '0') {
        return [
            'outcome'         => 'ACCEPTED',
            'conversation_id' => (string) ($json['ConversationID'] ?? ''),
            'raw'             => $raw,
        ];
    }

    // A 4xx with a Daraja errorCode is a genuine up-front refusal: validation
    // failed, so nothing was queued and the hold can be released. A 5xx is not
    // safe to read that way — Safaricom may have accepted it internally.
    if ($code >= 400 && $code < 500 && !empty($json['errorCode'])) {
        return [
            'outcome' => 'REJECTED',
            'error'   => (string) $json['errorCode'],
            'desc'    => (string) ($json['errorMessage'] ?? ''),
            'raw'     => $raw,
        ];
    }

    return ['outcome' => 'INCONCLUSIVE', 'error' => 'HTTP_' . $code, 'raw' => $raw];
}

/**
 * Ask Safaricom what actually happened to a payout.
 *
 * This is the ONLY way an UNKNOWN withdrawal is ever resolved. Like the payment
 * request itself the reply is asynchronous — the real answer arrives at the
 * ResultURL — so this function's job is to trigger that answer, and the
 * reconciler's job is to keep asking until one arrives.
 */
function b2c_transaction_status(array $config, string $token, string $originatorId, string $transactionId = ''): array
{
    $credential = b2c_security_credential($config);
    if ($credential === null) {
        return ['outcome' => 'REJECTED', 'error' => 'B2C_NOT_CONFIGURED'];
    }

    $payload = [
        'Initiator'                => (string) $config['b2c_initiator_name'],
        'SecurityCredential'       => $credential,
        'CommandID'                => 'TransactionStatusQuery',
        'TransactionID'            => $transactionId,
        'OriginatorConversationID' => $originatorId,
        'PartyA'                   => (string) $config['b2c_shortcode'],
        'IdentifierType'           => '4', // organisation shortcode
        'ResultURL'                => (string) $config['b2c_result_url'],
        'QueueTimeOutURL'          => (string) $config['b2c_timeout_url'],
        'Remarks'                  => 'Reconciliation',
        'Occasion'                 => 'Reconciliation',
    ];

    [$code, $json, $raw] = b2c_http(
        daraja_base($config) . '/mpesa/transactionstatus/v1/query',
        $token,
        json_encode($payload)
    );

    if ($code === 200 && isset($json['ResponseCode']) && (string) $json['ResponseCode'] === '0') {
        return ['outcome' => 'ACCEPTED', 'raw' => $raw];
    }
    return ['outcome' => 'INCONCLUSIVE', 'error' => 'HTTP_' . $code, 'raw' => $raw];
}

/**
 * Current utility-account balance.
 *
 * B2C pays from the funded utility account; Till and Paybill collections do NOT
 * top it up automatically. The float monitor uses this so payouts start queueing
 * with an honest message before the account runs dry, instead of failing
 * mysteriously at 2am.
 */
function b2c_account_balance(array $config, string $token): array
{
    $credential = b2c_security_credential($config);
    if ($credential === null) {
        return ['outcome' => 'REJECTED', 'error' => 'B2C_NOT_CONFIGURED'];
    }

    $payload = [
        'Initiator'          => (string) $config['b2c_initiator_name'],
        'SecurityCredential' => $credential,
        'CommandID'          => 'AccountBalance',
        'PartyA'             => (string) $config['b2c_shortcode'],
        'IdentifierType'     => '4',
        'Remarks'            => 'Float check',
        'QueueTimeOutURL'    => (string) $config['b2c_timeout_url'],
        'ResultURL'          => (string) $config['b2c_result_url'],
    ];

    [$code, $json, $raw] = b2c_http(
        daraja_base($config) . '/mpesa/accountbalance/v1/query',
        $token,
        json_encode($payload)
    );

    if ($code === 200 && isset($json['ResponseCode']) && (string) $json['ResponseCode'] === '0') {
        return ['outcome' => 'ACCEPTED', 'raw' => $raw];
    }
    return ['outcome' => 'INCONCLUSIVE', 'error' => 'HTTP_' . $code, 'raw' => $raw];
}

/**
 * POST JSON to Daraja. Returns [httpCode, decodedJson, rawBody].
 *
 * httpCode 0 means the transport never completed — the caller MUST treat that as
 * inconclusive rather than as a failure.
 */
function b2c_http(string $url, string $token, string $body): array
{
    $ch = curl_init($url);
    curl_setopt_array($ch, [
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_POST           => true,
        CURLOPT_POSTFIELDS     => $body,
        CURLOPT_HTTPHEADER     => [
            'Authorization: Bearer ' . $token,
            'Content-Type: application/json',
        ],
        CURLOPT_TIMEOUT        => 45,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_SSL_VERIFYPEER => true,
        CURLOPT_SSL_VERIFYHOST => 2,
    ]);
    $raw = curl_exec($ch);
    $code = (int) curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $err = curl_error($ch);
    curl_close($ch);

    if ($raw === false) {
        return [0, [], 'curl: ' . $err];
    }
    $json = json_decode((string) $raw, true);
    return [$code, is_array($json) ? $json : [], (string) $raw];
}

/**
 * Pull the useful fields out of a B2C ResultURL callback.
 *
 * Safaricom delivers the interesting values as a ResultParameters name/value
 * list rather than named JSON fields, so this flattens it.
 */
function b2c_parse_result(array $payload): array
{
    $result = $payload['Result'] ?? [];
    $params = [];
    $items = $result['ResultParameters']['ResultParameter'] ?? [];
    if (isset($items['Key'])) {
        $items = [$items]; // A single parameter arrives unwrapped.
    }
    foreach ($items as $item) {
        if (isset($item['Key'])) {
            $params[(string) $item['Key']] = $item['Value'] ?? null;
        }
    }

    return [
        'originator_id'   => (string) ($result['OriginatorConversationID'] ?? ''),
        'conversation_id' => (string) ($result['ConversationID'] ?? ''),
        'result_code'     => isset($result['ResultCode']) ? (string) $result['ResultCode'] : null,
        'result_desc'     => (string) ($result['ResultDesc'] ?? ''),
        'transaction_id'  => (string) ($result['TransactionID'] ?? ''),
        'receipt'         => (string) ($params['TransactionReceipt'] ?? $params['ReceiptNo'] ?? ''),
        'amount'          => $params['TransactionAmount'] ?? null,
        'charge'          => $params['B2CChargesPaidAccountAvailableFunds'] ?? null,
        'params'          => $params,
    ];
}
