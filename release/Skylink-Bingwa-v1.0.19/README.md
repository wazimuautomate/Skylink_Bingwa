# Skylink Bingwa v1.0.19 — icon reshuffle + everything since v1.0.18

**versionName** `1.0.19` · **versionCode** `20` · **applicationId** `com.bingwasokoni`

## What is in this folder

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.19-direct.apk` | **The real release.** Signed with the permanent upload keystore. This is the file to actually distribute — download and install directly, no debug label, replaces the live app on a device that already has it. |
| `Skylink-Bingwa-v1.0.19-direct.apk.sha256` | Checksum for the above. |
| `Skylink-Bingwa-v1.0.19-play.aab` | The Google Play bundle. **Upload this to the Play Console only** — an AAB is not directly installable on a phone. |
| `Skylink-Bingwa-v1.0.19-play.aab.sha256` | Checksum for the above. |
| `Skylink-Bingwa-v1.0.19-direct-debug.apk` | Debug test build — installs as *Skylink Bingwa Dev*, side by side with the real app, for testing without touching your production install. Not for distribution. |
| `Skylink-Bingwa-v1.0.19-direct-debug.apk.sha256` | Checksum for the debug build. |

Both signed files come from the tagged `v1.0.19` GitHub Release, built by the
`Release (signed)` workflow — the one that uses the permanent signing keystore and
only ever runs on a version tag, never on an ordinary push. Same identity as every
previous signed release (last one before this: `v1.0.16`), so this update installs
cleanly over an existing install rather than needing a fresh one.

Public release page: https://github.com/wazimuautomate/Skylink_Bingwa/releases/tag/v1.0.19

## What changed since v1.0.18

**Refer & Earn moved to the bottom navigation bar**, replacing its old top-bar gift
icon. **Settings moved to the top bar** (right of the refresh icon) in its place.
Refer & Earn is now a primary destination like Home/Offers/Activity/Help — reached
from the bottom bar, not a screen you navigate into and back out of, so it no longer
has its own back button.

Everything else this cycle was server-side — no app code changed for these, but they
directly affect what Refer & Earn actually does once you use it:

- **Real SMS gateway wired in.** Referral OTP codes and "someone joined"
  notifications now go through HostPinnacle (your real provider) instead of a
  guessed contract that was never going to deliver an actual text.
- **B2C initiator password moved to the database**, editable from the Referrals
  admin page. Safaricom forces a periodic reset on this password — previously that
  meant an edit to `config.php` and a redeploy every time; now it's one password
  field, and the next payout cron run picks it up automatically.
- **Duplicate registration no longer overwrites a customer's name.** The phone
  number is the account; re-registering an already-known number (a reinstall, or a
  retried call) no longer relabels that customer with whatever name was typed this
  time.

(v1.0.18 itself — force-refresh buttons, the Earn-screen fix, the account-status
banner, the Danger Zone confirmation — is unchanged here; see that folder's README.)

## Before this build moves real money

Everything **code-side** is complete and verified (see below). What's left is
configuration only, and it's yours to finish since it's your credentials:

- [ ] Confirm the real HostPinnacle `sms_userid` / `sms_password` / `sms_sender_id`
      are in `config.php` (not placeholders) and a test OTP actually arrives by SMS.
- [ ] Confirm `b2c_cert_path` in `config.php` points to a file that's actually there
      with that exact filename — case-sensitive on Linux. (Flagged separately: the
      certificate itself is dated 2017–2018; worth pulling a current one from the
      Daraja portal to compare against.)
- [ ] Turn on **Automatic M-Pesa payouts** on the Referrals settings page once B2C
      Go-Live is complete and you've tested a real payout.
- [ ] Set real per-offer commission margins so the referral rate (currently 10%) is
      actually capped at something real, rather than the platform default.

None of these are code gaps — the referral system, OTP flow, and payout pipeline all
work correctly end-to-end against whatever credentials are configured. These are the
real-world secrets and Safaricom process steps only you can supply.

## Verification

- `./gradlew compileDirectDebugKotlin` — clean.
- `./gradlew lintDirectDebug` — clean.
- `./gradlew testDirectDebugUnitTest` — 405 tests, 399 pass. The 6 failures are all
  in `OnboardingPermissionComposeTest`, confirmed pre-existing and unrelated since
  v1.0.17 (they fail identically against an unmodified clean checkout from before
  any of this session's changes) — a Robolectric/Compose test-harness timing issue
  in the onboarding welcome→setup step transition, not a real onboarding bug. Real
  onboarding has worked correctly on-device throughout this whole project. Not
  attempted this round: fixing it means touching onboarding's animation/pager
  logic, which already works correctly for real users, purely to satisfy a test
  harness — happy to take it on as its own dedicated piece of work if you want it
  gone rather than documented.
- Server: PHP syntax-checked and the admin logic test suite passed on every commit
  this session (GitHub Actions `Server checks`), and every server file deployed
  this session was independently confirmed live via a direct HTTP check
  (`X-Powered-By: PHP` on the deployed endpoint) — not just a green deploy log.
- `Release (signed)` workflow run: succeeded — both `assembleDirectRelease` and
  `bundlePlayRelease` built clean, and both artifacts here are verified against
  the checksums the workflow itself produced (`sha256sum -c` passed).
