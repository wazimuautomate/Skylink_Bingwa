# Skylink Bingwa v1.0.17 — referral & commission release

**versionName** `1.0.17` · **versionCode** `18` · **applicationId** `com.bingwasokoni`

## What is in this folder

| File | What it is |
|---|---|
| `Skylink-Bingwa-v1.0.17-direct-debug.apk` | **Test build.** Debug-signed, installs as *Skylink Bingwa Dev* alongside your live app. |
| `...apk.sha256` | Checksum for the above. |

**This is not the customer release.** It is signed with the debug key so it can be
built without the production keystore, which is deliberately not in this repository.
Because the application id carries a `.debug` suffix, it installs next to your real
app instead of replacing it — which is exactly what you want for testing a money
feature.

The signed direct APK and Play AAB are produced by the `release` workflow from the
`v1.0.17` tag, the same way every previous release was. See
[`docs/REFERRAL_DEPLOYMENT_GUIDE.md`](../../docs/REFERRAL_DEPLOYMENT_GUIDE.md),
final section.

## What this version adds

**Refer & Earn**, reachable from the gift icon in the top-right corner of the home
screen.

- Every customer gets a referral code in the form `SK` + three digits + a letter
  (for example `SK391R`), minted by the server at registration. Tap it to copy;
  one button shares it to WhatsApp, SMS or anywhere else.
- Onboarding gains an optional referral-code field. It validates live and shows
  *"You were referred by \<name\>"* when the code is real. A wrong code never blocks
  onboarding.
- The referrer earns **Ksh 10** when someone joins with their code, and a percentage
  commission on everything that person buys afterwards.
- At **Ksh 200** the referrer withdraws straight to their own M-Pesa number, with no
  phone call and no manual step by the owner.
- Withdrawal is protected by a one-time SMS code, asked for only the first time —
  buyers who never earn are never bothered by it, and the business is never charged
  for the SMS.

## What this version removes

The admin panel's **scheduled notifications** module — editor, campaigns, wording
variations, delivery log and the two public endpoints that served them. Instant Push
does the same job immediately and without a publish cycle, so keeping both meant two
places to write a message and two ways for it to be wrong.

The app falls back to the notification wording compiled into the APK, which is what it
already did on any install with no server configured. Instant Push is unaffected.

## The anti-farming rule worth knowing about

The obvious way to abuse a referral bonus is: install, onboard with a second SIM,
redeem your own code, uninstall, reinstall, repeat. Nothing on a phone survives that
loop except Android's device id — which is why the server records a hashed device id
and allows **one referral redemption per handset, for life**.

On top of that, the Ksh 10 bonus does not become withdrawable until that referred
person actually buys something. A farm would have to generate real revenue to unlock
it, which is the point.

## Before this build does anything

The commission rate ships at **0%** on purpose. It stays there until the owner records
the real margin on each offer in the admin panel — a commission above margin loses
money on every referred sale, and loses it faster the better the programme works.

Automatic M-Pesa payouts also ship **off**. Turn them on only after B2C Go-Live, as
described in the deployment guide.

## Verification

- `./gradlew lintDirectDebug` — clean.
- `./gradlew testDirectDebugUnitTest` — 405 tests, 399 pass. The 6 failures are all in
  `OnboardingPermissionComposeTest` and were **confirmed pre-existing**: they fail
  identically on the unmodified `990325e` commit, verified in a clean git worktree.
  They are not caused by this change and are not fixed by it.
