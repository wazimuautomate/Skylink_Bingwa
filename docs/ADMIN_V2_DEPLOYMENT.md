# Skylink Bingwa Admin V2 — cPanel Deployment Guide

Admin V2 is plain PHP 8.2+ with **no runtime Composer/Node dependency**. You upload the
folder, create `config.php`, and open the URL — it installs itself (no phpMyAdmin, no SQL).

---

## 0. The two folders you deploy

| Folder in the repo | Goes to (cPanel) | What it is |
|---|---|---|
| `server/mybingwa-api/` | `public_html/` (site root) | The **payment API** the app already uses: `get_config.php`, `get_offers.php`, `stk.php`, `callback.php`, `status.php`, plus `config.php` (Daraja secrets). |
| `server/admin-v2/` | `public_html/admin/` | The **admin panel** — open at `https://your-domain/admin/`. |

Both share **one** MySQL database: admin-v2 uses the `mb_` table prefix, the payment API
uses the unprefixed tables (`offers`, `settings`, `templates`, `payments`). Admin-v2 reads
`payments` read-only. Do **not** also upload the old `mybingwa-api/admin/` folder — the new
`admin/` replaces it.

---

## 1. Requirements

- PHP **8.2+** with PDO MySQL, OpenSSL and GD (for billboard images).
- MySQL 8 / MariaDB 10.4+.
- HTTPS (cPanel AutoSSL is fine).

---

## 2. Create the database (one time, point-and-click)

cPanel → **MySQL Databases** → create a database and a user, add the user to the database
with **All Privileges**. Note the database name, user and password. This is the only manual
DB step — there is **no phpMyAdmin import and no SQL to run**; every table is created
automatically.

---

## 3. Upload the payment API (site root)

1. Upload the contents of `server/mybingwa-api/` into `public_html/`.
2. Copy `config.sample.php` → `config.php` and fill in: `app_key`, the Daraja
   credentials/shortcodes (`business_shortcode`, `party_b`, `paybill_shortcode`, `passkey`,
   `callback_url`), the fulfilment SMS settings, `admin_user`/`admin_pass`, and the same
   `db_*` values from step 2. Leave the offline `paybill_number`/`support_number` **blank**
   — you set those in the admin.
3. The `payments` table auto-creates on first use (`db.php`) — you do **not** import
   `schema.sql`.

---

## 4. Upload the admin (public_html/admin)

1. Upload `server/admin-v2/` into `public_html/admin/`.
2. Copy `config/config.sample.php` → `config/config.php` and fill in:
   - `app_key` — a long random string (`bin2hex(random_bytes(32))`).
   - `db.*` — the **same** database as the payment API. Keep `prefix` = `mb_`.
   - `bootstrap_admin` — your name/email, and **set a password (10+ chars)** for a fully
     silent install. (If you leave it blank, a generated one is written once to
     `storage/first-login-password.txt`.)
   - `environment` — `production`.
3. Make sure `storage/` and `uploads/` are writable (755 is fine on cPanel).

`config/config.php` is git-ignored and blocked from the web by `.htaccess`.

---

## 5. Open the admin — it installs itself

Open `https://your-domain/admin/`. On this first visit the panel automatically:

- creates every `mb_*` table,
- seeds the offers/templates/config (with **blank** Till/Paybill — you set them),
- publishes a **baseline version**, so nothing shows as a "draft".

Then sign in with your `bootstrap_admin` email + password.

---

## 6. First real setup

1. **Support details** → enter your offline Till, Paybill, support phone and WhatsApp.
2. **Offers / App configuration** → adjust as needed (offer IDs are auto-generated).
3. Header **Preview changes** → **Publish changes**. This creates the next version the app
   downloads.

---

## 7. Verify

- `https://your-domain/admin/api/health` → `{ "ok": true, "configVersion": 1, … }`.
- `https://your-domain/admin/api/app-data` → the published JSON (offers, support, etc.).
- The **Payments** page lists the same rows the payment API records.

---

## 8. Hardening checklist

- [ ] `/admin/config/config.php` → 403 (not downloadable).
- [ ] `/admin/app/…`, `/admin/database/…`, `/admin/storage/…` → 403.
- [ ] `/admin/uploads/x.php` → denied (uploads serve images, not scripts).
- [ ] HTTPS enforced; cookies `Secure`, `HttpOnly`, `SameSite=Lax`.
- [ ] `app_key` is long and unique.
- [ ] Delete `storage/first-login-password.txt` after your first login (if it was created).

---

## 9. Updating later

Upload the changed files. Any new database migrations apply **automatically** on the next
visit (idempotent) — there is no separate migrate step to run.

---

## 10. Tests (CI or SSH)

```bash
php tests/run.php
```

Dependency-free logic tests (canonical JSON, checksum, regex safety, billboard tokens,
publish validation, snapshot diff, CSV safety, masking) run without a database.
