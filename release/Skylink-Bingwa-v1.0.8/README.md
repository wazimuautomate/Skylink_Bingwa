# Skylink Bingwa v1.0.8 — release pack

**versionName** `1.0.8` · **versionCode** `9` · **applicationId** `com.bingwasokoni`

Built by GitHub Actions "Release (signed)" run `31370362085` from tag `v1.0.8`
(commit `29dc403` on `main`). Signed with the same permanent upload key used since
v1.0.3.

**No functional changes from v1.0.7.** This build exists solely because Google Play
Console required a new version code for the upload (it will not accept a duplicate
`versionCode`). Everything shipped in v1.0.7 — see `release/Skylink-Bingwa-v1.0.7/README.md`
for the full feature/fix writeup — applies unchanged here: SMS permission removed
entirely, morning/evening notifications fixed, "buy for myself" number editable.

## What is in this folder

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.8-play.aab` | **Google Play bundle** | Play Console → upload this |
| `Skylink-Bingwa-v1.0.8-play.aab.sha256` | Checksum (computed locally after download) | Verify before uploading |
| `Skylink-Bingwa-v1.0.8-direct.apk` | Signed APK for direct/sideload distribution | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.8-direct.apk.sha256` | Checksum (from CI) | Verify before distributing |

### Checked on the actual artifact

- `versionCode` **9**, `versionName` **1.0.8**, `applicationId` **com.bingwasokoni** —
  read back with `aapt dump badging`.
- Signing certificate SHA-256 `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`
  — byte-identical to v1.0.3/v1.0.6/v1.0.7, confirmed with `apksigner verify --print-certs`.
  Update-compatible with whatever is already live.

No server files changed since v1.0.7 — nothing new to re-upload to cPanel for this
version bump.
