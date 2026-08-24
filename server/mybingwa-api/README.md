# Skylink Bingwa payment API (cPanel PHP)

Four small PHP endpoints that let the app run M-Pesa STK Push **without keeping any
Daraja secret inside the APK**. The app calls `stk.php` and polls `status.php`;
Daraja posts the result to `callback.php`. Your Daraja credentials live only here,
on your cPanel.

```
app  ──POST stk.php────────►  starts STK, returns CheckoutRequestID
customer enters M-Pesa PIN
Daraja ──POST callback.php─►  saves paid/failed + receipt
app  ──GET  status.php─────►  paid / failed / still checking
```

Files:
- `stk.php`, `status.php`, `callback.php` — the 3 public endpoints
- `config.php` — your credentials (fill on the server, never commit real values)
- `lib.php`, `db.php`, `offers.php` — shared code (blocked from the web by `.htaccess`)
- `schema.sql` — the one database table

---

## What you need first

1. Your cPanel login (from your host).
2. A domain already on that cPanel.
3. A Safaricom **Daraja** account with: Consumer Key, Consumer Secret, Passkey,
   your Short Code / Till number. For real money you must have completed **Go Live**
   in the Daraja portal. Use **sandbox** first to test.

---

## Step 1 — Create a subdomain

cPanel → **Domains** (or **Subdomains**) → Create.
- Subdomain: `api`
- Domain: your domain
- It fills a **Document Root** like `api.yourdomain.co.ke` or `public_html/api`.
  Note that folder — your files go there.

## Step 2 — Turn on HTTPS (required)

cPanel → **Security → SSL/TLS Status** → tick the new subdomain → **Run AutoSSL**.
Wait a few minutes until it shows a padlock. Daraja will not call an `http://` URL.

## Step 3 — Create the database

cPanel → **MySQL Databases**:
1. Create a database, e.g. `skylinkbingwa` (cPanel prefixes it, e.g. `user_skylinkbingwa`).
2. Create a user + password (save them).
3. Under **Add User To Database**, add the user with **All Privileges**.
   Write down the final DB name, user and password.

## Step 4 — Create the table

cPanel → **phpMyAdmin** → click your database → **Import** → choose `schema.sql` →
**Go**. (Or open the **SQL** tab and paste the contents of `schema.sql`.)

## Step 5 — Upload the files

cPanel → **File Manager** → open your subdomain's Document Root (from Step 1) →
**Upload**. Upload all of these into that folder:
`stk.php`, `status.php`, `callback.php`, `config.php`, `lib.php`, `db.php`,
`offers.php`, `.htaccess`.
(You do not need to upload `schema.sql` or this README, but it's harmless if you do —
`.htaccess` blocks them.)

> If File Manager hides `.htaccess`, click **Settings** (top-right) → tick
> **Show Hidden Files**.

## Step 6 — Fill in config.php

Copy `config.sample.php` to `config.php` (File Manager → right-click → Copy), then
edit `config.php` → **Edit**. `config.php` is git-ignored and holds your secrets;
`config.sample.php` is the safe template. Replace every `PUT_...`:
- `app_key` → invent a long random string. It must equal the app's
  **`PAYMENTS_APP_KEY`** GitHub secret (Step 8); requests without a matching
  `X-App-Key` header get `401 UNAUTHORISED`.
- `daraja_env` → `sandbox` to test, later `production`.
- `consumer_key`, `consumer_secret`, `passkey`, `business_shortcode` → from Daraja.
- `party_b` → your Till number (the number that receives the money).
- `transaction_type` → `CustomerBuyGoodsOnline` for a Till.
- `callback_secret` → invent a **second** long random string (different from
  `app_key`). This is the token that authenticates Daraja's callback.
- `callback_url` → `https://api.yourdomain.co.ke/callback.php?token=<callback_secret>`
  — it MUST include `?token=` with the exact `callback_secret` value, and it must
  be the exact URL you register with Daraja in Step 7.
- `paybill_shortcode` → your Paybill number for **buy-for-another** purchases
  (leave as a copy of `business_shortcode` if you only use a Till for now).
  Optional `paybill_passkey` only if Daraja gave the Paybill a distinct passkey.
- `callback_ip_allowlist` → leave `[]` (allow all) unless you want to restrict to
  Safaricom's callback IPs. `trusted_proxy_header` → leave `''` unless a proxy you
  control fronts this server.
- `db_name`, `db_user`, `db_pass` → from Step 3.
Save.

## Step 7 — Tell Daraja your callback

