<?php
/**
 * Authenticated symmetric encryption for secrets retained at rest (e.g. a user's TOTP
 * secret). AES-256-GCM using a key derived from the deployment `app_key`. This is the
 * server-side equivalent of Keystore-backed protection required by the security rules:
 * a database leak alone does not expose the plaintext without the app_key.
 */

namespace App\Core;

final class Crypto
{
    private const CIPHER = 'aes-256-gcm';

    private static function key(): string
    {
        $appKey = (string) Config::get('app_key', '');
        // Derive a fixed 32-byte key from the configured secret.
        return hash('sha256', 'skylinkbingwa-admin|' . $appKey, true);
    }

    /** Encrypt plaintext → "v1:base64(iv|tag|ciphertext)". Empty in, empty out. */
    public static function encrypt(string $plaintext): string
    {
        if ($plaintext === '') {
            return '';
        }
        $iv = random_bytes(12);
        $tag = '';
        $cipher = openssl_encrypt($plaintext, self::CIPHER, self::key(), OPENSSL_RAW_DATA, $iv, $tag);
        if ($cipher === false) {
            return '';
        }
        return 'v1:' . base64_encode($iv . $tag . $cipher);
    }

    /** Decrypt a value produced by encrypt(). Returns '' on any failure. */
    public static function decrypt(string $payload): string
    {
        if ($payload === '' || strncmp($payload, 'v1:', 3) !== 0) {
            return '';
        }
        $raw = base64_decode(substr($payload, 3), true);
        if ($raw === false || strlen($raw) < 28) {
            return '';
        }
        $iv = substr($raw, 0, 12);
        $tag = substr($raw, 12, 16);
        $cipher = substr($raw, 28);
        $plain = openssl_decrypt($cipher, self::CIPHER, self::key(), OPENSSL_RAW_DATA, $iv, $tag);
        return $plain === false ? '' : $plain;
    }
}
