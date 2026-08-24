# Skylink Bingwa — Release & Google Play Publishing Runbook

This is the complete, ordered guide for shipping Skylink Bingwa for the **first
time**, written for someone who has never published to Google Play. Follow the
steps top to bottom. You do **not** need Android Studio — everything is built for
you by GitHub Actions on GitHub's servers.

> Owner actions are marked **[OWNER]**. These are things only you can do (they
> need your Google account, your money, your secret passwords, or your written
> store copy). Everything else is automated by the workflows in this repo.

---

## 0. The three things you end up with

| # | Deliverable | Exact file / source | Who gets it |
|---|---|---|---|
| a | **Direct v1 APK** | `Skylink-Bingwa-v1.0.0-direct.apk` — an asset on the **GitHub Release** produced by the `release.yml` workflow | End users who sideload from GitHub |
| b | **Play AAB** | `Skylink-Bingwa-v1.0.0-play.aab` — an asset on the **same GitHub Release run** | **Upload to Google Play only. Never hand the AAB to end users — it is not directly installable.** |
| c | **Signing keystore** | `my-upload-key.jks` — produced once by the `bootstrap-keystore.yml` workflow | Kept secret by you; its base64 lives in a GitHub secret so CI can sign |

The direct APK and the Play AAB come out of the **same release build**, share the
**same `applicationId` (`com.bingwasokoni`)** and are signed with the **same
permanent key**, so a user can move between the two channels and updates apply
correctly.

---

## Step A — Set the GitHub secrets

The release build reads its signing passwords and non-secret payment config from
**GitHub Actions secrets**. Set them at:

**GitHub → your `Skylink-Bingwa` repo → Settings → Secrets and variables → Actions →
New repository secret.**

Set these now (you will add `KEYSTORE_BASE64` in Step B, after the key exists):

| Secret | What it is | Notes |
|---|---|---|
| `STORE_PASSWORD` | Password protecting the keystore **file** | **[OWNER]** Choose a strong password. You will need it again in Step B. Save it in a password manager. |
| `KEY_PASSWORD` | Password protecting the **key** inside the keystore | **[OWNER]** Can be the same as `STORE_PASSWORD`, but a distinct one is fine. Save it too. |
| `KEY_ALIAS` | The key's name inside the keystore | Use **`upload`** — the build and the bootstrap workflow default to this alias. |
| `KEYSTORE_BASE64` | The keystore file, base64-encoded | **Added in Step B**, after you generate and back up the key. |
| `PAYMENTS_BASE_URL` | Base URL of the payment backend | Non-secret. Set to `https://mybingwa.blazetechscope.com/` (the app also defaults to this if unset). |
| `PAYMENTS_APP_KEY` | Shared header key so only our app can call the payment API | **[OWNER]** Get this value from the payment backend config. It is **not** a Daraja credential — Daraja secrets stay on the server. Without it, real online payments fail honestly (they are never faked). |

### CLI alternative

If you have the GitHub CLI (`gh`) installed and are logged in, you can set
secrets from a terminal instead of the web UI:

```bash
gh secret set STORE_PASSWORD          # paste value when prompted
gh secret set KEY_PASSWORD
gh secret set KEY_ALIAS               # type: upload
gh secret set PAYMENTS_BASE_URL       # https://mybingwa.blazetechscope.com/
gh secret set PAYMENTS_APP_KEY        # value from the payment backend
# KEYSTORE_BASE64 is set in Step B:
# gh secret set KEYSTORE_BASE64 < my-upload-key.jks.b64
```

Run `gh` from inside a clone of the repo (or add `-R wazimuautomate/Skylink_Bingwa`).

---

## Step B — Generate the permanent signing key (once, ever)

The signing key is the app's permanent identity. **Guard it like a house deed.**

> ⚠️ **This key is PERMANENT.** Every future update — on Google Play *and* on
> GitHub — must be signed with this exact key. **If you lose it, you can never
> update the app again** under `com.bingwasokoni`; you would have to publish a
> brand-new app and lose all your users. Back it up before you do anything else
> with it.

1. **[OWNER]** In GitHub → **Actions** tab → open the **`bootstrap-keystore`**
   workflow → **Run workflow**. (It asks for the alias and passwords, or reads
   them from the secrets you set in Step A — use alias `upload`.)
2. When the run finishes, open it and download the artifact
   **`my-upload-key.jks.gpg`** (the keystore, GPG-encrypted so it is safe to pass
   through the artifact download).
3. **Decrypt it locally** with the passphrase the workflow used/printed:

   ```bash
   gpg --output my-upload-key.jks --decrypt my-upload-key.jks.gpg
   ```

