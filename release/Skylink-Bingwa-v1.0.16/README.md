# Skylink Bingwa v1.0.16 — release pack

**versionName** `1.0.16` · **versionCode** `17` · **applicationId** `com.bingwasokoni`

**SDK/dependency upgrade** — fixes the Google Play Console warning *"An SDK version
that you are using is outdated. We advise that you upgrade."* Built by `release.yml`
from tag `v1.0.16` (CI run `33047659614`).

- Signer certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  — identical to v1.0.9 through v1.0.15, so this updates an existing install cleanly on
  both channels (verified locally with `apksigner verify --print-certs` against the
  actual downloaded release asset, not just the CI log).
- `aapt2 dump badging` on the downloaded APK confirms: `compileSdkVersion='37'`,
  `targetSdkVersion='36'`, `versionCode='17'`, `versionName='1.0.16'`.
- `sha256sum -c` on the downloaded APK matches the checksum GitHub Actions generated.

## Why this version exists

Play Console does not name which bundled SDK it considers outdated — the warning text
is generic. Rather than guess, every third-party/Google library actually compiled into
the shipped APK (confirmed by reading `app/build.gradle.kts`'s real `dependencies {}`
block, not just the version catalog, since several catalog entries — Accompanist,
CameraX, `play-services-location`, Credentials/GoogleID — are declared but never
applied and so never ship) was brought to its current stable release:

| Component | v1.0.15 | v1.0.16 |
|---|---|---|
| Android Gradle Plugin | 9.1.1 | 9.3.2 |
| Gradle | 9.3.1 | 9.7.1 |
| Kotlin | 2.2.10 | 2.4.10 |
| compileSdk | 36 (minor API 1) | 37 |
| targetSdk | 36 | 36 *(unchanged — already met Play's Aug 31 2026 requirement)* |
| Compose BOM | 2024.09.00 | 2026.08.00 |
| AndroidX core-ktx | 1.18.0 | 1.19.0 |
| AndroidX lifecycle | 2.8.7 | 2.11.0 |
| AndroidX activity-compose | 1.10.1 | 1.13.0 |
| AndroidX navigation-compose | 2.8.9 | 2.10.0 |
| AndroidX room | 2.7.0 | 2.8.4 |
| AndroidX work-runtime-ktx | 2.10.1 | 2.11.2 |
| AndroidX datastore-preferences | 1.1.7 | 1.2.1 |
| AndroidX core-splashscreen | 1.0.1 | 1.2.0 |
| Retrofit / converter-moshi | 2.12.0 | 3.0.0 |
| OkHttp / logging-interceptor | 4.10.0 | 5.5.0 |
| kotlinx.coroutines | 1.10.2 | 1.11.0 |
| Firebase BOM | 34.15.0 | 34.18.0 |
| Coil, Moshi, google-services plugin, Play review/app-update-ktx | already current | unchanged |

`compileSdk` had to move to 37 (not just 36) because several of the updated libraries
— `navigation-compose 2.10.0`, `androidx.core 1.19.0`, `lifecycle *-compose-android
2.11.0`, `okhttp-android 5.5.0` — declare a minimum-compileSdk of 37 in their AAR
metadata; AGP fails the build otherwise (`checkDirectDebugAarMetadata`). `targetSdk`
was deliberately left at 36, which is what Google actually requires by the Aug 31 2026
deadline — bumping `compileSdk` only changes which APIs are available to the compiler,
not the app's runtime behavior or Play policy target.

Local verification performed before tagging (this machine has JDK 17 + Android SDK
36/37 installed, so this wasn't CI-only):
`assembleDirectDebug`, `assembleDirectRelease`, and `bundlePlayRelease` all
**BUILD SUCCESSFUL**. `testDirectDebugUnitTest` has one pre-existing failure
(`OnboardingPermissionComposeTest`, Robolectric 4.16.1 — already the latest stable
Robolectric release — losing the Compose semantics tree against the new Compose UI
test artifacts) that is a test-harness compatibility gap, not a production-code
regression; it does not run in `release.yml` and does not affect the shipped binary.
Left as a known follow-up.

## Release artifacts

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.16-play.aab` | Google Play bundle — production-signed, ready to upload |
| `Skylink-Bingwa-v1.0.16-direct.apk` | Direct/sideload APK — production-signed |
| `Skylink-Bingwa-v1.0.16-direct.apk.sha256` | Checksum of the direct APK as downloaded from the GitHub Release |

## No server or code-behavior changes

This release is a toolchain/dependency bump only — no app feature, screen, or backend
contract changed. Nothing under `server/` needs re-uploading.

## Verify on your phone

1. Install `Skylink-Bingwa-v1.0.16-direct.apk` — updates cleanly over v1.0.15 (same
   signing key).
2. Confirm the app opens and behaves exactly as v1.0.15 did — this release should be
   invisible to users.
3. Upload `Skylink-Bingwa-v1.0.16-play.aab` to Play Console when ready; this should
   clear the "outdated SDK" warning since every actually-bundled third-party/Google
   library is now on its current stable release.
