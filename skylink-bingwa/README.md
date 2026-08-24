# Skylink Bingwa — Android app module

This is the Android (Kotlin / Jetpack Compose) app for Skylink Bingwa. It was
bootstrapped from a Google AI Studio export and is being rebuilt to the
canonical spec in [`../docs/design.md`](../docs/design.md) and
[`../docs/Plan.md`](../docs/Plan.md).

## Build (no Android Studio required)

The project builds from the command line using the checked-in Gradle wrapper.
You need a JDK 17+ and the Android SDK on the machine (CI provisions both). Do
**not** open this in Android Studio as a requirement — the authoritative build
is GitHub Actions.

```bash
./gradlew test lint assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Notes

- Debug builds use AGP's auto-generated debug keystore. Never commit a keystore.
- Optional secrets (e.g. a Gemini key for AI Studio scaffolding) go in a local
  `.env` file, which is git-ignored. See `.env.example`. The customer app does
  not require any secret to build a debug APK.
- See the repository root [`README.md`](../README.md) for the full workflow and
  how to download a CI debug APK.
