# Skylink Bingwa v1.0.10 — release pack

**versionName** `1.0.10` · **versionCode** `11` · **applicationId** `com.bingwasokoni`

## What changed in v1.0.10

1. **Streamlined 3-Step Onboarding with Progress Counter**:
   - Combined intro steps into a unified Welcome card (Intro + Benefits).
   - Shows clean `1 of 3`, `2 of 3`, `3 of 3` progress indicators.
   - Preserves required notification permission gate and details collection.

2. **In-App Review Pop-up After Purchase**:
   - Fires after 1 successful online purchase (`MIN_SUCCESSFUL_PURCHASES = 1`).
   - Settle delay of 3 seconds after purchase sheet dismissal (`SETTLE_DELAY_MILLIS = 3_000L`).
   - Minimum interval between prompts updated to 30 days.
   - Fixed `LaunchedEffect` race condition to guarantee review card trigger.

3. **Google Play In-App Updates**:
   - Integrated Google Play In-App Update API (`AppUpdateManager`) on the `play` flavor.
   - Launches `IMMEDIATE` non-dismissible full-screen update overlay when a new version is available on the Play Store.

4. **Dynamic Offline Engagement Notifications & Catalogue Cleanup**:
   - Removed discontinued `1.25GB for KSh 55` bundle across client, API, and admin seeds.
   - Engagement notifications now dynamically bind real active catalogue bundles and current prices from local/cached storage.

5. **Instant Admin Push Notifications via Firebase FCM HTTP v1**:
   - Integrated Firebase Cloud Messaging SDK (`com.google.firebase:firebase-messaging-ktx`).
   - Added `SkylinkBingwaFirebaseService` for foreground/background notification dispatch and token registration.
   - Added pure PHP `FcmService` supporting Google Service Account OAuth2 JWT authorization.
   - Added Admin Dashboard "Instant Push" compose & broadcast view (`/push`), broadcast logging, and customer FCM token management.

## Release Artifacts

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.10-play.aab` | **Google Play bundle** | Play Console → Production / Open Testing |
| `Skylink-Bingwa-v1.0.10-play.aab.sha256` | Checksum | Verify before uploading |
| `Skylink-Bingwa-v1.0.10-direct.apk` | Signed APK for direct/sideload distribution | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.10-direct.apk.sha256` | Checksum | Verify before distributing |
