# Skylink Bingwa

Native Android customer app for buying the Skylink Bingwa business's Bingwa data,
SMS, minutes and special offers. One seller, no customer accounts. Customers buy
for their own number or another number, pay online via M-Pesa STK Push or follow
cached offline Till/Paybill instructions, and track purchases locally.

Bundle fulfilment happens outside this app. **Version 1 stops at honest payment
status and never claims a bundle was delivered.**

## Repository layout

| Path | Purpose |
|---|---|
| `CLAUDE.md` | Operating, Git, testing and documentation rules (the operational brain). |
| `memory.md` | Durable project state and append-only execution history. |
| `CHANGELOG.md` | Keep a Changelog with an `[Unreleased]` section. |
| `docs/Plan.md` | Product behaviour, scope, architecture and API expectations. |
| `docs/design.md` | Canonical UI/UX: colours, typography, composition, motion, accessibility. |
| `docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md` | Execution phases and parallel-session boundaries. |
| `docs/REPO_INVENTORY.md` | Imported-project inventory, ownership boundaries and Phase 1 shared contracts. |
| `assets/skylink-bingwa-logo-kit/` | Approved logo, launcher, notification, splash and in-app assets. |
| `skylink-bingwa/` | The Android app project (Gradle root). |

## Build workflow (CI-first, no Android Studio required)

This project is built and released **without Android Studio**. The checked-in
Gradle wrapper and GitHub Actions are the authoritative build path; real visual
testing happens on a physical Android phone.

The Android project lives in [`skylink-bingwa/`](skylink-bingwa/). With a JDK 17+ and the
Android SDK available, you can build a debug APK from the command line:

```bash
cd skylink-bingwa
./gradlew test lint assembleDebug
```

The clean, authoritative build runs in GitHub Actions.

### Current tooling (imported baseline)

- Gradle **9.3.1** (checked-in wrapper)
- Android Gradle Plugin **9.1.1**
- Kotlin **2.2.10**, Jetpack Compose + Material 3
- `minSdk 24`, `targetSdk 36`, `compileSdk 36`
- Package/namespace: `com.example` (placeholder — to be finalised in Phase 1)
- `applicationId`: `com.aistudio.skylinkbingwa.k3p9zq` (placeholder — the permanent
  production applicationId is an unresolved product decision; see `memory.md`)

## Getting a debug APK

1. Open the repository on GitHub and go to **Actions**.
2. Open the successful **Feature debug build** run for your branch or commit.
3. Download the `skylink-bingwa-debug-<short-sha>` artifact from the run summary.
4. Extract it and install `Skylink-Bingwa-Debug-<short-sha>.apk` on the phone.

Debug builds are labelled distinctly from release builds and use AGP's
auto-generated debug signing key. Release signing, versioning and Play/direct
distribution are handled in a later, protected release phase.

## Contributing / phased development

Work follows the phases in
[`docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md`](docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md).
Before touching code, read `CLAUDE.md`, the relevant parts of `docs/Plan.md` and
`docs/design.md`, and the current state in `memory.md`. Never commit secrets,
never merge unfinished work to `main`, and keep payment/fulfilment language
honest.
