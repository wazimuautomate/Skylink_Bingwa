# Skylink Bingwa v1.0.18 — refresh buttons, Earn screen fix, safer data wipe

**versionName** `1.0.18` · **versionCode** `19` · **applicationId** `com.bingwasokoni`

## What is in this folder

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.18-direct-debug.apk` | **Test build.** Debug-signed, installs as *Skylink Bingwa Dev* alongside your live app. |
| `...apk.sha256` | Checksum for the above. |

**This is not the customer release.** Built by the `Feature debug build` GitHub Actions
workflow off commit `fb5f5ad`, the same way every debug test build should be — that
workflow injects the real `PAYMENTS_APP_KEY`/`PAYMENTS_BASE_URL` repo secrets, which a
plain local `./gradlew assembleDebug` cannot do. A build without the real app key
still installs and looks fine, but every server call (registration, Refer & Earn,
payments) is silently rejected with 401 — see the note below.

The signed direct APK and Play AAB are produced by the `release` workflow from a
version tag, the same way every previous release was.

## What this version fixes

**Refer & Earn showing a blank code.** Two things were true at once:

1. The Earn screen only ever showed a code after its own network call to the server
   returned — there was no cached fallback, even though the code to show one already
   existed and simply was never called. Fixed: the screen now shows the last known
   code immediately, before that call completes.
2. If that call fails (no connection, or a build with no server configured), the
   screen looked *exactly* the same as "you genuinely have no code yet" — just blank.
   Fixed: it now says so — "Could not load your code — check your connection and tap
   refresh" — so a real failure is never mistaken for nothing being there.

If you saw this on the v1.0.17 test build: that specific APK was very likely built
without the real app key (see above), which would make the *first* problem above the
one that mattered — the live fetch was being rejected outright, and there was no
cached copy yet to fall back to because it had never successfully fetched one. This
build fixes both the missing fallback and the missing explanation, and — built
through CI — carries the real key.

## What this version adds

- **Force-refresh**, in the same style everywhere it appears: a refresh icon that
  pulls fresh data from the server right now rather than waiting for the next
  scheduled sync.
  - **Home** — to the right of the notification bell.
  - **Offers** — top-right, next to the "Offers" heading.
  - **Help** — top-right, next to the "Help & Support" heading.
  - (Refer & Earn already had one — it now has company.)
- **Settings → Danger zone.** "Clear local data" no longer sits open on the page: it's
  behind a collapsed "Danger zone" row you have to tap to open, and clearing now asks
  for confirmation twice — a first dialog, then a second, starker one — before
  anything is deleted.
- **Refresh now says what happened.** Every refresh button (Home, Offers, Help,
  Refer & Earn) reports through a Snackbar — "Updated", "Already up to date", or
  "Couldn't refresh — check your connection and try again" — instead of just
  spinning and going quiet.
- **Account status is visible on Refer & Earn.** If the admin bans an account or
  blocks earning/payouts (with a reason), the Earn screen now shows a clear banner
  with that reason. Previously a status change was invisible to the customer beyond
  a generic "under review" note on the withdraw button.
- **OTP hardening.** Requesting a new verification code now enforces a one-minute
  minimum gap between requests (on top of the existing 3-per-hour, 10-per-day caps),
  and a nightly job purges verification codes a day past their expiry. The codes
  themselves were already self-built (not a third-party OTP service), hashed, and
  single-use — this closes the "double-tap resend" gap and stops old rows piling up.

## Verification

- `./gradlew compileDirectDebugKotlin` — clean.
- `./gradlew lintDirectDebug` — clean.
- `./gradlew testDirectDebugUnitTest` — 405 tests, 399 pass. The 6 failures are all in
  `OnboardingPermissionComposeTest` and are the same pre-existing failures documented
  in the v1.0.17 release notes — unrelated to this change, confirmed unchanged.
