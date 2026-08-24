# Skylink Bingwa server — deployment

The production server is a shared cPanel host. There is no SSH pipeline, no Composer step and
no build server: files are uploaded through File Manager and the application configures itself
on the next request. This document is the checklist for doing that safely.

## Contents

- [What is deployed](#what-is-deployed)
- [Building the upload package](#building-the-upload-package)
- [Deployment checklist](#deployment-checklist)
- [Database migrations](#database-migrations)
- [Configuration that is never uploaded](#configuration-that-is-never-uploaded)
- [Verifying a deployment](#verifying-a-deployment)
- [If something goes wrong](#if-something-goes-wrong)

---

## What is deployed

| Folder | Purpose | Lives at |
| --- | --- | --- |
| `server/admin-v2/` | The admin panel and the app sync API. | `public_html/admin/` (or wherever it is installed today) |
| `server/mybingwa-api/` | The legacy payment endpoints (STK push, callback, status). | `public_html/` |

Both share one MySQL database. `admin-v2` prefixes its tables with `mb_` so it never collides
with the legacy `offers` / `settings` / `payments` tables.

---

## Building the upload package

Uploading the whole project every time is slow and risks overwriting something edited on the
server. Build a package containing only what changed:

```powershell
# everything this branch changed against main
pwsh server/tools/build-deploy-package.ps1

# only what changed since a specific deployment
pwsh server/tools/build-deploy-package.ps1 -Since v1.0.2
```

The script writes `server/dist/skylink-bingwa-server-<timestamp>-<sha>.zip` plus a `.sha256` file.
Inside the ZIP:

- the changed files, with the folder structure they must land in (the `server/` prefix is
  stripped, so the archive root **is** the installation folder);
- `DEPLOY-README.md` — which migrations ship, which files were deleted from the project, and the
  post-upload checks;
- `DEPLOY-MANIFEST.txt` — the exact file list.

The package **never** contains `config/config.php`, uploads, storage, keys or `.env` files.

---

## Deployment checklist

1. **Back up.** In cPanel → phpMyAdmin → Export → Quick, save the database. Download the current
   `admin-v2/config/config.php` as well.
2. **Build the package** as above and check `DEPLOY-MANIFEST.txt` matches what you expect.
3. **Upload.** Extract the ZIP locally, then upload its contents into the installation folder in
   File Manager, keeping the folder structure. Choose "overwrite" when prompted.
4. **Delete removed files.** If `DEPLOY-README.md` lists files under "Files removed from the
   project", delete those from the server by hand. Nothing else deletes them.
5. **Open the admin panel and sign in.** The first request applies any pending migrations
   automatically.
6. **Open Preview & publish.** It must list only what you actually changed. If it lists things
   nobody touched, stop and report it — that is the exact bug this release fixed.
7. **Publish.** Devices pick the new configuration up on their next sync.
8. **Verify** using the checks below.

---

## Database migrations

Migrations live in `server/admin-v2/database/migrations/*.sql` and are applied in filename order.
Each applied file is recorded in `mb_migrations`, so re-running is always safe.

They run **automatically** on the first request after upload
(`App\Core\Installer::autoProvision()` → `App\Database\Migrator::run()`). You do not need
phpMyAdmin.

To run them explicitly instead — for example from a cPanel cron job with a PHP CLI:

```
php database/migrate.php
```

Migrations added by this release:

| File | What it adds |
| --- | --- |
| `013_sms_rules.sql` | SMS rules, event-type and pattern-type catalogues, the starter Safaricom rules, and an import of any existing v1 message templates. |
| `014_notifications_v2.sql` | Notification variations, category / trigger / variable catalogues, scheduling, cooldown and enabled columns. |
| `015_release_management.sql` | Per-resource versions, release field-level change records, release identifiers, and the audit `module` column. |
| `016_billboard_media.sql` | Billboard media type, thumbnails, display order, target action and enabled flag. |
| `017_categories_flags.sql` | Offer categories and feature flags as editable configuration. |

None of them drop a column or a table. Existing data is preserved.

---

## Configuration that is never uploaded

`server/admin-v2/config/config.php` holds the database credentials, the optional sync API key and
the signing key paths. It is git-ignored, excluded from every package, and must be edited only on
the server. `server/admin-v2/config/config.sample.php` documents every key.

Signing keys (`*.pem`) live outside the web root and are referenced by path from `config.php`.
Never place a private key inside the repository or inside an uploaded package.

---

## Verifying a deployment

```
GET  https://<host>/<admin>/api/health
GET  https://<host>/<admin>/api/sync/manifest
GET  https://<host>/<admin>/api/app-data
```

- `health` must return `"ok": true` and the `configVersion` you just published.
- `manifest` must list every resource with a version and a checksum.
- `app-data` must return the full published snapshot and an `ETag` header. Requesting it again
  with `If-None-Match: <that etag>` must return `304`.

In the admin panel:

- **Preview & publish** shows no pending changes immediately after a publish.
- **Releases** shows the new version with its release identifier and change count.
- **Audit log** shows a `publish.execute` entry naming you.

On a phone: open the app, pull to refresh, and confirm offers, adverts and support details match
what the admin shows.

---

## If something goes wrong

- **The admin shows a blank page or a 500.** The error is in the cPanel error log. The most
  common cause is a partially uploaded file — re-upload.
- **A migration failed.** The admin logs it and keeps serving. Check `mb_migrations` to see which
  files applied, fix the cause, and reload the admin to retry. Migrations are idempotent.
- **The app stopped receiving data.** Check `api/health`. If the database is down it returns
  `"ok": false`. Devices keep working from their cached configuration — this is by design, so
  there is no emergency: fix the server and the next sync recovers.
- **A publish went out with a mistake.** Open **Releases**, choose the previous version and use
  Roll back. That copies the old contents into the working draft; review it in Preview and
  publish again as a new version. Old releases are immutable and are never rewritten.
