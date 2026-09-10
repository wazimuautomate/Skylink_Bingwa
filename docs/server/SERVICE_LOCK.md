# Service lock ("Danger zone")

A single Super-Admin-only switch that suspends the whole product: while it is on, every
request the Android app makes to either server half is refused with **HTTP 503** and a
`Request Denied` body, and nothing new can be sent to a device.

It is a **server** feature end to end. Turning it on or off takes effect on the very next
request. No publish, no deploy, no new APK/AAB is involved.

---

## Where it lives

| Piece | Path |
| --- | --- |
| Admin page | `GET /danger-zone`, `POST /danger-zone/save` (`DangerZoneController`) |
| Lock state + payload (admin) | `server/admin-v2/app/Services/ServiceLock.php` |
| Lock state + payload (legacy API) | `service_lock_state()` / `deny_if_service_locked()` in `server/mybingwa-api/lib.php` |
| Stored state | `mb_settings`, keys `service_lock.*` (migration `025_service_lock.sql`) |
| Dashboard notice | `app/Views/partials/service_lock_notice.php` |

Both server halves share the same MySQL database, so the legacy API reads the very same
five rows the admin writes — there is no second switch to keep in step.

## Who can reach it

Super Admin only. `DangerZoneController` calls `Rbac::requireSuperAdmin()` on **both** the
page and the save, and the page key is deliberately **not** in `SettingsController::PAGES`,
so it cannot be granted to a partner Admin even by mistake. The sidebar item is likewise
only rendered for a Super Admin — but hiding a link is never the control; the controller is.

The dashboard notice is shown to **every** admin while the lock is on. That is the point:
it is how the account owner learns the product is blocked and why.

## What it stores

| Key | Meaning |
| --- | --- |
| `service_lock.enabled` | `1` = blocked, `0` = normal |
| `service_lock.amount` | Outstanding balance in whole KSh. `0` = do not name an amount |
| `service_lock.reason` | Free text. Empty falls back to `ServiceLock::DEFAULT_REASON` |
| `service_lock.enabled_at` | UTC datetime the switch was last turned **on** |
| `service_lock.enabled_by` | Name of the Super Admin who turned it on |

Editing the amount while already locked does **not** reset `enabled_at`.

## What a blocked client receives

HTTP `503`, `Retry-After: 3600`, `Cache-Control: no-store`, and:

```json
{
  "blocked":   true,
  "status":    "REQUEST_DENIED",
  "error":     "request_denied",
  "errorCode": "REQUEST_DENIED",
  "title":     "Request Denied",
  "message":   "<reason>. Outstanding balance: KSh 250,000.",
  "reason":    "<reason>",
  "amountKsh": 250000,
  "currency":  "KES",
  "since":     "2026-09-10 08:00:00"
}
```

The refusal is spelled three ways (`status`, `errorCode`, `error`) because the sync API
answers `{"error": ...}` and the payment API answers `{"status": ..., "errorCode": ...}`.
A client must recognise the refusal whichever endpoint it called.

## What is blocked

**Admin V2 public API** — every route on `Api\SyncController`, checked before the rate
limit: `/api/app-data`, `/api/health`, `/api/sync/manifest`, `/api/sync/resources`,
`/api/sync/resource/{key}`.

**Legacy API (`mybingwa-api`)** — every app-facing endpoint, checked immediately after
`require_app_key()`: `stk.php`, `status.php`, `withdraw.php`, `otp_request.php`,
`otp_verify.php`, `referral_summary.php`, `check_referral_code.php`, `register_user.php`,
`get_offers.php`, `get_billboards.php`, `get_config.php`, `get_sync_manifest.php`.

**Outbound** — `POST /push/send` (FCM broadcast) and `POST /publish/execute` are refused,
so nothing can be sent *to* a device either. `cron_referrals.php` does no work while
locked: no commission matures, no push, no payout. Nothing is lost — the first run after
the lock is lifted picks up everything that came due meanwhile.

## What is deliberately NOT blocked

- **`callback.php`, `b2c_result.php`, `b2c_timeout.php`** — Safaricom's server-to-server
  callbacks. They report money that has **already moved**. Refusing them would leave a real
  customer's payment unreconciled, which punishes the customer rather than the account
  owner. Since `stk.php` and `withdraw.php` are locked, no new payment can enter that path.
- **The admin panel itself.** It is how the lock is lifted.

## Failure behaviour: fail open

A database or configuration error while reading the switch returns "unlocked", on both
sides. A read that failed closed would take the product down on any transient DB fault —
including the admin page that lifts the lock, which would then be untrustworthy.

## Known edge case

A customer who completed an M-Pesa payment seconds before the lock went on cannot poll
`status.php` for the result. The payment itself is unaffected: the callback still records
it, and it appears on the admin Payments page as normal. The app simply shows its own
"could not verify" state until the lock is lifted.

## What the installed Android app shows

The app renders **its own** hard-coded error strings; it never displays server-supplied
error text, and it has no maintenance-mode screen. So a device on the current build sees
its normal failure wording ("Payment failed", "Could not reach the payment service", stale
cached offers), not the words *Request Denied*. The literal red "Request Denied" banner
would need an app-side change and a new build.

Everything is genuinely refused either way — this is about the wording on screen, not
about whether the block works.
