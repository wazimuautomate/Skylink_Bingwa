# Skylink Bingwa v1.0.13 — release pack

**versionName** `1.0.13` · **versionCode** `14` · **applicationId** `com.bingwasokoni`

**Signed with the permanent production identity and wired to the real Firebase
project.** Built by `release.yml` from tag `v1.0.13` (CI run `32710909917`).

- Signer certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  (`C=KE, L=Nairobi, O=My Bingwa, CN=My Bingwa`) — identical to v1.0.9, so this updates
  an existing install cleanly on both channels.
- Verified with `aapt2 dump resources` that the APK carries the **real** Firebase config:
  `google_app_id = 1:111803005684:android:b538e0f6c6456235d7e7b7`,
  `google_storage_bucket = my-bingwa.firebasestorage.app` — both match the project the
  server's FCM service-account key authenticates against.

## Why this version exists — v1.0.12 was broken

v1.0.12 was signed correctly and passed every CI gate, but the **`GOOGLE_SERVICES_JSON`**
repository secret had never been set. `ensureGoogleServicesJson` (in
`app/build.gradle.kts`) falls back to writing a **fake stub** Firebase project whenever
the real config isn't supplied — confirmed by unzipping the released v1.0.12 APK and
finding the literal string `fake_api_key_for_ci_build` and project id `skylink-bingwa`
baked into `resources.arsc`.

Firebase Cloud Messaging cannot issue a token against a project that does not exist, so
on v1.0.12:

- The app's `subscribeToTopic("all_users")` and token registration never reached the
  real Firebase project.
- Sending a push from the admin dashboard returned *"Push sent to the all_users topic.
  No device has registered its token yet"* — because literally no device, ever, could
  have registered against that fake project.
- No push notification could reach any phone, no matter what.

**This has nothing to do with the push-fix code itself** (`PushController`,
`FcmService`, the notification channel fix, the migration fix — all correct, all still
in this build). It is a CI-pipeline gap: the workflow's fallback stub existed before this
work started, and building/CI-passing verifies the code compiles, not that the shipped
binary is wired to a real backend.

**Fixed by:** setting `GOOGLE_SERVICES_JSON` to the real `google-services.json`
(`project_id: my-bingwa`, matching the FCM service-account key already deployed on the
server), so every future CI build — feature debug builds and signed releases — embeds
the real project.

v1.0.12's GitHub Release is marked broken/pre-release rather than deleted, since it had
already been installed for testing: <https://github.com/wazimuautomate/Skylink_Bingwa/releases/tag/v1.0.12>

One thing worth knowing: the real `google-services.json`'s `api_key` is a placeholder
(`AIzaSyDummyKeyForGoogleServicesCompilationOnly`), not a live Google API key — that is
what's in the source file itself, not something the build substituted. FCM token
registration goes through Play Services via the App ID, not that REST key, so it should
not block push — but if push still fails after installing this build, generating a real
Android API key for this Firebase project in the Google Cloud Console is the next thing
to check.

## What changed in v1.0.13

Nothing behavioural beyond v1.0.12 — see `release/Skylink-Bingwa-v1.0.12/README.md`
(still in git history) for the full push-notification-fix and rename changelog. This
release exists solely to ship the same code with a real Firebase connection.

## Release artifacts

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.13-play.aab` | Google Play bundle — production-signed, ready to upload |
| `Skylink-Bingwa-v1.0.13-direct.apk` | Direct/sideload APK — production-signed |
| `*.sha256` | Checksums of the files as downloaded from the GitHub Release |

## No server re-upload needed

Nothing under `server/` changed for this release — the push-notification fix already
shipped with v1.0.12 and should already be live on cPanel per
`release/Skylink-Bingwa-v1.0.12/README.md`'s POST-INSTRUCTIONS (now in git history).
This release only fixes the Android client's Firebase wiring.

## Verify on your phone

1. Install `Skylink-Bingwa-v1.0.13-direct.apk` — it updates over v1.0.12 cleanly (same
   signing key), no uninstall needed, no onboarding redo needed.
2. Open the app once so it can register — needs a working internet connection.
3. From the admin dashboard, send a push. It should now report a real device count
   reached, and arrive within seconds, including with the app backgrounded.
4. The Play upload should also move to `1.0.13` — the AAB here is ready.
