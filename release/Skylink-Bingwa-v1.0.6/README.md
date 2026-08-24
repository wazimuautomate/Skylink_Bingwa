# Skylink Bingwa v1.0.6 — release pack

**versionName** `1.0.6` · **versionCode** `7` · **applicationId** `com.bingwasokoni`

Built by GitHub Actions "Release (signed)" run `31274807752` from tag `v1.0.6`
(commit `247b4ca` on `main`). Signed with the permanent upload key held in Actions
secrets — no keystore exists on any local machine.

## What is in this folder

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.6-play.aab` | **Google Play bundle** (13.8 MB) | Play Console → Production → Create release → upload this |
| `Skylink-Bingwa-v1.0.6-play.aab.sha256` | Checksum for the bundle | Verify before uploading |
| `Skylink-Bingwa-v1.0.6-direct.apk` | Signed APK for direct/sideload distribution (13.9 MB) | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.6-direct.apk.sha256` | Checksum for the direct APK | Verify before distributing |

Verify either file with `sha256sum -c <file>.sha256`.

```
APK  3f42ab8cdd282ff3724550b79410b0b491bd00ee94cd9d8bef9a7ed7bf9669cb
AAB  9abb32a0b36b263a06ff66fc12a893a896f731b276a935c4172a55374eea1428
```

### Checked on the actual artifacts, not assumed

- `versionCode` **7**, `versionName` **1.0.6**, `applicationId` **com.bingwasokoni**,
  `minSdk` 24, `targetSdk` 36 — read back out of the built APK.
- **The signing identity is byte-identical to v1.0.3**: certificate SHA-256
  `185d3fca540acfcf26ff49530bdb5ff491a236e8fa096493ccd86f72117837cd`,
  `CN=Skylink Bingwa, O=Skylink Bingwa, L=Nairobi, C=KE`. That is what lets a customer move
  between the Play and direct channels with updates superseding correctly — worth
  confirming every release, because a mismatch is only discovered when an update
  refuses to install.
- The direct APK declares `RECEIVE_SMS` and `REQUEST_INSTALL_PACKAGES`, as the
  sideload channel needs; the Play bundle drops `REQUEST_INSTALL_PACKAGES`.
- The APK's direct-channel checksum matched the one CI generated.
- Both artifacts came out of the same CI job, so they are the same source at the same
  commit.

Never distribute the `.aab` to end users — it is not installable.

## What ships in this version

### 1. Offers carry the hours Safaricom sells them

Safaricom now restricts several bundles to a window of the day. Buying one outside its
window fails at the carrier *after* the customer has paid, so the window travels the
whole way through the stack:

- **Admin → Offers** has two new fields, **Sells from** and **Sells until** (Nairobi
  wall clock, both blank = sold all day). A window may cross midnight, e.g. 22:00 →
  02:00. The offers list shows a **Sells** column so the whole catalogue can be read at
  a glance.
- **Publishing** puts `availableFrom` / `availableTo` in the snapshot, and
  `get_offers.php` serves them to the app.
