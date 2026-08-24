# Skylink Bingwa v1.0.12 — release pack

**versionName** `1.0.12` · **versionCode** `13` · **applicationId** `com.bingwasokoni`

**Signed with the permanent production identity** — built by the `release.yml` CI
workflow from tag `v1.0.12` (run 32705920591) and published to the GitHub Release.

Signer certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
(`C=KE, L=Nairobi, O=My Bingwa, CN=My Bingwa`) — verified identical to the v1.0.9
release, so this build updates existing installs correctly on both channels.

> ⚠️ **Note on v1.0.10 and v1.0.11.** The APK/AAB sitting in those two release folders are
> signed with the local **Android debug key**
> (`3d94a46c5d74324df89831ae75f8ef338b7ed7530dacd7a1fe69221c78f80291`,
> `CN=Android Debug`), not the production identity. They were produced by a local Gradle
> build rather than by CI, so they could never have been uploaded to Play or installed as
> an update over a real install. Only v1.0.9 and this v1.0.12 carry the real signature.
> Rebuild those tags through `release.yml` if you ever need publishable copies.

## What changed in v1.0.12

### Admin push notifications now work

Sending a push from the dashboard failed with *"Something went wrong. Please try again."*
Three separate faults, all fixed:

1. **The request crashed before Firebase was ever contacted.** `PushController` called
   methods that do not exist in this codebase — `Csrf::check(string)` (the real signature
   takes a `Request`), `$v->rule()`, `$v->firstError()`, `Audit::log(named:)`,
   `Database::fetchOne()` and `Database::query()`. Each raised a fatal `Error`, and
   `index.php`'s catch-all rendered the generic 500 page, hiding the real cause. The
   controller now uses the same core APIs as every other admin module. The form also
   posted `csrf_token` while `Csrf` reads `_csrf`; it now renders `Csrf::field()`.

2. **Nothing arrived on a backgrounded phone.** The server set
   `android.notification.channel_id = "news_channel"`, but the app's channel is
   `NotificationChannels.NEWS` = `"news"`. Android 8+ silently discards a notification
   posted to a channel that does not exist. Messages are now **data-only**, so the app's
   `onMessageReceived` is the single delivery path in every app state — it posts through
   `AppNotifier` (correct channel, correct deep link) **and** records the message in the
   in-app notification centre, which the SDK-drawn notification never did.

3. **Migration `021_fcm_push.sql` could never apply.** It had no `-- @@` statement
   separators, and its `ALTER TABLE` / `CREATE INDEX` were not idempotent even though
   `register_user.php` adds the same `fcm_token` column itself. It threw, was never
   recorded, and `Migrator::run()` aborts on the first error — so it silently blocked
   every later migration too. Each schema change is now guarded through
   `information_schema` and applied with `PREPARE`.

Also in this area:

- Failed sends now report Firebase's own reason (`SENDER_ID_MISMATCH`, a rejected service
  account, an unreachable host) instead of a bare failure.
- Tokens FCM reports as `UNREGISTERED` / `NOT_FOUND` are cleared automatically.
- The app now subscribes to the `all_users` topic at startup. Previously the server's
  topic fan-out returned HTTP 200 and reached nobody; topic delivery is now counted
  separately from per-device delivery so the dashboard numbers stay honest.
- The dashboard says when the schema is missing instead of showing a confident "0 tokens".

### Branding

- **The app is called "Skylink Bingwa" again**, reverting v1.0.11 back to the name
  established in v1.0.9. Launcher label, top app bar, onboarding, Settings, Help,
  Activity, permission screen, notification channels and update copy all follow; the
  debug variant is "Skylink Bingwa Dev".
- **The v1.0.11 logo was reverted** to the v1.0.10 artwork across every launcher, round,
  adaptive, monochrome, splash, onboarding and status-bar asset, plus the logo kit.
