# Referral & commission system — deployment guide

Follow this top to bottom. It takes you from the current live server to a working
referral programme, and it deliberately turns automatic M-Pesa payouts on **last**,
after everything else has been proven.

Nothing here needs Android Studio, and nothing needs a developer.

---

## Before you start — the one decision to make first

**Open the admin panel and record your real margin on each offer before you set any
commission rate.** The whole system is built so a commission can never be saved above
the margin on that offer, but it can only enforce that once the margin is recorded.

Until you do this, the default commission rate stays at **0%** and referrers earn only
the Ksh 10 joining bonus. That is the safe state — it is not a bug.

---

## Step 1 — Upload the server files

### Option A — the GitHub workflow (recommended)

1. Go to the repository on GitHub → **Actions** → **Deploy to cPanel (FTP)**.
2. Click **Run workflow**, leave **dry_run** ticked, and run it.
3. Read the log. It lists exactly what it would upload. Confirm you see the new files:
   `referrals.php`, `b2c.php`, `fcm.php`, `check_referral_code.php`,
   `referral_summary.php`, `otp_request.php`, `otp_verify.php`, `withdraw.php`,
   `b2c_result.php`, `b2c_timeout.php`, `cron_referrals.php`.
4. Run it again with **dry_run unticked**.

The workflow never overwrites `config.php`, `config/config.php`, `uploads/` or
`storage/`, so your secrets and uploaded images are safe.

### Option B — cPanel File Manager (manual)

1. cPanel → **File Manager** → open your API document root (where `stk.php` lives).
2. Upload every `.php` file from `server/mybingwa-api/` **except `config.php`**, plus
   the updated `.htaccess`.
3. **Delete** `get_app_notifications.php` and `get_notification_templates.php` — the
   scheduled-notifications feature they served has been removed.
4. Open the `admin/` folder and upload everything from `server/admin-v2/` **except**
   `config/config.php`, `uploads/`, `storage/`, `tests/`, `bin/` and `cutover/`.
5. **Delete** `admin/app/Controllers/NotificationsController.php`,
   `admin/app/Services/NotificationService.php` and the whole
   `admin/app/Views/notifications/` folder.

---

## Step 2 — Add the new settings to `config.php`

cPanel → File Manager → your API document root → right-click `config.php` → **Edit**.

Add these lines just before the final `return $config;`. Leave the B2C values empty
for now — you will fill them in at Step 8.

```php
// ---- M-Pesa B2C (referral payouts) — filled in at Step 8 -------------------
$config['b2c_shortcode']            = '';
$config['b2c_initiator_name']       = '';
$config['b2c_security_credential']  = '';
$config['b2c_command_id']           = 'BusinessPayment';
$config['b2c_result_url']           = 'https://YOUR-API-DOMAIN/b2c_result.php';
$config['b2c_timeout_url']          = 'https://YOUR-API-DOMAIN/b2c_timeout.php';

// ---- Cron guard -----------------------------------------------------------
// Invent a long random string. Anyone with this string can trigger your cron.
$config['cron_key'] = 'PUT_A_LONG_RANDOM_STRING_HERE';
```

Replace `YOUR-API-DOMAIN` with the same domain already in your `callback_url`.

Save.

---

## Step 3 — Create the database tables

You do not need phpMyAdmin. Open the admin panel in a browser:

```
https://YOUR-DOMAIN/admin/
```

Log in. The migrations run automatically on the first page load. You should now see
**Referrals** in the left sidebar, and **Notifications** should be gone.

> If the sidebar still shows Notifications, the admin files did not upload. Repeat Step 1.

### What this creates and what it removes

**Creates:** referrers, referrals, device registry, commission ledger, withdrawals,
notification outbox, OTP challenges, device tokens, money-event log — plus two new
columns on your offers table (`commission_bps`, `margin_bps`).

**Removes permanently:** the scheduled-notification campaigns, their wording
variations and the catalogue tables behind the old editor. Instant Push is untouched
and remains the way to message customers.