4. **[OWNER] Back up `my-upload-key.jks` offline in at least two separate
   places** (for example an encrypted USB drive and an encrypted cloud vault) —
   **before** you continue. Also save `STORE_PASSWORD`, `KEY_PASSWORD` and the
   alias `upload` with it. Never commit the `.jks` to the repo.
5. Turn the keystore into base64 and store it as the `KEYSTORE_BASE64` secret so
   CI can sign with it:

   ```bash
   base64 -w0 my-upload-key.jks > my-upload-key.jks.b64
   gh secret set KEYSTORE_BASE64 < my-upload-key.jks.b64
   ```

   (Or open `my-upload-key.jks.b64`, copy its whole contents, and paste it into
   a new `KEYSTORE_BASE64` secret in the web UI.)
6. Delete the local `.gpg`, `.b64` and any extra copies you do not intend to keep
   as a backup. Keep the securely-backed-up `.jks` forever.

---

## Step C — Produce the v1.0.0 release build

The `release.yml` workflow only runs on a **version tag** (`v*`) or a **manual
run**. It never runs on ordinary branches, so feature builds can never touch the
signing secrets.

**Option 1 — push a tag (recommended):**

```bash
git tag v1.0.0
git push origin v1.0.0
```

**Option 2 — manual run:** GitHub → **Actions** → **Release (signed)** → **Run
workflow** → enter version `1.0.0`.

Either way the workflow:

- builds the signed **direct APK** (`:app:assembleDirectRelease`) and the
  **Play AAB** (`:app:bundlePlayRelease`),
- names them `Skylink-Bingwa-v1.0.0-direct.apk` and `Skylink-Bingwa-v1.0.0-play.aab`,
- generates `Skylink-Bingwa-v1.0.0-direct.apk.sha256`,
- and publishes them as assets on a **GitHub Release** named *Skylink Bingwa v1.0.0*.

Download all three from **Releases → Skylink Bingwa v1.0.0**.

### Verify the APK checksum before trusting it

```bash
# Compare the computed hash to the published .sha256 file:
sha256sum -c Skylink-Bingwa-v1.0.0-direct.apk.sha256
# Expect: Skylink-Bingwa-v1.0.0-direct.apk: OK
```

(On Windows PowerShell: `Get-FileHash Skylink-Bingwa-v1.0.0-direct.apk -Algorithm
SHA256` and compare the hash to the contents of the `.sha256` file.)

Install `Skylink-Bingwa-v1.0.0-direct.apk` on a real phone and test before you go
further. The `.aab` is **only** for Google Play (Step D).

---

## Step D — Publish on Google Play (first time)

### D1. Create the developer account **[OWNER]**

1. Go to the Google Play Console and sign up as a developer with your Google
   account.
2. Pay the **one-time registration fee** (a small USD amount) and complete
   Google's identity verification. This can take a little time to approve.

### D2. Create the app **[OWNER]**

1. Play Console → **Create app**.
2. App name: **Skylink Bingwa**. Default language, app (not game), free.
3. Package name is set by the bundle you upload — it must be **`com.bingwasokoni`**
   (the AAB already carries this; you cannot change it later).

### D3. App signing / App integrity — **use your OWN key** (critical)

