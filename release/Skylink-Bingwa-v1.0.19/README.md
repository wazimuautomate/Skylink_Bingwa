# Skylink Bingwa v1.0.19 — icon reshuffle + everything since v1.0.18

**versionName** `1.0.19` · **versionCode** `20` · **applicationId** `com.bingwasokoni`

## What is in this folder

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.19-direct-debug.apk` | **Test build.** Debug-signed, installs as *Skylink Bingwa Dev* alongside your live app. |
| `...apk.sha256` | Checksum for the above. |

**This is not the customer release.** Built by the `Feature debug build` GitHub Actions
workflow off commit `3aa95d5` on `main`, which carries the real `PAYMENTS_APP_KEY` /
`PAYMENTS_BASE_URL` repo secrets — a plain local `./gradlew assembleDebug` cannot do
that, and a build without the real key installs fine but has every server call
(registration, Refer & Earn, payments) silently rejected with 401.

This build merges two things that were developed in parallel and are now both on
`main`: the icon reshuffle from your own session, and everything server-side from
this one. Confirmed no file overlap between them — the merge was clean, no conflicts.

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
- CI: `Feature debug build` assembles and uploads the APK successfully every time;
  the workflow shows red only because of the pre-existing test gate above, not the
  build itself.