---

## Step 4 — Set up the cron jobs

cPanel → **Cron Jobs** → add each of these. Replace `YOUR-API-DOMAIN` and
`YOUR_CRON_KEY` with your real values.

| Frequency | Command |
|---|---|
| Every minute | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=outbox"` |
| Every minute | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=submit"` |
| Every 5 minutes | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=reconcile"` |
| Every 15 minutes | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=float"` |
| Once an hour | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=velocity"` |
| Once a day (e.g. 3am) | `wget -q -O /dev/null "https://YOUR-API-DOMAIN/cron_referrals.php?key=YOUR_CRON_KEY&task=integrity"` |

In cPanel's "Common Settings" dropdown: *Once Per Minute* is `* * * * *`, *Every Five
Minutes* is `*/5 * * * *`, *Twice Per Hour* covers the 15-minute one (or type
`*/15 * * * *`), *Once Per Hour* is `0 * * * *`, and for the daily one set Minute `0`,
Hour `3`, everything else `*`.

**These are not optional.** Without `outbox` nobody gets notified. Without `submit`
no withdrawal is ever sent. Without `reconcile` a payout that M-Pesa never confirms
stays stuck forever.

Each job refuses to run if another copy of itself is still running, so overlapping
schedules are safe.

### Check it works

Paste the outbox URL into your browser. You should see something like:

```
outbox: 0 due, 0 sent, 0 deferred/dead
```

A `forbidden` response means the `cron_key` in `config.php` does not match the URL.

---

## Step 5 — Set the commission rates

1. Admin → **Offers** → edit each offer.
2. Fill in **Your margin** in basis points — `100` = 1%. If you make Ksh 2 on a
   Ksh 50 bundle, that is 4%, so enter `400`.
3. Leave **Referral commission** blank on most offers; it falls back to the default.

Then Admin → **Referrals** → *Programme settings*:

- **Default commission rate** — start conservative. `100` (1%) is a sensible opening
  position while you watch the numbers.
- **Signup bonus** — `1000` cents = Ksh 10. This is the value you asked for.
- **Signup bonus only unlocks after the friend buys** — **leave this ticked.** It is
  the single most important anti-farming control you have.
- **Minimum withdrawal** — `20000` cents = Ksh 200.

Save.

---

## Step 6 — Install the app and test earning

1. Copy `release/Skylink-Bingwa-v1.0.17/Skylink-Bingwa-v1.0.17-direct-debug.apk`
   to a phone and install it. It installs **alongside** your live app as
   *Skylink Bingwa Dev*, so nothing you already have is disturbed.
2. Complete onboarding on **phone A**. Leave the referral field empty.
3. Open the app → tap the **gift icon in the top-right corner** → note the code
   (e.g. `SK391R`). Tap it to copy.
4. On **phone B**, install and onboard using phone A's code. As you type the sixth
   character you should see *"You were referred by \<name\>"*.
5. Phone A should receive an SMS and a push saying phone B joined.
6. Admin → **Referrals → Referrers** → phone A should show 1 referral and a Ksh 10
   balance, marked as held.
7. Buy a bundle on **phone B**. Within a minute phone A's commission should rise, and
   the Ksh 10 bonus should unlock.

**Test the anti-farming rule too:** uninstall the app from phone B, reinstall it, and
onboard with a different number using phone A's code again. The referral must **not**
be credited a second time. Admin → Referrals → the device appears under *Blocked /
suspicious devices*. If it does credit twice, stop and do not go further.

---

## Step 7 — Test withdrawal with payouts still OFF

On phone A, tap **Withdraw**. You should be told withdrawals are paused. That is the
kill switch doing its job.

---

## Step 8 — Turn on real M-Pesa payouts

Do this only once Steps 1–7 all behaved.

### 8a. Get B2C credentials from Safaricom

B2C is a **separate product** from the STK Push you already use. It needs its own
Go-Live and its own shortcode. In the Daraja portal:

