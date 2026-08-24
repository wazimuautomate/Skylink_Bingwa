# Skylink Bingwa v1.0.7 — release pack

**versionName** `1.0.7` · **versionCode** `8` · **applicationId** `com.bingwasokoni`

Built by GitHub Actions "Release (signed)" run `31364855138` from tag `v1.0.7`
(commit `769e41f` on `main`). Signed with the same permanent upload key used since
v1.0.3 — no keystore exists on any local machine.

## What is in this folder

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.7-play.aab` | **Google Play bundle** (13.8 MB) | Play Console → Production → Create release → upload this |
| `Skylink-Bingwa-v1.0.7-play.aab.sha256` | Checksum for the bundle (computed locally after download — the CI job does not generate one for the AAB) | Verify before uploading |
| `Skylink-Bingwa-v1.0.7-direct.apk` | Signed APK for direct/sideload distribution (13.9 MB) | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.7-direct.apk.sha256` | Checksum for the direct APK (from CI) | Verify before distributing |

Verify either file with `sha256sum -c <file>.sha256`.

### Checked on the actual artifacts, not assumed

- `versionCode` **8**, `versionName` **1.0.7**, `applicationId` **com.bingwasokoni**,
  `minSdk` 24, `targetSdk` 36 — read back out of the built APK with `aapt dump badging`.
- **The signing identity is byte-identical to v1.0.3 and v1.0.6**: certificate SHA-256
  `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`,
  `CN=Skylink Bingwa, O=Skylink Bingwa, L=Nairobi, C=KE` — verified with `apksigner verify
  --print-certs`. That is what lets a customer move between the Play and direct
  channels with updates superseding correctly.
- **`android.permission.RECEIVE_SMS` is confirmed ABSENT from the built APK's
  permission list** (`aapt dump badging` — full list below). This is the headline
  fix in this release: the app was declined on Play production for this exact
  permission.
- The APK's direct-channel checksum matched the one CI generated.
- Both artifacts came out of the same CI job, so they are the same source at the
  same commit.

Full permission list in the shipped APK (`aapt dump badging`):

```
android.permission.INTERNET
android.permission.POST_NOTIFICATIONS
android.permission.ACCESS_NETWORK_STATE
android.permission.READ_PHONE_STATE
android.permission.REQUEST_INSTALL_PACKAGES   (direct channel only — stripped on Play)
android.permission.WAKE_LOCK                  (WorkManager)
android.permission.RECEIVE_BOOT_COMPLETED     (WorkManager)
android.permission.FOREGROUND_SERVICE         (WorkManager)
```

No `RECEIVE_SMS`, no telephony `<uses-feature>`, no SMS broadcast receiver.

Never distribute the `.aab` to end users — it is not installable.

## What ships in this version

### 1. The SMS permission is gone — fully, not just on Play

The app was declined on Google Play production for requesting `RECEIVE_SMS`. Rather
than just stripping it from the Play flavour (which is what the previous release
actually shipped, despite an internal comment claiming otherwise — the direct and
Play manifests had diverged), the entire on-device "read Safaricom bundle/balance
messages" feature is removed from **both** channels:

- The manifest permission, the `<uses-feature android:name="android.hardware.telephony">`
  declaration, and the `SmsDeliveryReceiver` broadcast receiver are gone.
- The on-device rule engine, parser and rule store (`core/sms/*`), the legacy
  message-template classes, and the SMS-rule remote sync source are deleted.
- Onboarding no longer has a "Keep track of your bundles" permission step — it now
  ends at Notifications, which stays required.
- The blocking "a permission is switched off" screen no longer mentions SMS.
- The server's entire **SMS Rules** admin module (controller, matching engine, the
  three `/sms-rules/*` views, the `SMS_RULES` sync resource, the legacy `templates`
  snapshot section it fed, and the admin's "which phone message triggers this
  notification campaign" option) is removed. A new migration
  (`020_drop_sms_rules.sql`) drops the now-unused tables; the historical migration
  013 that created them is left in place as a ledger entry, not deleted.
- `docs/PRIVACY.md` no longer describes an SMS permission.

The only permission this app asks for now is **notifications** — and it is used
more seriously as a result (see below).

### 2. The morning/evening notifications actually fire now

