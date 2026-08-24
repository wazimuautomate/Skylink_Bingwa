# Skylink Bingwa Admin V2

A small, private control panel for the Skylink Bingwa app — built for **two people** (one
Super Admin, one Admin). It manages offers, billboard adverts, notifications, Safaricom
message templates, payments (read-only), support/payment details, remote app configuration
and update rules, with an append-only audit log and a simple **draft → publish → rollback**
workflow. A single read-only endpoint (`GET /api/app-data`) serves the latest published
data to the Android app.

Runs on plain cPanel PHP 8.2+ with **no Composer/Node dependency at runtime**. Create the
database + user once in cPanel, add `config.php`, then open the admin URL — it installs
every table and seeds itself automatically (no phpMyAdmin, no manual SQL).

## Layout

```
admin-v2/
  index.php              front controller (all routes) + auto-install
  config/config.sample.php
  app/
    Core/                kernel: Router, Request, Response, View, Auth, Rbac, Csrf,
                         Session, Database, Config, Signer, Snapshot, Audit, Installer
    Controllers/         one per sidebar page + Api/SyncController
    Repositories/        Offer + Payment (read-only legacy payments)
    Services/            Publishing, Billboard, TemplateMatcher, ImageUploader,
                         RateLimiter, Settings, RollbackRestorer
    Views/               server-rendered pages (+ partials, layout)
    Support/             helpers, Icons (inline SVG), Csv
  database/
    migrations/*.sql     schema (mb_ prefixed)   migrate.php   seed.php   seed_data.php
  assets/                css + js
  uploads/               billboard images (non-executable)
  tests/run.php          dependency-free pure-logic tests
```

## Key properties

- **Two roles only:** Super Admin (full control) and Admin (you pick which sidebar pages
  they can see/edit via `mb_admin_users.allowed_pages`). No permission matrix, no 2FA.
- **Coexists** with `server/mybingwa-api` in the same DB via the `mb_` prefix; reads the
  legacy `payments` table read-only; never modifies legacy data or initiates payments.
- **Draft/publish/rollback:** editing app data creates a draft; "Publish changes" writes an
  immutable, versioned `mb_configuration_releases` row (SHA-256 checksum). Rollback restores
  a previous version as a new, later version.
- **Sync API:** `GET /api/app-data` returns the latest published offers, adverts, templates,
  support details, app config, update info and version. ETag/`304`; rate-limited.
- **Offline Till/Paybill** shown to customers are set on the Support page — never hardcoded,
  and separate from the server-side STK shortcode (which stays in `mybingwa-api/config.php`).

## Run

See `docs/ADMIN_V2_DEPLOYMENT.md`.

```bash
# Normally automatic on first visit; these also work from the CLI:
php database/migrate.php && php database/seed.php
php tests/run.php
```
