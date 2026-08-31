# Skylink Bingwa v1.0.20 — fixes the broken PAYMENTS_BASE_URL secret

**versionName** `1.0.20` · **versionCode** `21` · **applicationId** `com.bingwasokoni`

## What is in this folder

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.20-direct.apk` | **The real release.** Signed with the permanent upload keystore. This is the file to actually distribute — download and install directly, no debug label, replaces the live app on a device that already has it. |
| `Skylink-Bingwa-v1.0.20-direct.apk.sha256` | Checksum for the above. |
| `Skylink-Bingwa-v1.0.20-play.aab` | The Google Play bundle. **Upload this to the Play Console only** — an AAB is not directly installable on a phone. |
| `Skylink-Bingwa-v1.0.20-play.aab.sha256` | Checksum for the above. |
| `Skylink-Bingwa-v1.0.20-direct-debug.apk` | Debug test build — installs as *Skylink Bingwa Dev*, side by side with the real app, for testing without touching your production install. Not for distribution. |
| `Skylink-Bingwa-v1.0.20-direct-debug.apk.sha256` | Checksum for the debug build. |

Both signed files come from the tagged `v1.0.20` GitHub Release, built by the
`Release (signed)` workflow — the one that uses the permanent signing keystore and
only ever runs on a version tag, never on an ordinary push. Same identity as every
previous signed release: `apksigner verify --print-certs` on this APK reports
SHA-256 `185d3fca…37cd`, identical to v1.0.9 through v1.0.19, so this update
installs cleanly over an existing install rather than needing a fresh one.

Public release page: https://github.com/wazimuautomate/Skylink_Bingwa/releases/tag/v1.0.20

## What changed since v1.0.19 — and why this release exists

**No app code changed.** This release exists to pick up one corrected server
configuration value: the `PAYMENTS_BASE_URL` GitHub Actions secret.

That secret did not resolve as a hostname — it was wrong, not merely unset. Every
app CI has ever built (every signed Play/direct release, and every CI-built debug
APK) baked in that broken value, so every network call the app made through it —
checking/loading a referral code, the Earn screen's balance summary, OTP,
withdrawal, and real M-Pesa STK payments — failed with a network error the moment
it left the phone. The app surfaced this honestly as "Could not load your code"
rather than crashing or faking success, which is why it looked like a feature bug
from the outside.

It never showed up in testing because a debug APK built **locally** (on a
developer's own machine, not through CI) never had that secret set, so it silently
fell back to the correct hardcoded default (`https://mybingwa.blazetechscope.com/`)
in `app/build.gradle.kts` — every local debug build worked by accident, every
CI-built and Play-distributed build was broken by the same secret.

**Fix applied this release:** the owner corrected the `PAYMENTS_BASE_URL` secret in
GitHub → Settings → Secrets and variables → Actions to the correct value. This
build is the first one since the mistake was introduced to actually carry the
fix — confirmed live before this build was cut:

```
curl https://mybingwa.blazetechscope.com/status.php
→ HTTP 401 {"status":"PAYMENT_FAILED","errorCode":"UNAUTHORISED"}
```

(a clean, fast, correctly-formed rejection — proof the host resolves and the
server answers; the 401 itself is expected without the app's X-App-Key header,
which only the compiled app sends).

## Before this build moves real money

Unchanged from v1.0.19 — still configuration only, still yours to finish:

- [ ] Confirm the real HostPinnacle `sms_userid` / `sms_password` / `sms_sender_id`
      are in `config.php` (not placeholders) and a test OTP actually arrives by SMS.
- [ ] Confirm `b2c_cert_path` in `config.php` points to a file that's actually there
      with that exact filename — case-sensitive on Linux.
- [ ] Turn on **Automatic M-Pesa payouts** on the Referrals settings page once B2C
      Go-Live is complete and you've tested a real payout.
- [ ] Set real per-offer commission margins so the referral rate is capped at
      something real, rather than the platform default.
- [ ] **New, because of this release:** once this build is installed, actually open
      Refer & Earn and confirm a real referral code loads (the whole point of this
      release). If it still says "Could not load your code," the secret value
      entered may not exactly match `https://mybingwa.blazetechscope.com/`
      (trailing slash, scheme, or a typo in the host) — re-check it.

## Verification

- `Release (signed)` workflow run: succeeded — both `assembleDirectRelease` and
  `bundlePlayRelease` built clean.
- `sha256sum -c Skylink-Bingwa-v1.0.20-direct.apk.sha256` — passed against the
  workflow's own checksum.
- `apksigner verify --print-certs` on the direct APK — V2 signer SHA-256
  `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`, DN
  `C=KE, L=Nairobi, O=My Bingwa, CN=My Bingwa` — matches every prior release.
- Root cause was isolated with a temporary CI diagnostic (curled the live referral
  endpoints from GitHub Actions using the real `PAYMENTS_APP_KEY` +
  `PAYMENTS_BASE_URL` secrets): it failed with `curl: (6) Could not resolve host`
  against the broken secret, while the same domain resolved and answered correctly
  when queried directly — conclusively pointing at the secret, not the referral
  code in `ReferralRepository.kt` / `referral_summary.php` / `check_referral_code.php`,
  all of which were read end-to-end and are correct. The diagnostic workflow was
  removed after use.