This was implemented in a previous release but never worked in practice. Root
cause: `SkylinkBingwaApplication.onCreate()` re-scheduled the daily engagement
notification job on **every** cold start using `ExistingWorkPolicy.REPLACE`. Since
Android always runs `Application.onCreate()` before dispatching the WorkManager job
that triggered that very cold start, the app was — on essentially every real-world
run — cancelling the notification job that was about to fire, then scheduling a
*later* one, which would in turn get cancelled the same way. It never had a real
chance to reach the user.

Fixed by switching the cold-start reschedule to `ExistingWorkPolicy.KEEP` (only the
worker's own self-chaining reschedule, which runs at the tail of a completed job,
still uses `REPLACE`) — the same pattern already used successfully by the sibling
periodic catalogue-sync job. Also:

- The morning/evening nudges now use their own notification categories (`MORNING`
  / `EVENING`, 24h cooldown) instead of sharing the connectivity `ONLINE`/`OFFLINE`
  categories — previously, an ordinary "you're back online" blip could silently
  consume the rate-limit budget the daily nudge needed.

### 3. "Buy for myself" — the number is now editable

Tapping a bundle and choosing "For my number" used to show the saved profile
number as plain, uneditable text. It is now a real editable field (mirroring the
already-editable "For another number" fields), so a wrong digit can be fixed
without leaving the checkout sheet.

## Server files to re-upload with this release

From `server/mybingwa-api/`: `get_sync_manifest.php` (SMS_RULES resource removed).
`get_sms_rules.php`, `get_templates.php` and `templates.sql` are deleted — remove
them from the live server too if they were previously uploaded.

From `server/admin-v2/`: the whole folder as usual. **Migration
`020_drop_sms_rules.sql` applies itself on the first admin request** and drops the
`sms_rules`, `sms_rule_revisions`, `sms_event_types` and `sms_pattern_types`
tables. It also disables the `sms_event` notification trigger type and removes the
`sms_rules` feature-flag row. Nothing has to be done by hand.

⚠️ **Server-side PHP was not syntax-checked with `php -l`** — no PHP interpreter
was available in this environment. The diffs were reviewed manually for structural
correctness (balanced braces, array syntax) file by file, but this is not a
substitute for actually running the code. Smoke-test the admin panel (Notifications
page, Publish/Preview, sync manifest endpoint) after uploading, before relying on
it in production.

## What to test on the phone

1. **Fresh install, onboarding.** Confirm there is no "Keep track of your bundles" /
   SMS permission step — onboarding goes straight from Notifications to the app.
2. **Buy for myself.** Tap a bundle → "For my number" → confirm the number field is
   now editable (tap it, change a digit, confirm it sticks through to checkout).
3. **Morning/evening notifications.** This is the hardest one to verify quickly
   since it's a daily, windowed background job — the concrete regression test is:
   leave the app installed and NOT opened between roughly 06:30–08:00 or
   17:00–20:00 Nairobi time on a day when the device has (for the offline slot) no
   connection, or (for the connected slot) a connection, and confirm a notification
   arrives. If you want a faster signal, you can shrink `EngagementSchedule`'s
   windows temporarily in a debug build — not recommended for this release's actual
   verification, just for a quicker manual smoke check.
4. **No SMS permission prompt anywhere**, and Android Settings → Apps → Skylink Bingwa →
   Permissions shows no SMS entry at all.
5. Everything carried over from v1.0.6 (offer selling windows, once-a-day-per-number
   blocking, in-app rating, no update prompts in the release build) is unchanged by
   this release and does not need to be re-verified unless you have reason to
   suspect a regression.

## Carried over from v1.0.6 — still verify before publishing

The deployed `server/mybingwa-api/config.php` is per-server and git-ignored. Confirm
in cPanel that `party_b` is the production Till **4063396** (not the dev **4953696**)
and that `fulfilment_phone` is **0110092715**. Both can also be set from
Admin → Payment gateway.

Play Console notes (no `RECEIVE_SMS` to declare now — this fix removes the need for
the Permissions Declaration entirely; `READ_PHONE_STATE` justification is unchanged;
no `REQUEST_INSTALL_PACKAGES` in the Play build; data-safety answers) — update the
Play Console's data-safety form to remove any previously-declared SMS access, since
this build no longer requests it.
