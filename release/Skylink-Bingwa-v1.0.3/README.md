# Skylink Bingwa v1.0.3 — production release pack

**versionName** `1.0.3` · **versionCode** `4` · **applicationId** `com.bingwasokoni`
Built by GitHub Actions "Release (signed)" run `31265509950` from tag `v1.0.3`
(commit `5a73fdf` on `main`). Signed with the permanent upload key held in Actions
secrets — no keystore exists on any local machine.

## What is in this folder

| File | What it is | Where it goes |
|---|---|---|
| `Skylink-Bingwa-v1.0.3-play.aab` | **Google Play bundle** | Play Console → Production → Create release → upload this |
| `Skylink-Bingwa-v1.0.3-play.aab.sha256` | Checksum for the bundle | Verify before uploading |
| `Skylink-Bingwa-v1.0.3-direct.apk` | Signed APK for direct/sideload distribution | GitHub Release / direct download. **Not** for Play |
| `Skylink-Bingwa-v1.0.3-direct.apk.sha256` | Checksum for the direct APK | Verify before distributing |
| `Skylink-Bingwa-v1.0.3-debug.apk` | Debug build (`com.bingwasokoni.debug`, "Skylink Bingwa Dev") | Test phones only — installs alongside the real app |
| `Skylink-Bingwa-v1.0.3-debug.apk.sha256` | Checksum for the debug APK | — |

Verify any file with `sha256sum -c <file>.sha256`. The direct APK's checksum was
checked against the one CI generated and matched.

The AAB and the direct APK carry the **same version and the same signing identity**,
so a customer can move between the Play and direct channels and updates supersede
correctly. Never distribute the `.aab` to end users — it is not installable.

## Play Console submission notes

1. **Permissions Declaration is required.** This build ships `RECEIVE_SMS`, kept by
   explicit owner decision so the app can read Safaricom's own bundle-delivery and
   balance messages. Play restricts the SMS permission group: the submission must
   declare the use case, and Play may still refuse it. If it is refused, the fallback
   is two edits — restore `tools:node="remove"` for `RECEIVE_SMS` in
   `app/src/play/AndroidManifest.xml`, and set `SMS_DETECTION_AVAILABLE` to `false`
   for the `play` flavour in `app/build.gradle.kts`. The app degrades cleanly: SMS
   detection simply stays inactive.
2. `READ_PHONE_STATE` is used only to pick the SIM whose number the customer declared
   as theirs when launching the M-Pesa menu for an offline payment. It is best-effort
   and falls back to the default SIM when denied.
3. The Play build ships **no** `REQUEST_INSTALL_PACKAGES` and performs **no** in-app
   update check — Play distributes and updates it natively.
4. Data safety: no account, no cloud sync. Name, number, favourites and Activity are
   installation-local. SMS content is read on the device and never uploaded.

## Before you publish — one unresolved item

The deployed `server/mybingwa-api/config.php` is per-server and git-ignored, so its
contents could not be read from here. Confirm in cPanel File Manager that:

- `party_b` is **4063396** (the production Till that should COLLECT buy-for-myself
  money). The copy on this machine still holds **4953696**, which project history
  records as the DEV Till. Getting this wrong sends real customer payments to the
  wrong till.
- `fulfilment_phone` is the production number **0110092715** (the local copy holds the
  dev number), otherwise buy-for-another confirmations reach the wrong operator.

Both can now also be set from Admin → Payment gateway: the overlay that reads them
from the admin database was fixed this release (it had been looking for the admin at a
sibling path that only exists in the repository, so on cPanel it silently did nothing).

## Server files to re-upload with this release

From `server/mybingwa-api/`: `stk.php`, `lib.php`, `config.sample.php`. `stk.php` now
prices every purchase from the same published catalogue the app displays, so editing
or removing offers in the admin is safe — previously it priced from a static file and
a price change would have charged the old amount and left the customer's real payment
flagged as an unconfirmed mismatch.

## What to test on the phone

- **Onboarding** now ends with permission steps: notifications, then Safaricom
  messages. Both should be asked for, with the reason shown before the system dialog.
- **Home billboards** come only from the admin now — nothing is hardcoded. Publish
  three offer billboards and confirm they are three *different* colours; unpublish them
  all and confirm the board clears on the next sync.
- **Daily notifications**: four windows — offline 06:30–08:00 and 17:00–19:00 offer
  data; connected 07:00–09:00 and 17:00–20:00 offer minutes/SMS. One per slot per day
  at a minute that varies daily, nothing outside 06:30–20:00, and all of it subject to
  the shared engine's quiet hours, daily cap and de-duplication.
- **Buy for another number** end to end: the Paybill route, the recipient's number as
  the account reference, and the fulfilment SMS naming the recipient.
- **Offline purchase** with the Till, including on a fresh install that has never
  synced (it must say "connect to refresh", never show a blank number to pay).
