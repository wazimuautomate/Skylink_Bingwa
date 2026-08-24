# Skylink Bingwa v1.0.14 — release pack

**versionName** `1.0.14` · **versionCode** `15` · **applicationId** `com.bingwasokoni`

**Signed with the permanent production identity, wired to the real Firebase project,
and fixes the reason v1.0.13 still could not deliver a push to a real device.** Built
by `release.yml` from tag `v1.0.14` (CI run `32714571455`).

- Signer certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  (`C=KE, L=Nairobi, O=My Bingwa`) — identical to v1.0.9/v1.0.13.
- `aapt2 dump resources` confirms the real Firebase config is baked in:
  `google_app_id = 1:111803005684:android:b538e0f6c6456235d7e7b7`,
  `google_storage_bucket = my-bingwa.firebasestorage.app`.

## Why this version exists — v1.0.13 still failed on a real device

v1.0.13 fixed the CI pipeline (real `GOOGLE_SERVICES_JSON` secret, so the APK is
genuinely wired to the `my-bingwa` Firebase project) but sending a push still reported
*"No device has registered its token yet"* on a real phone. The cause this time was in
the app, not the build:

`FakeBingwaRepositoryImpl.setFcmToken()` read the in-memory user profile
(`_userProfile.value`) to decide whether onboarding was complete, **without waiting for
the on-disk profile restore to finish**. Its sibling method, `registerCustomer()`, does
wait (`restoreComplete.await()`) — with a comment explaining exactly why: reading before
the restore returns the default, empty profile.

For a brand-new install this rarely shows up, because onboarding runs interactively and
the in-memory profile is already correct by the time a token arrives. But for an
**already-onboarded install updating to a new version** — the test phone, and every real
customer — `registerCustomer()` short-circuits (`_customerRegistered` is already `true`
from a previous version) and never runs again. `setFcmToken()` becomes the *only*
remaining path that can ever deliver a token to the server. Firebase's token callback can
resolve before the DataStore restore finishes on a cold start (a network round-trip
racing a local disk read); when it wins that race, `setFcmToken()` read
`isOnboardingCompleted = false` and silently dropped the token — every launch, with
nothing logged anywhere.

**Fixed** by awaiting `restoreComplete` in `setFcmToken()` before reading the profile,
matching `registerCustomer()`'s existing, already-correct pattern.

## No server or Firebase-config changes in this release

Only `FakeBingwaRepositoryImpl.kt` changed. The push-fix server code (still from
v1.0.12) and the real Firebase wiring (from v1.0.13) are both unchanged and don't need
re-touching.

## Verify on your phone

1. Install `Skylink-Bingwa-v1.0.14-direct.apk` — updates cleanly over v1.0.13, same
   signing key, no uninstall, no re-onboarding.
2. Open the app once with a working connection.
3. Send a push from the admin dashboard. It should now report an actual delivered
   device count (not "No device has registered its token yet") and arrive on the phone
   within seconds, including backgrounded.
4. Upload `Skylink-Bingwa-v1.0.14-play.aab` to Play when ready.

If it *still* reports zero after this — the next things to check, in order:
1. **Confirm the phone actually has internet** at the moment the app is opened (the
   token fetch and the register call both need it; a launch with no connectivity yields
   the same silent no-op, by design, and simply retries next launch).
2. **Check the admin Customers page** for this phone's number — if it's not there at
   all, the whole registration pipeline (not just FCM) isn't reaching the server, which
   points at `PAYMENTS_APP_KEY`/`X-App-Key` or `PAYMENTS_BASE_URL` rather than Firebase.
3. **The known caveat from v1.0.13**: the real `google-services.json`'s `api_key` is the
   placeholder `AIzaSyDummyKeyForGoogleServicesCompilationOnly`. This should not block
   FCM (token registration goes through Play Services via the App ID, not that REST
   key), but if everything else here checks out, generating a real Android API key for
   the `my-bingwa` Firebase project in Google Cloud Console is the remaining unknown.