- The name now runs through the repository structure too: the Gradle module moved from
  `my-bingwa/` to `skylink-bingwa/`; `MyBingwaApplication`, `MyBingwaFirebaseService`,
  `MyBingwaTopAppBar` and `MyBingwaBottomNav` became their `SkylinkBingwa*` equivalents;
  `ic_stat_my_bingwa` became `ic_stat_skylink_bingwa`; the logo kit and every release
  folder were renamed.

### Deliberately NOT renamed

Changing any of these breaks a live system rather than rebranding it:

| Identifier | Why it stays |
|---|---|
| `com.bingwasokoni` | Play Store identity — changing it orphans every install |
| `mybingwa.blazetechscope.com` | Live payments/API host |
| `server/mybingwa-api/` | Live cPanel directory the app calls |
| `MYBINGWA_ADMIN_CONFIG` | Environment variable set on the cPanel host |
| Firebase project `my-bingwa` + `my-bingwa-b538e0f6c645.json` | The FCM project the app is registered against |
| `all_users` | Topic subscription; existing subscribers live on Google's side |
| `mybingwa_local`, `mybingwa_notification_state`, `mybingwa_personalization`, `mybingwa_sync_meta`, … | On-device DataStore / SharedPreferences filenames. Renaming these makes every existing install look brand new — the customer loses profile, favourites, activity history and any pending order |
| `mybingwa_catalogue_sync` and the other WorkManager unique names | Renaming orphans already-scheduled background work |

