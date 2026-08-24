# Skylink Bingwa v1.0.15 — release pack

**versionName** `1.0.15` · **versionCode** `16` · **applicationId** `com.bingwasokoni`

**This is the first build where push notifications have a genuine chance of working.**
Built by `release.yml` from tag `v1.0.15` (CI run `32724753731`).

Verified at every layer — not just "it compiled":

- **Signer**: `apksigner` → `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  (`C=KE, L=Nairobi, O=My Bingwa`) — identical to v1.0.9. Updates cleanly.
- **Firebase config baked into the APK** (`aapt2 dump resources`):
  - `google_api_key = AIzaSyDT6yZXrTvKlBgz6D0_EEzryflhS4l3HTU`
  - `google_app_id = 1:763690457362:android:a0470835032073b96b3bcb`
  - `google_storage_bucket = my-bingwa.firebasestorage.app`
- **Those exact values tested live against Google's servers** —
  `POST firebaseinstallations.googleapis.com/v1/projects/my-bingwa/installations` with
  this API key and App ID returns **HTTP 200** and a real installation ID. This is the
  same call the Android Firebase SDK makes internally before it can ever issue an FCM
  token.

## Why every prior "fix" wasn't enough — the actual root cause

v1.0.12 shipped with a fake stub Firebase project (missing CI secret). v1.0.13 fixed
that — or so it seemed. v1.0.14 fixed a real client-side race condition. Both were
genuine, necessary fixes. **Neither could have worked**, because:

No Android app had ever actually been registered in the `my-bingwa` Firebase project.
The `google-services.json` used throughout — including the one "verified" for
v1.0.13/v1.0.14 — was itself fabricated: its `project_id` string (`my-bingwa`) matched by
coincidence, but its `project_number` (`111803005684`) never matched the real project's
(`763690457362`), and its API key was the literal placeholder
`AIzaSyDummyKeyForGoogleServicesCompilationOnly`. Confirmed by:

1. Testing that API key directly against `firebaseinstallations.googleapis.com` →
   `400 API_KEY_INVALID`.
2. Listing the live project's registered apps via the Firebase Management API
   (authenticated with the existing FCM service-account key) → **zero apps**.

Every FCM attempt on every version shipped before this one was structurally incapable of
succeeding, independent of any code correctness.

**Fixed by**: the user registered the real Android app (`com.bingwasokoni`) in Firebase
Console and provided the genuine config. A second gap surfaced immediately — the debug
build (`com.bingwasokoni.debug`) has no matching client in a single-app config, and the
`google-services` Gradle plugin **hard-fails** that build (confirmed by actually running
it, not assumed) — so a second Firebase app was registered for the debug package via the
Management API, and the merged two-client config is what's now in
`GOOGLE_SERVICES_JSON`.

## What's still unverified

Everything up to and including a real Firebase Installation ID being issued is now
proven, from outside the app, against Google's live servers. What remains untested is
the step after that — the phone's actual FCM token registering with your server
(`register_user.php`, already confirmed independently working via a CI diagnostic:
`HTTP 200 {"status":"REGISTERED"}`) and a push actually rendering on a real device. That
needs your phone.

## No server re-upload needed

Nothing under `server/` changed. The push-fix code shipped with v1.0.12; this release is
purely the Firebase app registration + config.

## Verify on your phone

1. Install `Skylink-Bingwa-v1.0.15-direct.apk` — updates cleanly over v1.0.14.
2. Open the app once with a working connection.
3. Send a push from the admin dashboard. It should report an actual delivered device
   count this time, and arrive within seconds, including backgrounded.
4. Upload `Skylink-Bingwa-v1.0.15-play.aab` to Play when ready.

If this **still** doesn't work, the next diagnostic is the admin **Customers** page —
confirm your phone number appears there with a non-empty `fcm_token` column (phpMyAdmin:
`SELECT msisdn, fcm_token, updated_at FROM mb_customers WHERE msisdn = '254XXXXXXXXX'`).
If the row exists with no token, the failure is now isolated to the phone's Firebase SDK
runtime itself (Play Services version, device-level Firebase issue) rather than any
configuration this project controls.