- **The app** shows the window on **every** offer card ("Available now · 5:00 PM –
  11:00 PM", or "Available from 5:00 PM to 11:00 PM" when it is shut). Outside the
  window the Buy button is replaced by an **Opens 5:00 PM** chip, and tapping the card
  opens an explanation instead of checkout. The window is restated on the review step.
- **Offline purchase** of a windowed offer is withheld while it is shut, with the same
  explanation — an offline payment could not be fulfilled either.
- **`stk.php` refuses it server-side** with `OFFER_NOT_AVAILABLE_NOW`, *before* the
  payment row is claimed and before Daraja is called, so a refusal costs nothing: no
  STK prompt, no charge, no order to reconcile.

### 2. Once-a-day bundles know which number already had one

- Every offer list shows **"Already bought today for 0712 345 678"** under a
  once-per-day offer that has been used today, naming the number rather than a bare
  "bought today".
- At checkout, typing a number that already received the bundle today shows a block
  explaining that Safaricom allows it once a day per number, that it can be bought for
  that number again after midnight, and that **a different number can be used right
  now**. Confirm stays disabled until the number changes.
- Numbers are matched on their last nine digits, so `0712345678`, `254712345678` and
  `+254 712 345 678` are one line. (Previously they compared as three different
  numbers, which would have let the same bundle through twice in a day.)
- **`stk.php` enforces the same rule** (`ALREADY_BOUGHT_TODAY`), counting confirmed
  payments plus requests started in the last ten minutes so a prompt still sitting on
  the customer's screen cannot be duplicated.
- **The reset is the Nairobi day boundary itself, not a stored flag or a scheduled
  job.** A purchase at 23:58 stops blocking at 00:00 because the day comparison — never
  a written "bought today" marker — decides. Server-side the Nairobi/MySQL clock offset
  is *measured* (`SELECT NOW()` against PHP's Nairobi clock) rather than assumed, so
  the reset lands on midnight whatever the host's timezone is.

### 3. Notifications and Safaricom messages are now required

Owner decision: both are the product, not extras.

- Onboarding order is now **Welcome → What you gain → Name & number → Notifications →
  Safaricom messages**. The permission steps come last, after the personal details, and
  cannot be skipped. The "Skip" shortcut and the "Not now" escape are gone; the only
  alternative offered is **Close Skylink Bingwa**.
- After two refusals Android stops showing its dialog, so the button becomes **Open
  settings and allow** rather than a control that appears to do nothing.
- **Settings no longer has the "Push Notifications" or "Reads Safaricom SMS"
  toggles.** A switch the app would immediately override is worse than no switch.
- If a permission is later revoked in Android's own settings, the app shows a blocking
  screen on next foreground with the same two outcomes (allow, or close).
- Unchanged on the Play flavour: it strips `RECEIVE_SMS` from the manifest, so that
  step does not exist there and nothing is required that cannot be granted.

### 4. Checkout: tap a number to fix it

Both numbers on the review step are tappable and marked with a pencil — seeing a wrong
digit at the moment of paying is exactly when it needs fixing.

### 5. The Support page no longer starts blank — the seller numbers ship in the APK

**This is the fix for the "4 of 20 testers saw no Till/Paybill/support number" report.**
The numbers defaulted to blank and were only filled by a successful sync, so a customer
on a weak connection saw an empty Support page and offline instructions that refused to
show a number to pay — and the values appeared, or appeared then vanished, purely
according to whether a sync had landed. That matches all three things testers described.

The production numbers now ship inside the APK:

| Value | Bundled in this build |
|---|---|
| Till | `4063396` |
| Paybill | `4008239` |
| Support phone | `0110092715` |
| Support WhatsApp | `0717444266` |

They are a **floor, not the truth**: the first successful sync replaces them, the synced
copy is cached and preferred from then on, and Admin → Support details remains the only
place a number is changed. Override them per build without touching code:

```
./gradlew assembleDirectRelease \
  -PsellerTillNumber=4063396 -PsellerPaybillNumber=4008239 \
  -PsellerSupportNumber=0110092715 -PsellerSupportWhatsapp=0717444266
```

(or the `SELLER_TILL_NUMBER` / `SELLER_PAYBILL_NUMBER` / `SELLER_SUPPORT_NUMBER` /
`SELLER_SUPPORT_WHATSAPP` env vars, which is how CI should set them.)

These four values are the owner-confirmed production numbers for this release. They are
also what the app falls back to on any phone that has not synced yet, so if one of them
changes, change it in Admin → Support details **and** rebuild with the override above —
the admin fixes every synced phone, the rebuild fixes the ones that never sync.

### 6. Customers: the seller's own register

The app now sends the name and Safaricom number typed at onboarding to the backend
**once** per install. It is the only customer detail that ever leaves the phone —
purchases, favourites and behaviour stay on the device. A failed attempt (offline at
onboarding) is retried on a later launch, and the endpoint is idempotent on the number,
so a retry or a reinstall updates that customer instead of duplicating them.

Admin → **Customers** shows total / new today / new this week, searches by name or
number, filters by date, removes customers singly or via select-all, and exports the
current filter to CSV. Removing a customer deletes only the seller's copy — their app
keeps working and re-registers if they reinstall.

### 7. In-app Play Store rating

After a purchase the customer actually received, Google's rating card appears **over**
the app — they rate, write a comment and submit without leaving Skylink Bingwa, and the
review lands on the Play listing.

It is governed by `core/review/ReviewPolicy.kt`:

| Rule | Why |
|---|---|
| Only after a payment that succeeded | Asking someone whose payment just failed is how apps earn one-star reviews |
| Not before the 2nd received purchase | A returning customer has an opinion worth writing down |
| At most once every 60 days | Google quotas the card invisibly; our stricter limit makes the one attempt count |
| A few seconds' delay, never immediate | The M-Pesa SMS, the caller-ID summary and the Safaricom confirmation all land first — a card in that pile-up is dismissed unread |
| Never over the open checkout sheet | It waits for the purchase sheet to close |

Two things worth knowing before you test it:

- **Google never tells us whether the card appeared.** The API reports nothing — not
  whether it showed, not whether the customer reviewed. So the attempt is recorded either
  way; we never re-ask on the assumption it did not show.
- **It cannot be tested from a normal build.** The card only works for an install the
  Play Store made. Use the internal test track or internal app sharing. On the direct
  APK the card can never work, so that flavour opens the Play listing instead, under the
  same 60-day rule.

There is deliberately no "Do you like the app?" question first — pre-filtering who sees
the card breaches Play policy.

### 8. Google Play is the only update channel

The `release` build type sets `GITHUB_UPDATER_ENABLED` false, and a build type overrides
a product flavour, so **neither production artifact** — direct APK or Play AAB — fetches
`update.json`, shows "Check for updates", posts the update notification, renders the Home
"update available" billboard, or raises the force-update gate. Only debug builds do.

`update.json` in the repository root is therefore for **debug installs only**. It is kept
current out of habit, not because a shipped build reads it.

## Server files to re-upload with this release

From `server/mybingwa-api/`: **`stk.php`**, **`lib.php`**, **`get_offers.php`**,
**`register_user.php`** (new), and `offers.sql` if you use the legacy table.

From `server/admin-v2/`: the whole folder as usual. **The two new migrations apply
themselves on the first admin request** — `018_offer_availability.sql` adds
`available_from` / `available_to` to the offers table (guarded against
`information_schema`, so a re-run is harmless), and `019_customers.sql` creates the
customer register. Nothing has to be done by hand. `register_user.php` also creates the
customer table itself if the admin has not run yet, so the order of upload does not
matter.

Both columns default to NULL, which means "sold all day". **No existing offer changes
behaviour until someone sets a window**, so uploading the server files is safe to do
before anyone touches the admin.

## What to test on the phone

1. **A windowed offer.** In the admin, set one offer to sell 17:00 → 23:00 and publish.
   In the app, outside that window the card must read "Available from 5:00 PM to 11:00
   PM", show an "Opens 5:00 PM" chip instead of Buy, and open the explanation on tap.
   Inside the window it must read "Available now" and buy normally.
2. **A window crossing midnight** (22:00 → 02:00): open at 23:00 and at 01:00, shut at
   15:00.
3. **The once-a-day block.** Buy a `Buy once a day` offer for 0712…, then reopen it:
   the list must say "Already bought today for 0712 345 678", and typing that number at
   checkout must block with the explanation while another number goes through.
4. **The midnight reset.** Buy a once-a-day bundle late in the evening and try again
   after midnight — it must go through. (Changing the phone's clock past midnight is
   enough to see it.)
5. **Onboarding.** Fresh install: name and number come first, then notifications, then
   Safaricom messages, with no way past either. Refuse twice and confirm the button
   becomes "Open settings and allow"; confirm "Close Skylink Bingwa" closes it.
6. **Revocation.** After onboarding, turn notifications off in Android settings and
   reopen the app — the blocking screen must appear.
7. **Settings** must no longer show the notification or SMS toggles.
8. **Review step**: tap either number and confirm it returns to the number fields.
9. **Support page on a fresh install with the phone in aeroplane mode.** Till, Paybill,
   support phone and WhatsApp must all be filled from the very first launch — this is
   the tester-reported bug, so test it exactly that way (install, no network, open
   Support) rather than on a phone that has already synced.
10. **Customers.** Finish onboarding on a test phone and confirm the customer appears in
    Admin → Customers within seconds, with "New today" incrementing. Then onboard the
    same number again (clear app data and repeat) and confirm it **updates** that row
    rather than adding a second one. Try the CSV export and a select-all removal.
11. **Registration retry.** Finish onboarding in aeroplane mode, then turn the network
    on and reopen the app — the customer must appear then.
12. **The rating card** (internal test track only — it cannot appear on a normally
    installed build). Complete two purchases, close the success sheet, and wait a few
    seconds. Confirm it does not appear on the first purchase, does not appear over the
    open sheet, and does not appear twice.
13. **No update prompts anywhere in the release build.** Settings must have no "Check for
    updates" control, no update notification must arrive at launch, and Home must show no
    "update available" billboard. If any of those appear, the build is a debug build.

## Carried over from v1.0.3 — still verify before publishing

The deployed `server/mybingwa-api/config.php` is per-server and git-ignored. Confirm in
cPanel that `party_b` is the production Till **4063396** (not the dev **4953696**) and
that `fulfilment_phone` is **0110092715**. Both can also be set from
Admin → Payment gateway.

Play Console notes (permissions declaration for `RECEIVE_SMS`, `READ_PHONE_STATE`
justification, no `REQUEST_INSTALL_PACKAGES` in the Play build, data-safety answers)
are unchanged from `release/Skylink-Bingwa-v1.0.3/README.md` — re-use that section verbatim.
