# Skylink Bingwa — App Sync Contract (Server → Android)

This document is the exact contract for the versioned, read-only sync API exposed by
**Admin V2** (`server/admin-v2`). It is the forward path for the Android app to receive
published configuration (offers, billboards, message templates, support details, app
config, version rules) as a signed, immutable snapshot.

> **Status of the Android side.** The shipped app (`versionCode 1`, `com.bingwasokoni`)
> does **not** yet have a WorkManager sync worker, Room persistence, ETag handling or
> signature verification. It fetches `get_offers.php` / `get_config.php` on connectivity
> regain and replaces its in-memory lists. Therefore this API has been **built and is
> testable on the server**, and the Android integration is specified here as a handoff.
> The app connection is **not** claimed as completed. The legacy endpoints keep working
> unchanged so the in-testing app is unaffected.

---

## 1. Endpoints

Base: `https://<your-domain>/<admin-path>/` (e.g. `https://mybingwa.blazetechscope.com/admin/`).

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/v1/app/manifest` | Small envelope: current version, checksum, signature, snapshot URL. |
| GET | `/api/v1/app/snapshot/{version}` | The immutable canonical snapshot for a version. |
| GET | `/api/v1/app/sync` | Combined: manifest + snapshot in one call (or `304`). |
| GET | `/api/v1/app/offers` | Backward-compatible offers shape from the published snapshot. |
| GET | `/api/v1/app/config` | Backward-compatible support/config shape. |
| GET | `/api/v1/app/templates` | Backward-compatible `TemplateSet` shape. |
| POST | `/api/v1/app/sync-events` | Optional anonymous telemetry (install id, version). |
| GET | `/api/v1/health` | DB + current version + signed flag. |

**Auth / limits.** Optional `X-Sync-Key` header if `security.sync_api_key` is set. Per-IP
rate limit (`sync.rate_limit_per_minute`, default 60). All responses are published,
app-safe data only — never admin users, roles, drafts, audit logs or secrets.

---

## 2. Manifest

`GET /api/v1/app/manifest`

```json
{
  "schemaVersion": 1,
  "configVersion": 7,
  "publishedAt": "2026-07-25T18:20:11Z",
  "snapshotUrl": "https://skylinkbingwa.example/admin/api/v1/app/snapshot/7",
  "checksum": "9f2c…(sha256 hex)…",
  "checksumAlgorithm": "SHA-256",
  "signature": "base64(RSA-SHA256 over the canonical snapshot bytes) or null",
  "signatureAlgorithm": "RS256",
  "minClientVersionCode": 1,
  "validUntil": null
}
```

Response header `ETag: "<checksum>"`. Send `If-None-Match: "<checksum>"` to get `304 Not
Modified` when nothing changed.

---

## 3. Snapshot

`GET /api/v1/app/snapshot/{version}` returns the **canonical** JSON that the checksum and
signature were computed over. Serve/verify **verbatim** (do not re-serialize before
verifying). Headers: `ETag`, `Cache-Control: public, max-age=31536000, immutable`,
`X-Checksum-SHA256`, and (if signed) `X-Signature` + `X-Signature-Algorithm`.

```json
{
  "schemaVersion": 1,
  "configVersion": 7,
  "publishedAt": "2026-07-25T18:20:11Z",
  "offers": [
    {
      "id": "data_6", "category": "DATA", "name": "2GB", "price": 110,
      "validity": "24 Hrs", "band": "Daily",
      "dailyRule": "MULTIPLE_PER_DAY", "policy": "MULTIPLE_PER_DAY",
      "maxPerDay": null, "commercialTag": "", "offlineEligible": true, "restrictions": ""
    }
  ],
  "billboards": [
    {
      "id": 3, "kind": "simple", "priority": 5, "linkedOfferId": "data_6",
      "tag": "BEST VALUE", "headline": "2GB for KSh 110", "body": "Stay connected for 24 Hrs.",
      "ctaLabel": "Buy now", "ctaDestination": "skylinkbingwa://offers/data_6",
      "imageUrl": "uploads/ab12…webp", "altText": "", "audienceRule": "all",
      "frequencyCap": 0, "startsAt": null, "endsAt": null
    }
  ],
  "templates": {
    "version": 7,
    "delivery": [
      { "id": "data_bingwa_sokoni", "senderId": "Safaricom", "category": "DATA",
        "pattern": "received\\b.*?\\d+\\s*(?:MB|GB).*?from\\s+Bingwa\\s+Sokoni",
        "description": "Bingwa Sokoni data delivery", "purpose": "delivery",
        "priority": 5, "correlationWindowMinutes": 30 }
    ],
    "lowBalance": [ /* same shape, purpose = low_balance | very_low_balance */ ]
  },
  "support": {
    "tillNumber": "4953696", "paybillNumber": "40450595",
    "supportNumber": "0727921038", "supportWhatsapp": "254727921038",
    "offlineSelfInstructions": "…", "offlineOtherInstructions": "…",
    "supportBanner": "", "workingHours": "Daily, 8:00 AM - 9:00 PM"
  },
  "appConfig": {
    "maintenanceMode": false, "maintenanceTitle": "", "maintenanceMessage": "",
    "maintenanceAllowHelp": true, "syncIntervalMinutes": 360,
    "snapshotCacheHours": 168, "offlineConfigValidHours": 168,
    "quietHours": { "start": "21:00", "end": "07:00" },
    "campaignDailyCap": 2,
    "featureFlags": { "sms_parsing": false, "billboards": true },
    "personalisation": { "frequency_weight": 1.0, "value_weight": 0.6, "validity_weight": 0.4, "diversity_floor": 0.2, "max_step_up": 3.0, "top_pool": 5 },
    "emergencyDisable": { "offers": [], "campaigns": [], "routes": [] }
  },
  "version": {
    "latestVersionCode": 1, "latestVersionName": "1.0.0", "minSupportedVersionCode": 1,
    "mandatory": false, "playStoreUrl": "https://play.google.com/…", "apkUrl": "",
    "apkSha256": "", "rolloutPercent": 100, "releaseNotes": "Initial public release."
  }
}
```

---

## 4. Backward-compatible shapes (match the current app exactly)

- `GET /api/v1/app/offers` → `{ "offers": [ { id, category, name, price(int), validity, band, dailyRule } ] }`
- `GET /api/v1/app/config` → `{ tillNumber, paybillNumber, supportNumber, supportWhatsapp, updatedAt }`
- `GET /api/v1/app/templates` → `{ version, delivery: [ {id, senderId, category, pattern, description} ], lowBalance: [ … ] }`

`dailyRule` is `ONCE_PER_DAY` when the policy is once-per-recipient, else `MULTIPLE_PER_DAY`
(the shipped app treats anything ≠ `ONCE_PER_DAY` as repeatable).

---

## 5. Signature verification (Android)

1. Generate the keypair once, on the server (see the deployment guide). Embed **only the
   public key** in the app (`res/raw` or assets). The private key never leaves the server.
2. On sync: fetch manifest → if `configVersion` > local, GET the snapshot **bytes**.
3. Verify `SHA-256(bytes) == manifest.checksum`. Then verify `manifest.signature` over the
   **exact bytes** with RSA/SHA-256 and the embedded public key.
4. Only on success, parse and write to Room in **one transaction**. On any failure, keep
   the previous valid local snapshot (never wipe Room on a failed/invalid sync).

Kotlin sketch:

```kotlin
val sig = Signature.getInstance("SHA256withRSA")
sig.initVerify(publicKey)          // parsed from the embedded PEM/DER
sig.update(snapshotBytes)          // the raw response body, unmodified
val valid = sig.verify(Base64.decode(manifest.signature, Base64.NO_WRAP))
```

---

## 6. Android integration steps (handoff, not yet done)

1. Add `androidx.work` (WorkManager) and Room (`@Entity`/`@Dao`/`@Database`) for
   offers, billboards, templates, support, appConfig, version + a `sync_meta` table
   (lastVersion, checksum, lastSyncAt).
2. Add Retrofit DTOs mirroring §3, separate from Room entities and UI models.
3. `SyncWorker` (periodic, min interval from `appConfig.syncIntervalMinutes`, clamped):
   manifest → conditional GET → verify → atomic Room replace. Exponential backoff.
4. One-time sync on cold start when stale (do not block cached Home).
5. Keep the UI observing Room only — never render raw network responses.
6. Optional: POST `/api/v1/app/sync-events` with an anonymous install id for telemetry.
7. FCM sync hint is **not** present today; adding it is a separate future task
   (`RemoteTemplateSync` is the existing seam for template sync).

Until these land, the app keeps using the legacy endpoints; nothing breaks.

---

## 7. Error codes

| HTTP | Meaning |
|---|---|
| 200 | OK (fresh data) |
| 304 | Not modified (matched `If-None-Match`) |
| 401 | Missing/wrong `X-Sync-Key` (only if configured) |
| 404 | Unknown snapshot version |
| 429 | Rate limited (`Retry-After: 60`) |
| 503 | No configuration published yet |
