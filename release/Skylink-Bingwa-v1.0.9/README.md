# Skylink Bingwa v1.0.9 — release pack

**versionName** `1.0.9` · **versionCode** `10` · **applicationId** `com.bingwasokoni`

Built by GitHub Actions "Release (signed)" run `31537510514` from tag `v1.0.9`
(commit `21dd211` on `main`). Signed with the same permanent upload key used since
v1.0.3.

## What changed

**The app is now called "Skylink Bingwa" — name only.** The launcher label, the top
app bar title, and every customer-facing string that speaks the product name
(onboarding, the permission-required screen, Settings, Help, Activity's empty state,
the notification channels and templates) now read "Skylink Bingwa". Nothing else
changed: same `applicationId`, same signing identity, same logo, same behaviour, no
server change.

## What is in this folder

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.9-play.aab` | **Google Play bundle** | Play Console → upload this |
| `Skylink-Bingwa-v1.0.9-play.aab.sha256` | Checksum (computed locally after download) | Verify before uploading |
| `Skylink-Bingwa-v1.0.9-direct.apk` | Signed APK for direct/sideload distribution | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.9-direct.apk.sha256` | Checksum (from CI) | Verify before distributing |

File names keep the `Skylink-Bingwa-` prefix so they match every earlier release pack and
the workflow that produces them; the app itself is labelled Skylink Bingwa.

### Checked on the actual artifact

- `versionCode` **10**, `versionName` **1.0.9**, `applicationId` **com.bingwasokoni**,
  launcher label **Skylink Bingwa** — read back with `aapt dump badging`.
- Signing certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  — byte-identical to v1.0.3/v1.0.6/v1.0.7/v1.0.8, confirmed with
  `apksigner verify --print-certs`. Update-compatible with what is already live.
- `Skylink-Bingwa-v1.0.9-direct.apk.sha256` verified against the downloaded APK (`OK`).