## Release artifacts

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.12-play.aab` | Play bundle — **debug-signed, do not upload** | Verification only |
| `Skylink-Bingwa-v1.0.12-direct.apk` | Direct APK — **debug-signed, do not distribute** | Sideload onto a test phone |
| `*.sha256` | Checksums of the files as built | Integrity check |

## How this was built

Tag `v1.0.12` was pushed to `main`, which triggered `.github/workflows/release.yml`. That
job decodes the keystore from repository secrets into `$RUNNER_TEMP`, builds
`:app:assembleDirectRelease` and `:app:bundlePlayRelease`, deletes the keystore in an
always()-run cleanup step, and publishes the assets to the GitHub Release. The files in
this folder were downloaded from that release with `gh release download v1.0.12`.

Gates that passed before the merge to `main`:

- **Server checks** — PHP syntax on every file, admin logic tests (`php tests/run.php`),
  migration well-formedness, committed-secret scan.
- **Feature debug build** — Android unit tests and lint.

---

# POST-INSTRUCTIONS — cPanel re-upload

The admin dashboard and the customer API were both touched. Upload over the existing
files, keeping the same paths. **Do not** upload `config/config.php` or any `config.php`
— those are live, per-host, and are not in the repo.

## 1. Required — the push notification fix

These four carry the actual fix. Nothing works without them.

| Local path | cPanel destination |
|---|---|
| `server/admin-v2/app/Controllers/PushController.php` | `admin/app/Controllers/PushController.php` |
| `server/admin-v2/app/Services/FcmService.php` | `admin/app/Services/FcmService.php` |
| `server/admin-v2/app/Views/push/index.php` | `admin/app/Views/push/index.php` |
| `server/admin-v2/database/migrations/021_fcm_push.sql` | `admin/database/migrations/021_fcm_push.sql` |

## 2. Then run the migration

`021_fcm_push.sql` previously failed and was never recorded, so it is still pending — and
because `Migrator::run()` stops at the first error, anything after it is pending too.
Apply it **after** uploading the corrected file, either way:

**Nothing needs to be triggered by hand.** `Installer::autoProvision()` runs from
`index.php` on *every* request and always calls `Migrator::run()`, so simply loading any
admin page applies whatever is pending. Just open the dashboard once after uploading.

(There is no `/migrate` route — an earlier revision of this file said there was, and it
404s. If you have cPanel Terminal or SSH you can also run `php database/migrate.php` from
the `admin/` directory, but you do not need to.)

Confirm it applied, in phpMyAdmin:

```sql
SELECT filename, applied_at FROM mb_migrations ORDER BY id DESC LIMIT 5;
SHOW COLUMNS FROM mb_customers LIKE 'fcm_token';
SHOW TABLES LIKE 'mb_push_broadcasts';
```

You want `021_fcm_push.sql` listed, one `fcm_token` column, and the
`mb_push_broadcasts` table present. If it is missing, the PHP error log will carry
`[skylinkbingwa-admin] auto-migrate failed: ...` with the reason.

Expect `021_fcm_push.sql` in the "Applied" list. The rewritten migration is idempotent, so
it is safe whether or not `fcm_token` already exists on `mb_customers`.

## 3. Confirm the Firebase credential is in place

`FcmService` looks for the service-account JSON, in order:

1. the path in `fcm.service_account_file` in `config.php`
2. `admin/config/firebase-service-account.json`
3. `firebase-service-account.json` / `my-bingwa-b538e0f6c645.json` one and two levels above

Keep it **outside the web root** and point `fcm.service_account_file` at its absolute
path. The Instant Push page now prints exactly which paths it searched when it cannot
find the file.

## 4. Branding-only files (optional, but the admin UI still says the old name without them)

39 files changed for the rename alone. No behaviour change, so they can go up in the same
pass or a later one:

```
server/admin-v2/.htaccess
server/admin-v2/README.md
server/admin-v2/index.php
server/admin-v2/composer.json
server/admin-v2/config/config.sample.php
server/admin-v2/app/Controllers/AuditController.php
server/admin-v2/app/Controllers/Controller.php
server/admin-v2/app/Controllers/CustomersController.php
server/admin-v2/app/Controllers/DashboardController.php
server/admin-v2/app/Controllers/ImportController.php
server/admin-v2/app/Controllers/OffersController.php
server/admin-v2/app/Controllers/PaymentsController.php
server/admin-v2/app/Controllers/VersionsController.php
server/admin-v2/app/Core/Crypto.php
server/admin-v2/app/Core/Installer.php
server/admin-v2/app/Views/auth/forgot.php
server/admin-v2/app/Views/auth/login.php
server/admin-v2/app/Views/billboards/form.php
server/admin-v2/app/Views/errors/forbidden.php
server/admin-v2/app/Views/install/index.php
server/admin-v2/app/Views/layout.php
server/admin-v2/app/Views/notifications/form.php
server/admin-v2/app/Views/partials/sidebar.php
server/admin-v2/app/Views/publish/review.php
server/admin-v2/assets/css/app.css
server/admin-v2/assets/js/app.js
server/admin-v2/bin/import_legacy.php
server/admin-v2/database/seed.php
server/admin-v2/database/seed_data.php
server/admin-v2/tests/cases/billboards.php
server/admin-v2/tests/run.php
server/mybingwa-api/README.md
server/mybingwa-api/config.sample.php
server/mybingwa-api/lib.php
server/mybingwa-api/offers.sql
server/mybingwa-api/schema.sql
server/mybingwa-api/settings.sql
server/mybingwa-api/stk.php
server/tools/build-deploy-package.ps1
```

`server/mybingwa-api/*` goes to the API root; `server/admin-v2/*` goes under `admin/`.
The three `.sql` files changed only in a leading comment — they do **not** need to be
re-imported.

## 5. Verify end to end

1. Open the dashboard → **Instant Push**. The status card should read
   "Firebase Service Account Connected" and show a token count (not an error).
2. Install the v1.0.12 APK on a phone and complete onboarding, so its token registers.
3. Send a push with the app **in the background**. It should arrive within seconds.
4. Open the app — the same message must also be listed in the in-app notification centre.
5. Check **Recent Broadcasts** on the push page: the send should be logged with its
   delivery counts.

If a send fails, the flash message now names the real cause instead of "Something went
wrong" — read it before changing anything.