1. Create or open your app and add the **B2C** product.
2. Complete **Go Live** for B2C. This is a review process with Safaricom and it takes
   time — start it early.
3. Note your **B2C shortcode**, create an **API initiator** username, and generate the
   **SecurityCredential** for that initiator.

### 8b. Fund the payout account

B2C pays from your **utility account**. Your Till and Paybill collections do **not**
move money into it automatically — you must transfer funds there. If it runs dry,
withdrawals queue with an honest "this is taking a little longer" message rather than
failing, and the float monitor alerts you.

### 8c. Fill in `config.php`

Go back to the block from Step 2 and fill in `b2c_shortcode`, `b2c_initiator_name`
and `b2c_security_credential`.

### 8d. Register the callback URLs

In the Daraja portal, set the B2C result and timeout URLs to exactly what you put in
`config.php`:

```
https://YOUR-API-DOMAIN/b2c_result.php
https://YOUR-API-DOMAIN/b2c_timeout.php
```

Both must be `https`. Safaricom will not call an `http` URL.

### 8e. Turn it on, small

Admin → **Referrals** → *Programme settings*:

- Set **Business-wide daily payout cap** to something small to begin with —
  `200000` cents (Ksh 2,000) for the first week.
- Tick **Automatic M-Pesa payouts on**.
- Save. The confirmation message will say payouts are now ON in capital letters.

Withdraw once from phone A with a real balance. Watch Admin → **Referrals →
Withdrawals**. It should go `Queued → Sent to M-Pesa → Paid` within a minute or two,
and the recipient should get both an SMS and the M-Pesa confirmation.

Raise the daily cap once you have seen several real payouts complete.

---

## Day-to-day: what to watch

Open **Admin → Referrals** once a day and look at three things:

1. **Outstanding liability** — money you owe referrers. It should grow slowly and
   drop when people withdraw. A sudden jump means something is wrong.
2. **Unresolved payouts** — if this is not zero, open the Withdrawals queue. These are
   payouts M-Pesa has not confirmed either way. The system will never guess, because
   guessing wrong either pays someone twice or refuses money that was already sent.
   Check your M-Pesa statement for that exact amount and number, then use **Resolve**
   to close it with what you actually found.
3. **Ledger drift** — this should always be empty. If it is not, do not pay anyone out
   until you understand why. It means something wrote a balance outside the ledger.

---

## If something goes wrong

**Stop all payouts immediately:** Admin → Referrals → untick **Automatic M-Pesa
payouts on** → Save. Nothing further leaves your account. Balances are untouched and
customers keep earning.

**One person is abusing the programme:** Admin → Referrals → Referrers → open them →
set status to **Payout held**. They keep earning, but nothing is paid out until you
decide. Use **Banned** only when you are certain.

**A payout is stuck:** leave it. The reconciler asks M-Pesa every five minutes. Only
step in with **Resolve** once you have checked the actual M-Pesa statement.

**Nobody is getting SMS or push:** check the cron jobs from Step 4. Admin → Referrals
shows the notification queue — a large "waiting" number means cron is not running.

---

## Getting the properly signed app for real customers

The APK in `release/Skylink-Bingwa-v1.0.17/` is a **test build**. It is signed with
the debug key and installs as a separate app called *Skylink Bingwa Dev*, so you can
test safely without touching your live install.

The real, customer-facing signed build comes from CI, exactly as every previous
release has:

1. Commit and push these changes.
2. Create and push the tag:
   ```
   git tag v1.0.17
   git push origin v1.0.17
   ```
3. GitHub → **Actions** → the **release** workflow builds and signs both the direct
   APK and the Play AAB with your real upload key, and attaches them to a GitHub
   release.
4. Download those artefacts into `release/Skylink-Bingwa-v1.0.17/` alongside the test
   build, and upload the AAB to the Play Console.

Do not try to sign locally — the keystore is intentionally not in this repository.