In the Daraja portal for your app/short code, set the **STK CallbackURL** to the
**tokenised** URL, exactly matching `callback_url` in `config.php`:
`https://api.yourdomain.co.ke/callback.php?token=<your callback_secret>`

The token is what proves a callback really came via your registered URL. A POST to
`callback.php` without the correct `?token=` (or, if you set one, from an IP
outside `callback_ip_allowlist`) is acknowledged to Safaricom but **ignored** — no
payment is ever marked paid. If you rotate `callback_secret`, update BOTH
`callback_url` in `config.php` AND the CallbackURL registered in the Daraja portal.

### Buy-for-another (Paybill) vs buy-for-self (Till)
- **Self** (default): STK uses the Till / `CustomerBuyGoodsOnline` with `party_b`.
- **Another**: when the app sends `forSelf=false` (or `route:"another"`), the STK
  uses `paybill_shortcode` with `CustomerPayBillOnline`, and the **recipient's
  number** becomes the M-Pesa AccountReference. The STK password uses the same
  Paybill shortcode. Set `paybill_shortcode` (and optional `paybill_passkey`)
  before enabling buy-for-another in production.

## Step 8 — Point the app at your API

Add two **GitHub repository secrets** (GitHub → repo → Settings → Secrets and
variables → Actions → New repository secret):
- `PAYMENTS_BASE_URL` = `https://api.yourdomain.co.ke/`  (keep the trailing slash)
- `PAYMENTS_APP_KEY`  = the same `app_key` you set in `config.php`

The CI build reads these and bakes the **URL** (not any Daraja secret) into the app.
The next debug APK from Actions will call your API. With no secrets set, the app
falls back to the built-in simulation.

## Step 9 — Test

1. In a browser open `https://api.yourdomain.co.ke/status.php`. You should see a
   JSON error like `{"status":"PAYMENT_FAILED","errorCode":"UNAUTHORISED"}`.
   That error is **good** — it proves the endpoint is live and the app-key guard works.
2. Install the new debug APK, choose an offer for **your own number**, pay. Your
   phone should get the M-Pesa prompt; after you enter your PIN the app shows
   **Payment received**. Check the `payments` table in phpMyAdmin to see the row.

---

## Sandbox vs production
- **Sandbox** uses Safaricom test credentials and test phone numbers (no real money).
- Switch `daraja_env` to `production` and use your live credentials only after Go Live.

## Admin panel (manage everything from your browser)
The `admin/` folder is a password-protected dashboard to manage **offers**,
**payment & support details**, and **notification templates** — no code, no rebuild.

1. It's already uploaded with the other files (the `admin/` subfolder).
2. In `config.php`, set `admin_user` and a strong `admin_pass`.
3. Open `https://mybingwa.blazetechscope.com/admin/` and sign in.
4. The dashboard **creates its own tables** on first load — no SQL import needed.
   (Optional: import `offers.sql` to pre-fill the current catalogue, and
   `settings.sql` to seed Till/Paybill/support.)

Changes you make go live on the app's **next online sync**:
- The app fetches `get_config.php` (Till/Paybill/support) and `get_offers.php`
  (catalogue) whenever it's online, and caches both so they still work offline.
- `stk.php` still recomputes the price from `offers.php` for now; once you're happy
  managing offers in the admin, that can read the `offers` table too (same endpoints).

## Endpoints summary
- `POST stk.php` — start STK (app).
- `GET status.php` — poll payment result (app).
- `POST callback.php` — Daraja result in.
- `GET get_config.php` — Till/Paybill/support (app sync).
- `GET get_offers.php` — catalogue (app sync).
- `admin/` — the management dashboard (browser).

## If something fails
- `TOKEN_FAILED` → wrong consumer key/secret, or wrong `daraja_env`.
- `STK_REJECTED` → wrong shortcode/passkey/till, or not Go-Live for production.
- App stuck on "Still checking" → callback not reaching you or being ignored; check
  the CallbackURL is exactly your `callback.php?token=<callback_secret>`, that the
  `?token=` matches `callback_secret` in `config.php`, and that HTTPS works.
  `status.php` also falls back to a direct Daraja query, so it should still resolve
  within ~30s even if the callback is ignored.
- Payment paid on the phone but the row is still `PAYMENT_REQUESTED` with a
  `FLAGGED amount mismatch` in `result_desc` → the callback's paid Amount did not
  match the server-recomputed price; it was held for manual review, not confirmed.
- `DB_UNAVAILABLE` → wrong db name/user/password in `config.php`.
