<?php
/**
 * The global service lock — the Super-Admin-only kill switch behind "Danger zone".
 *
 * When it is ON, every request the Android app makes to this server is refused with
 * HTTP 503 and a "Request Denied" body: no offers, no configuration, no sync, no
 * registration, no OTP, no purchase, no referral read, no withdrawal, no push. The
 * server also refuses to publish, so nothing new can reach a device either.
 *
 * WHAT IS DELIBERATELY *NOT* LOCKED
 * ---------------------------------
 * Safaricom's server-to-server callbacks (callback.php, b2c_result.php,
 * b2c_timeout.php). Those carry the result of money that has ALREADY moved. Refusing
 * them would strand real customers' payments as unreconciled while the lock is on —
 * the lock exists to stop new business, not to lose someone else's money. Since
 * stk.php and withdraw.php are locked, nothing new can enter that path anyway.
 *
 * WHERE THE STATE LIVES
 * ---------------------
 * Five scalars in `mb_settings`, so the legacy mybingwa-api endpoints can read exactly
 * the same switch with one query and no shared code (see `service_lock_state()` in
 * server/mybingwa-api/lib.php).
 *
 * FAIL-OPEN, ON PURPOSE
 * ---------------------
 * A database error here returns "unlocked". A read that failed closed would lock the
 * whole product out on any DB hiccup — including the admin page that turns it back
 * off — and the lock would then be impossible to lift from the UI.
 */

namespace App\Services;

use App\Core\Auth;
use App\Core\Database;
use Throwable;

final class ServiceLock
{
    public const K_ENABLED = 'service_lock.enabled';
    public const K_AMOUNT  = 'service_lock.amount';
    public const K_REASON  = 'service_lock.reason';
    public const K_AT      = 'service_lock.enabled_at';
    public const K_BY      = 'service_lock.enabled_by';

    /** The single error title every blocked client is shown. */
    public const TITLE = 'Request Denied';

    /** Used when the operator leaves the reason box empty. */
    public const DEFAULT_REASON =
        'This service has been suspended by the developer because of an unsettled '
        . 'development invoice. The app and the server stay unavailable until the '
        . 'outstanding balance is paid in full.';

    private static ?array $cache = null;

    /* ------------------------------------------------------------------ read */

    /**
     * @return array{enabled:bool, amount:int, reason:string, since:?string, by:string}
     */
    public static function state(): array
    {
        if (self::$cache !== null) {
            return self::$cache;
        }
        $defaults = ['enabled' => false, 'amount' => 0, 'reason' => self::DEFAULT_REASON, 'since' => null, 'by' => ''];
        try {
            $rows = Database::fetchAll(
                'SELECT skey, svalue FROM ' . Database::table('settings') . ' WHERE skey LIKE ?',
                ['service_lock.%']
            );
        } catch (Throwable $e) {
            return self::$cache = $defaults; // fail open — see the class comment
        }

        $raw = [];
        foreach ($rows as $row) {
            $raw[(string) $row['skey']] = (string) ($row['svalue'] ?? '');
        }
        $reason = trim($raw[self::K_REASON] ?? '');
        $since  = trim($raw[self::K_AT] ?? '');

        return self::$cache = [
            'enabled' => ($raw[self::K_ENABLED] ?? '0') === '1',
            'amount'  => max(0, (int) ($raw[self::K_AMOUNT] ?? 0)),
            'reason'  => $reason !== '' ? $reason : self::DEFAULT_REASON,
            'since'   => $since !== '' ? $since : null,
            'by'      => trim($raw[self::K_BY] ?? ''),
        ];
    }

    public static function isLocked(): bool
    {
        return self::state()['enabled'];
    }

    /**
     * The body served to every blocked client.
     *
     * It carries three spellings of the same refusal — `error`, `errorCode` and
     * `status` — because the two server halves speak slightly different dialects
     * (the sync API answers `{"error": ...}`, the payment API `{"status": ...,
     * "errorCode": ...}`) and a blocked client must recognise the refusal whichever
     * endpoint it happened to call.
     */
    public static function payload(): array
    {
        $state = self::state();
        return [
            'blocked'   => true,
            'status'    => 'REQUEST_DENIED',
            'error'     => 'request_denied',
            'errorCode' => 'REQUEST_DENIED',
            'title'     => self::TITLE,
            'message'   => self::message($state),
            'reason'    => $state['reason'],
            'amountKsh' => $state['amount'],
            'currency'  => 'KES',
            'since'     => $state['since'],
        ];
    }

    /** One human sentence: the reason, plus the outstanding amount when there is one. */
    public static function message(?array $state = null): string
    {
        $state = $state ?? self::state();
        $message = $state['reason'];
        if ($state['amount'] > 0) {
            $message .= ' Outstanding balance: KSh ' . number_format($state['amount']) . '.';
        }
        return $message;
    }

    /* ----------------------------------------------------------------- write */

    /**
     * Persist a new lock state. `$amount` of 0 means "do not name an amount"; an empty
     * `$reason` falls back to DEFAULT_REASON at read time.
     *
     * The "since" and "by" stamps are only rewritten when the switch actually flips on,
     * so editing the amount while locked does not reset when the lock started.
     */
    public static function save(bool $enabled, int $amount, string $reason): void
    {
        $wasEnabled = self::state()['enabled'];

        Settings::set(self::K_ENABLED, $enabled ? '1' : '0');
        Settings::set(self::K_AMOUNT, (string) max(0, $amount));
        Settings::set(self::K_REASON, mb_substr(trim($reason), 0, 1000));

        if ($enabled && !$wasEnabled) {
            Settings::set(self::K_AT, gmdate('Y-m-d H:i:s'));
            Settings::set(self::K_BY, mb_substr((string) (Auth::user()['name'] ?? 'Super Admin'), 0, 120));
        } elseif (!$enabled) {
            Settings::set(self::K_AT, '');
            Settings::set(self::K_BY, '');
        }

        self::$cache = null;
    }

    /** Drop the per-request cache (tests, and immediately after a save). */
    public static function invalidate(): void
    {
        self::$cache = null;
    }
}