Because you distribute the app on **both** Google Play and a direct GitHub APK,
the two builds **must be signed by the same app-signing key**, or a phone that
installed one channel will refuse the update from the other ("signatures do not
match").

- In **App integrity → App signing**, when Play offers Play App Signing, choose
  the option to **use your own app-signing key / upload your existing key** — the
  same keystore you created in Step B — **rather than letting Google generate a
  new key**.
- **Plain-English trade-off:** if you let *Google generate* the signing key, the
  Play copy of the app is signed with a Google key you don't control. That
  signature would differ from your GitHub APK's signature, so users could not
  move between the Play and GitHub versions — each channel would be treated as a
  different app and cross-channel updates would break. Using your own key on both
  keeps them one and the same app.
- Follow Google's on-screen instructions to upload/encrypt the app-signing key
  (Play provides a `PEPK` tool for this). Keep your offline backup from Step B
  regardless.

### D4. Complete the required declarations **[OWNER]**

- **Privacy policy URL:** paste the public URL where you hosted `PRIVACY.md`
  (see that file's header). This is **required** — Play will not let you publish
  without it.
- **Data safety form:** answer it to match `PRIVACY.md`. In short: the app stores
  name, phone number and purchase activity **on the device**; it shares the
  recipient/payer number and payment details only to process an M-Pesa payment;
  it does **not** collect location, contacts or browsing, and does not use data
  for advertising. Declare M-Pesa payment processing as the reason data is
  shared. (The Play build ships **no** SMS permission, so there is nothing to
  declare for SMS.)
- **Content rating questionnaire:** complete it honestly (a utility/shopping app
  with no objectionable content).
- **Target audience & content:** set an adult audience (M-Pesa users); not
  directed at children.
- **App access:** the app needs no login to browse/buy, so declare that all
  functionality is available without special access.
- **Ads:** declare that the app contains no ads.

### D5. Store listing **[OWNER — you must supply the copy and images]**

- **App title:** Skylink Bingwa.
- **Short description** and **full description:** write these (what the app is —
  buy Safaricom Bingwa data/SMS/minutes/offers, pay with M-Pesa, works offline).
  You can reuse language from `RELEASE_NOTES_v1.0.0.md`.
- **App icon** (512×512), **feature graphic** (1024×500), and **phone
  screenshots** (at least 2–8). *Owner action: capture these from the app.*

### D6. Upload the AAB via Internal testing first

1. Play Console → **Testing → Internal testing → Create new release**.
2. Upload **`Skylink-Bingwa-v1.0.0-play.aab`** (from Step C's GitHub Release).
3. Add tester emails, save, review and **roll out to internal testing**.
4. Install via the tester opt-in link on a real phone and confirm the flow.

### D7. Promote to Production

When internal testing looks good and all the D4/D5 sections show complete
(green), create a **Production** release, reuse the same AAB (or a newer one),
set a rollout percentage, and submit for review. Google reviews first-time apps
before they go live.

---

## Step E — Shipping a later update (both channels)

Every update must go out on **both** channels so the two stay in sync:

1. **[OWNER]** Bump the version in `skylink-bingwa/app/build.gradle.kts`: increase
   **`versionCode`** (must always go up, e.g. 1 → 2) and set the new
   **`versionName`** (e.g. `1.0.1`). *(This file is owned by the app build work,
   not by this doc.)*
2. Commit, then tag and push `vX.Y.Z` (e.g. `git tag v1.0.1 && git push origin
   v1.0.1`). The `release.yml` workflow produces the new signed APK + `.sha256` +
   AAB on a new GitHub Release.
3. **GitHub / direct channel:** update **`update.json`** at the repo root (the
   in-app updater reads it) with the new version name, `versionCode`, the new
   APK download URL and its SHA-256. Direct-install users then get the in-app
   "update available" prompt. *(`update.json` is owned by the updater work.)*
4. **Play channel:** upload the new `Skylink-Bingwa-vX.Y.Z-play.aab` to a Play track
   (internal → production). Google auto-updates Play users.

Both builds carry the same `versionCode`/`versionName` and the same signing
identity, so a user on either channel supersedes correctly.

---

## What only you can do vs what is automated

| Task | Who |
|---|---|
| Choose signing passwords; keep the keystore backed up | **[OWNER]** |
| Set GitHub secrets (`STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`, `KEYSTORE_BASE64`, `PAYMENTS_APP_KEY`) | **[OWNER]** |
| Generate the keystore | `bootstrap-keystore.yml` (you click Run) |
| Build & sign the direct APK + Play AAB, checksum, GitHub Release | `release.yml` (automated on tag) |
| Create the Google Play developer account & pay the fee | **[OWNER]** |
| Choose "use your own signing key" in Play App Signing | **[OWNER]** |
| Write store listing copy; capture icon, feature graphic, screenshots | **[OWNER]** |
| Host `PRIVACY.md` and paste its URL into Play | **[OWNER]** |
| Fill the Data safety, content rating, target audience forms | **[OWNER]** |
| Upload the AAB, run internal testing, promote to production | **[OWNER]** |
| Bump `versionCode`/`versionName` for each update | **[OWNER]** (in `build.gradle.kts`) |
| Update `update.json` for direct-channel updates | **[OWNER]** (edits the file) |

---

### Reference: files and names this runbook depends on

- Workflows: `.github/workflows/release.yml`, `.github/workflows/bootstrap-keystore.yml`
- Signed build tasks: `:app:assembleDirectRelease`, `:app:bundlePlayRelease`
- Release assets: `Skylink-Bingwa-v1.0.0-direct.apk`, `Skylink-Bingwa-v1.0.0-direct.apk.sha256`, `Skylink-Bingwa-v1.0.0-play.aab`
- Permanent identity: `applicationId = com.bingwasokoni`, `versionName 1.0.0`, `versionCode 1`, key alias `upload`
- In-app update manifest (direct channel): `update.json` at the repo root
- Privacy policy source: `PRIVACY.md` at the repo root
