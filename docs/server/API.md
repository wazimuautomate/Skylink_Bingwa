# Skylink Bingwa — Public Server API

The read-only synchronisation API published by **Admin V2** (`server/admin-v2`). It is the
only interface the Android app needs: the server publishes configuration, the app reads it
and caches it. Everything here can be integrated without reading any PHP.

Two rules govern the whole design:

1. **The API never accepts user data.** No endpoint takes or returns a phone number, an
   M-Pesa receipt, a purchase, a favourite, a name or any other personal value. The only
   identifier that may ever reach the server is an optional, anonymous installation id, and
   the app works exactly the same when it is omitted.
2. **The API is offline-first.** No response ever means "discard what you hold". A failure,
   a timeout or a partial download leaves the device's cached configuration untouched.

---

## Table of contents

- [1. Base URL, versions and compatibility](#1-base-url-versions-and-compatibility)
- [2. Authentication and limits](#2-authentication-and-limits)
- [3. Common headers](#3-common-headers)
- [4. Endpoints](#4-endpoints)
  - [4.1 GET /api/health](#41-get-apihealth)
  - [4.2 GET /api/app-data](#42-get-apiapp-data)
  - [4.3 GET /api/sync/manifest](#43-get-apisyncmanifest)
  - [4.4 GET /api/sync/resource/{key}](#44-get-apisyncresourcekey)
  - [4.5 GET /api/sync/resources](#45-get-apisyncresources)
- [5. Error responses](#5-error-responses)
- [6. The published snapshot, section by section](#6-the-published-snapshot-section-by-section)
- [7. Resources, versions and checksums](#7-resources-versions-and-checksums)
- [8. ETag and If-None-Match](#8-etag-and-if-none-match)
- [9. Recommended client sync algorithm](#9-recommended-client-sync-algorithm)
- [10. Optional anonymous telemetry](#10-optional-anonymous-telemetry)
- [11. Adding a new resource](#11-adding-a-new-resource)

---

## 1. Base URL, versions and compatibility

The admin application is deployed into a directory, so every path below is relative to that
directory:

```
https://<your-domain>/<admin-path>/
e.g. https://skylinkbingwa.example/admin/
```

A full request URL is therefore `https://skylinkbingwa.example/admin/api/sync/manifest`.

Every URL the API returns (for example `resources.offers.url` in the manifest) is a
**relative path** such as `api/sync/resource/offers`. Resolve it against the base URL the
app is configured with. The server never returns a hardcoded host name, so the same
published data works on staging and production.

Compatibility guarantees:

| Guarantee | Meaning |
|---|---|
| `GET /api/app-data` is frozen | It returns the stored snapshot bytes verbatim, with the same ETag semantics it has always had. Devices already in the field keep working with no change. |
| Snapshot keys are only ever added | `offers`, `billboards`, `templates`, `support`, `appConfig` and `version` keep the exact shape the shipped app reads. New sections (`categories`, `notifications`, `smsRules`, `featureFlags`, `resourceVersions`) are additive. |
| Unknown keys must be ignored | A client must tolerate sections and fields it does not know. |
| The sync protocol does not change when a resource is added | New resources appear in the manifest automatically. See [section 11](#11-adding-a-new-resource). |

`schemaVersion` (currently `1`) identifies the shape of the snapshot. A client should refuse
to apply a snapshot whose `schemaVersion` is higher than it understands, and keep its
current data.

---

## 2. Authentication and limits

**Shared key (optional).** If the operator sets `security.sync_api_key` in the server
config, every endpoint below requires the header:

```
X-Sync-Key: <the shared key>
```

A missing or wrong key returns `401 {"error":"unauthorised"}`. When the setting is empty
(the default) no key is required — the API only ever exposes published, app-safe data.

The key is a deployment convenience, not a user credential. It identifies no one, it is the
same for every installation, and it must be treated as a low-value secret: do not build any
security decision on it and never place a value in the APK that also grants write access to
anything.

**Rate limit.** Per client IP, fixed one-minute window, `sync.rate_limit_per_minute`
(default 60). Exceeding it returns `429 {"error":"rate_limited"}` with `Retry-After: 60`.
The limiter fails open: if its storage is briefly unavailable, requests are allowed rather
than blocked.

The app should never come near the limit — the normal pattern is one manifest call every
`appConfig.syncIntervalMinutes` (60 to 1440, default 360).

---

## 3. Common headers

Request headers:

| Header | Required | Meaning |
|---|---|---|
| `X-Sync-Key` | Only if configured | Shared API key (see above). |
| `If-None-Match` | No | Conditional request. See [section 8](#8-etag-and-if-none-match). |
| `X-Install-Id` | No | Anonymous installation id, at most 64 characters of `A-Z a-z 0-9 . _ -`. See [section 10](#10-optional-anonymous-telemetry). |
| `X-App-Version-Code` | No | The reporting app's `versionCode`, digits only. Telemetry only. |
| `X-Config-Version` | No | The config version the device currently holds, digits only. Telemetry only. |

Response headers:

| Header | Endpoints | Meaning |
|---|---|---|
| `ETag` | all data endpoints | The entity tag to send back in `If-None-Match`. |
| `Cache-Control: no-cache` | all data endpoints | Always revalidate; never serve a stale copy from an intermediate cache without asking. |
| `X-Config-Version` | all data endpoints | The published config version this response came from. |
| `Retry-After: 60` | `429` only | Seconds to wait. |

---

## 4. Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `api/health` | Liveness and a summary of what is published. |
| GET | `api/app-data` | The whole published snapshot (original v1 contract). |
| GET | `api/sync/manifest` | Cheap "what changed?" call. |
| GET | `api/sync/resource/{key}` | One resource. |
| GET | `api/sync/resources` | Several resources in one round trip. |

All five are `GET`. There is no `POST`, `PUT` or `DELETE` in the public API.

### 4.1 GET /api/health

Liveness plus a summary of the current published release. Safe to call from a monitor.

**Parameters:** none.

**Example request**

```http
GET /admin/api/health HTTP/1.1
Host: skylinkbingwa.example
```

**Example response — 200**

```json
{
  "ok": true,
  "configVersion": 12,
  "time": "2026-07-31T09:40:02Z",
  "schemaVersion": 1,
  "signed": true,
  "releaseUid": "rel-20260731-v12-1a2b3c4d",
  "resources": {
    "offers": 12,
    "categories": 8,
    "billboards": 11,
    "notifications": 12,
    "smsRules": 9,
    "templates": 9,
    "support": 4,
    "appConfig": 4,
    "featureFlags": 7,
    "version": 10
  }
}
```

| Field | Type | Meaning |
|---|---|---|
| `ok` | bool | The database answered. `false` means the server is degraded — do not treat it as "no configuration". |
| `configVersion` | int | Latest published config version, `0` when nothing is published. |
| `time` | string | Server time, UTC, ISO-8601 `Z`. |
| `schemaVersion` | int | Snapshot schema of the current release. |
| `signed` | bool | The current release carries a signature. |
| `releaseUid` | string | Human-quotable release identifier, `""` when nothing is published. |
| `resources` | object | `resourceKey -> version`. `{}` when nothing is published. |

Health never fails hard: if the release cannot be read it answers `ok: false` with
`configVersion: 0` rather than an error page. It is still subject to the shared key and the
rate limit.

### 4.2 GET /api/app-data

The complete published snapshot, served as the exact bytes that were checksummed and
signed. This is the original contract and it does not change.

**Parameters:** none.

**Example request**

```http
GET /admin/api/app-data HTTP/1.1
Host: skylinkbingwa.example
If-None-Match: "9f2c8a1e...b7"
```

**Example response — 200** (body abbreviated; see [section 6](#6-the-published-snapshot-section-by-section))

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
ETag: "9f2c8a1e...b7"
Cache-Control: no-cache
X-Config-Version: 12
```

```json
{
  "appConfig": { "...": "..." },
  "billboards": [],
  "categories": [],
  "configVersion": 12,
  "featureFlags": {},
  "notifications": [],
  "offers": [],
  "publishedAt": "2026-07-31T09:12:04Z",
  "resourceVersions": { "offers": 12, "smsRules": 9 },
  "schemaVersion": 1,
  "smsRules": [],
  "support": { "...": "..." },
  "templates": { "...": "..." },
  "version": { "...": "..." }
}
```

The body is **canonical JSON**: object keys are sorted, slashes and unicode are not escaped.
Verify the checksum and the signature over these raw bytes before parsing — do not
re-serialise first.

**Responses:** `200`, `304`, `401`, `429`, `503`.

### 4.3 GET /api/sync/manifest

The cheap call a device makes first. A few hundred bytes describing the release and every
resource in it, so the device can decide what (if anything) to download.

**Query parameters**

| Name | Type | Meaning |
|---|---|---|
| `since` | int, optional | The config version the device already holds. Adds `since`, `changed` and `upToDate` to the response. Invalid or negative values are ignored as if absent. |

**Example request**

```http
GET /admin/api/sync/manifest?since=11 HTTP/1.1
Host: skylinkbingwa.example
X-Install-Id: 7f3a1c0b9d2e4f6a
```

**Example response — 200**

```json
{
  "configVersion": 12,
  "publishedAt": "2026-07-31T09:12:04Z",
  "checksum": "9f2c8a1e4b7d5c6f0a3e2d1b8c7a6f5e4d3c2b1a0f9e8d7c6b5a4938271605f4",
  "schemaVersion": 1,
  "signed": true,
  "signatureAlgo": "RS256",
  "minClientVersionCode": 1,
  "releaseUid": "rel-20260731-v12-1a2b3c4d",
  "resources": {
    "offers":        { "version": 12, "checksum": "3b1f...", "count": 29, "url": "api/sync/resource/offers" },
    "categories":    { "version": 8,  "checksum": "a70c...", "count": 4,  "url": "api/sync/resource/categories" },
    "billboards":    { "version": 11, "checksum": "5e42...", "count": 3,  "url": "api/sync/resource/billboards" },
    "notifications": { "version": 12, "checksum": "c19d...", "count": 14, "url": "api/sync/resource/notifications" },
    "smsRules":      { "version": 9,  "checksum": "8d55...", "count": 10, "url": "api/sync/resource/smsRules" },
    "templates":     { "version": 9,  "checksum": "0ba3...", "count": 1,  "url": "api/sync/resource/templates" },
    "support":       { "version": 4,  "checksum": "f612...", "count": 1,  "url": "api/sync/resource/support" },
    "appConfig":     { "version": 4,  "checksum": "22ae...", "count": 1,  "url": "api/sync/resource/appConfig" },
    "featureFlags":  { "version": 7,  "checksum": "6c98...", "count": 1,  "url": "api/sync/resource/featureFlags" },
    "version":       { "version": 10, "checksum": "d4f1...", "count": 1,  "url": "api/sync/resource/version" }
  },
  "since": 11,
  "changed": ["offers", "notifications"],
  "upToDate": false
}
```

| Field | Type | Meaning |
|---|---|---|
| `configVersion` | int | The release version. Increases by one on every publish. |
| `publishedAt` | string, nullable | UTC ISO-8601 publish time of this release. |
| `checksum` | string | SHA-256 (hex) of the canonical bytes of the whole snapshot. Also the `ETag` value, without quotes. |
| `schemaVersion` | int | Snapshot schema. |
| `signed` | bool | A signature exists for this release. |
| `signatureAlgo` | string | `RS256` when signed, `""` otherwise. |
| `minClientVersionCode` | int | The oldest app build the operator still supports. Informational: the server does not block old builds, it never withholds data from a device. |
| `releaseUid` | string | Stable, quotable id for support and audit, e.g. `rel-20260731-v12-1a2b3c4d`. |
| `resources` | object | One entry per resource present in this release. Keys are resource keys. |
| `resources.<key>.version` | int | The config version at which this resource last actually changed. |
| `resources.<key>.checksum` | string | SHA-256 of this resource's canonical bytes; the `ETag` of its resource endpoint. |
| `resources.<key>.count` | int | Items in a list resource; `1` for a singleton, `0` when absent. |
| `resources.<key>.url` | string | Relative path to fetch it. Always resolve against your base URL. |
| `since` | int | Echo of the request parameter. Present only when `since` was sent. |
| `changed` | string[] | Resource keys whose `version` is greater than `since`. Present only when `since` was sent. |
| `upToDate` | bool | `true` when `changed` is empty. Present only when `since` was sent. |

`resources` is never filtered by `since` — the full map is always returned so a device can
also verify the checksums of resources it believes are current.

`ETag` is the snapshot checksum, so an unchanged release answers `304` with no body.

**Responses:** `200`, `304`, `401`, `429`, `503`.

### 4.4 GET /api/sync/resource/{key}

One resource, taken from the **published** snapshot only. Draft or working content is never
served here.

**Path parameter**

| Name | Meaning |
|---|---|
| `key` | A resource key from the manifest, e.g. `offers`, `smsRules`, `support`. |

**Example request**

```http
GET /admin/api/sync/resource/smsRules HTTP/1.1
Host: skylinkbingwa.example
If-None-Match: "8d55c3a1..."
```

**Example response — 200**

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
ETag: "8d55c3a1..."
Cache-Control: no-cache
X-Config-Version: 12
```

```json
{
  "resource": "smsRules",
  "version": 9,
  "configVersion": 12,
  "checksum": "8d55c3a1...",
  "count": 10,
  "publishedAt": "2026-07-31T09:12:04Z",
  "data": [
    {
      "id": "data_bingwa_sokoni",
      "name": "Bingwa Sokoni data delivery",
      "senderId": "Safaricom",
      "patternType": "regex",
      "pattern": "received\\b.*?\\d+\\s*(?:MB|GB).*?from\\s+Bingwa\\s+Sokoni",
      "caseSensitive": false,
      "event": "DATA_RECEIVED",
      "secondaryEvents": [],
      "category": "DATA",
      "bundleType": "Daily",
      "captures": { "allowance": 1 },
      "correlationWindowMinutes": 30,
      "priority": 500
    }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `resource` | string | The resource key, echoed. |
| `version` | int | Config version at which this resource last changed. |
| `configVersion` | int | The release this response was taken from. |
| `checksum` | string | SHA-256 of the resource's canonical bytes (see [section 7](#7-resources-versions-and-checksums)). |
| `count` | int | Items for a list resource, `1` for a singleton. |
| `publishedAt` | string, nullable | Publish time of the release. |
| `data` | array or object | The section exactly as it appears in the snapshot. |

The `ETag` is the **resource** checksum, not the snapshot checksum. A device therefore gets
a `304` for a resource that did not move even when the release version did.

**Unknown key** returns `404`:

```json
{
  "error": "unknown_resource",
  "supported": ["offers", "categories", "billboards", "notifications", "smsRules",
                "templates", "support", "appConfig", "featureFlags", "version"]
}
```

`supported` lists the resources **this release actually publishes**, so it is also the
correct answer for a resource that a newer server would have but this release predates.

**Responses:** `200`, `304`, `401`, `404`, `429`, `503`.

### 4.5 GET /api/sync/resources

The batch form. A device with three stale resources spends one round trip instead of three.

**Query parameters**

| Name | Type | Meaning |
|---|---|---|
| `keys` | string, optional | Comma-separated resource keys, e.g. `offers,smsRules,support`. Absent or empty means **all** resources. At most 20 keys are considered per request. |
| `since` | int, optional | Return only the resources whose version is greater than this. |

Unknown keys never fail the request: they are returned in `unknown` and the known ones are
still served. Duplicates are collapsed. Key names are sanitised before they are echoed.

**Example request**

```http
GET /admin/api/sync/resources?keys=offers,smsRules,wallet HTTP/1.1
Host: skylinkbingwa.example
```

**Example response — 200**

```json
{
  "configVersion": 12,
  "publishedAt": "2026-07-31T09:12:04Z",
  "checksum": "9f2c8a1e...",
  "resources": {
    "offers": {
      "resource": "offers",
      "version": 12,
      "configVersion": 12,
      "checksum": "3b1f...",
      "count": 29,
      "publishedAt": "2026-07-31T09:12:04Z",
      "data": [ { "id": "data_6", "category": "DATA", "name": "2GB", "price": 110, "...": "..." } ]
    },
    "smsRules": {
      "resource": "smsRules",
      "version": 9,
      "configVersion": 12,
      "checksum": "8d55...",
      "count": 10,
      "publishedAt": "2026-07-31T09:12:04Z",
      "data": []
    }
  },
  "unknown": ["wallet"]
}
```

Each entry of `resources` has exactly the shape of the single-resource response. When
`since` is sent, the response also carries `since` (int) and `upToDate` (bool, `true` when
nothing needed sending).

`GET api/sync/resources` with no parameters returns every resource — the same content as
`api/app-data`, in this newer envelope. Prefer `api/app-data` for a first full download,
because its bytes are the signed bytes.

The `ETag` of a batch response covers the release **and** the requested key set, so a repeat
of the same request answers `304`.

**Responses:** `200`, `304`, `401`, `429`, `503`.

---

## 5. Error responses

Every error is JSON with an `error` key.

| Status | Body | When | What the client must do |
|---|---|---|---|
| `304` | empty | `If-None-Match` matched the current `ETag`. | Keep the cached copy. Nothing else. |
| `401` | `{"error":"unauthorised"}` | `X-Sync-Key` is required and was missing or wrong. | Keep the cached copy. Do not retry in a loop; the build is misconfigured. |
| `404` | `{"error":"unknown_resource","supported":[...]}` | The resource key is not published by this release. | Keep the cached copy of that resource. Drop the key from the request set and continue with the others. |
| `429` | `{"error":"rate_limited"}` + `Retry-After: 60` | Per-IP limit exceeded. | Back off for at least `Retry-After` seconds. Keep the cached copy. |
| `503` | `{"error":"no_published_configuration"}` | Nothing has ever been published, or the release could not be read. | Keep the cached copy. **This is not an instruction to clear data.** A brand-new install simply stays on its bundled defaults and retries later. |

Any other status (500, a gateway error, a timeout, an unparseable body) is treated the same
way: fail the sync, keep the previous data, retry with backoff.

There is no response in this API that means "delete what you have".

---

## 6. The published snapshot, section by section

The snapshot is what `api/app-data` returns and what the resource endpoints slice up. Top
level:

| Key | Type | Meaning |
|---|---|---|
| `schemaVersion` | int | Shape of this snapshot. Currently `1`. |
| `configVersion` | int | The release version. |
| `publishedAt` | string | UTC ISO-8601 publish time. |
| `resourceVersions` | object | `resourceKey -> version` for this release. Lets a client that only downloaded the full snapshot still track per-resource versions. |
| `offers` | array | Buyable offers. |
| `categories` | array | Offer categories for grouping and labelling. |
| `billboards` | array | Promotional slides. |
| `notifications` | array | Local notification RULES. |
| `smsRules` | array | Local SMS parsing rules. |
| `templates` | object | Legacy view of the SMS rules for pre-SMS-Rules app builds. |
| `support` | object | Payment routes and support contacts. |
| `appConfig` | object | App-wide configuration. |
| `featureFlags` | object | `flagKey -> bool`. |
| `version` | object | App update rule. |

### 6.1 `offers` (list)

Only offers with an active status are published, ordered by the operator's sort hint, then
category, then price.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Stable offer id, e.g. `data_6`. Never reused. |
| `category` | string | `DATA`, `SMS`, `MINUTES` or `SPECIAL`. |
| `name` | string | Display name, e.g. `2GB`. |
| `price` | int | Price in KSh. Always a whole number, always at least 1. |
| `validity` | string | Human validity text, e.g. `24 Hrs`. |
| `band` | string | Grouping band, e.g. `Daily`, `Weekly`. |
| `dailyRule` | string | Legacy purchase-awareness value: `ONCE_PER_DAY` or `MULTIPLE_PER_DAY`. Kept for app builds that only understand these two. |
| `policy` | string | The real policy: `MULTIPLE_PER_DAY`, `ONCE_PER_RECIPIENT_PER_DAY` or `MAX_PER_RECIPIENT_PER_DAY`. |
| `maxPerDay` | int, nullable | Purchases allowed per recipient per day when `policy` is `MAX_PER_RECIPIENT_PER_DAY`. |
| `commercialTag` | string | Short marketing tag, may be empty. |
| `offlineEligible` | bool | The offer may be bought with cached offline instructions. |
| `restrictions` | string | Free-text restriction note, may be empty. |

Purchase-awareness day boundaries are `Africa/Nairobi`.

### 6.2 `categories` (list)

Enabled categories, in display order.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Category key, matching `offers[].category`. |
| `label` | string | Display label. |
| `description` | string | Short supporting line. |
| `accent` | string | Accent token the app maps to its own palette. Never a raw colour to paint blindly. |
| `sortOrder` | int | Ascending display order. |

### 6.3 `billboards` (list)

Active or scheduled, enabled slides, ordered by `displayOrder` then `priority`. A slide
whose linked offer is unavailable is dropped at publish time, so a published billboard never
contains an unresolved `{{token}}`.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Billboard id. |
| `kind` | string | `offer` when a linked offer exists, otherwise `announcement`. |
| `priority` | int | Ranking weight. |
| `displayOrder` | int | Ascending display order. |
| `linkedOfferId` | string, nullable | The offer this slide promotes. |
| `tag` | string | Short badge text, may be empty. |
| `headline` | string | Resolved headline. |
| `body` | string | Resolved body. |
| `ctaLabel` | string | Call-to-action label, e.g. `Buy now`. |
| `ctaDestination` | string | v1 deep link. Newer clients should prefer `targetAction`. |
| `mediaType` | string | `none`, `image` or `gif`. |
| `imageUrl` | string | Relative asset path, e.g. `uploads/ab12.webp`. Empty when there is no image. |
| `thumbUrl` | string | Relative still-frame path for an animated asset. Empty when there is none. |
| `altText` | string | Accessibility text. |
| `targetAction` | string | `none`, `offer`, `category`, `url` or `internal`. |
| `clickUrl` | string | External URL when `targetAction` is `url`. |
| `internalAction` | string | Named in-app destination when `targetAction` is `internal`. |
| `targetCategory` | string | Category key when `targetAction` is `category`. |
| `audienceRule` | string | Audience selector evaluated on-device, e.g. `all`. |
| `frequencyCap` | int | Maximum shows per day. `0` means uncapped. |
| `startsAt` | string, nullable | UTC ISO-8601 window start. `null` on simple slides, which are always on. |
| `endsAt` | string, nullable | UTC ISO-8601 window end. `null` on simple slides. |

### 6.4 `notifications` (list)

Notification **rules**, not sends. The server does not push these; the app evaluates the
trigger, day, window and cooldown locally, picks one wording variation at random and
substitutes `{{variables}}` on-device. Nothing about the customer leaves the phone.

| Field | Type | Meaning |
|---|---|---|
| `id` | int | Campaign id. |
| `name` | string | Operator-facing name. |
| `category` | string | `SUPPORT`, `OFFLINE`, `ONLINE`, `MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`, `PROMOTION`, `PURCHASE_SUCCESS`, `BUNDLE_RECEIVED`, `LOW_DATA`, `VERY_LOW_DATA`, `NO_DATA`, `LOW_MINUTES`, `LOW_SMS`, `GIFT_RECEIVED`, `GENERAL`, `SYSTEM`. |
| `trigger` | string | `manual`, `offline`, `online`, `sms_event`, `purchase_event`, `bundle_expiry`, `time_based` or `promotion`. |
| `triggerEvent` | string | The SMS event key when `trigger` is `sms_event`, else empty. |
| `priority` | string | `low`, `normal` or `high`. |
| `variations` | array | One or more `{ "title": string, "body": string }`. Never empty. |
| `deepLink` | string | In-app destination when tapped, may be empty. |
| `linkedOfferId` | string, nullable | Offer this message refers to. |
| `startsOn` | string, nullable | `YYYY-MM-DD` first eligible day. |
| `endsOn` | string, nullable | `YYYY-MM-DD` last eligible day. |
| `daysOfWeek` | int[] | Allowed days, Monday = 1. Empty means every day. |
| `timeStart` | string | `HH:MM` Africa/Nairobi. Empty means any time. |
| `timeEnd` | string | `HH:MM` Africa/Nairobi. Empty means any time. |
| `cooldownMinutes` | int | Minimum gap between two shows of this rule. |
| `frequencyCap` | int | Maximum shows per day. `0` means uncapped. |
| `respectQuietHours` | bool | Suppress inside the app's quiet hours. |
| `suppressRecentPurchase` | bool | Do not show shortly after a purchase. |
| `expiresAt` | string, nullable | UTC ISO-8601 after which the rule must not fire. |

Promotional categories remain optional for the customer, and the app must still apply its
own permission, quiet-hours and campaign-cap rules on top of these fields.

### 6.5 `smsRules` (list)

How the app understands a Safaricom message, strongest priority first. Evaluation is
entirely local: no message, number or balance is ever sent to the server.

| Field | Type | Meaning |
|---|---|---|
| `id` | string | Stable rule key. |
| `name` | string | Operator-facing description. |
| `senderId` | string | Required sender. Empty matches any sender. |
| `patternType` | string | `regex`, `contains`, `starts_with` and similar catalogue values. |
| `pattern` | string | Regex source, phrase, or newline/comma separated keywords depending on `patternType`. |
| `caseSensitive` | bool | Case-sensitive matching. |
| `event` | string | Primary event, e.g. `DATA_RECEIVED`, `LOW_DATA`, `NO_DATA`, `GIFT_RECEIVED`. |
| `secondaryEvents` | string[] | Additional events the same match implies. |
| `category` | string | `DATA`, `SMS`, `MINUTES`, `SPECIAL` hint, may be empty. |
| `bundleType` | string | `Hourly`, `Daily`, `Weekly`, `Monthly` hint, may be empty. |
| `captures` | object | Capture-group map, e.g. `{"amount":1,"allowance":2}`. `{}` when the rule captures nothing. |
| `correlationWindowMinutes` | int | How long after a purchase a match may still be considered related. |
| `priority` | int | Higher wins when several rules match. |

A regular expression from this section is untrusted input for the device: compile it with a
timeout or a length guard and skip a rule that fails to compile rather than crashing.

### 6.6 `templates` (object, legacy)

A derived, backwards-compatible view of `smsRules` for app builds shipped before SMS Rules
existed. It is never edited on its own. Only `regex` rules with a v1 equivalent appear here.

| Field | Type | Meaning |
|---|---|---|
| `version` | int | Kept in step with `configVersion`. |
| `delivery` | array | Delivery-detection templates. |
| `lowBalance` | array | Low-balance templates. |

Each entry: `id`, `senderId`, `category`, `pattern`, `description`, `purpose`
(`delivery`, `low_balance` or `very_low_balance`), `priority` (ascending — v1 ordering, so
the strongest rule sorts first) and `correlationWindowMinutes`.

New clients should read `smsRules` and ignore `templates`.

### 6.7 `support` (object)

| Field | Type | Meaning |
|---|---|---|
| `tillNumber` | string | M-Pesa Till used for an own-number offline purchase. |
| `paybillNumber` | string | M-Pesa Paybill used for another-number offline purchase (recipient number is the account). |
| `supportNumber` | string | Support phone number to display. |
| `supportWhatsapp` | string | Support WhatsApp number to display. |
| `offlineSelfInstructions` | string | Numbered instructions for buying for your own number. |
| `offlineOtherInstructions` | string | Numbered instructions for buying for another number. |
| `supportBanner` | string | Optional short banner line. |
| `workingHours` | string | Human working-hours text. |

These are display and offline-instruction values only. They are not payment credentials, and
initiating a payment is not part of this API.

If both `tillNumber` and `paybillNumber` are empty the operator is warned at publish time,
and the app must disable offline purchase with a clear explanation rather than showing
partial instructions.

### 6.8 `appConfig` (object)

| Field | Type | Meaning |
|---|---|---|
| `maintenanceMode` | bool | Show the maintenance state instead of the normal flow. |
| `maintenanceMessage` | string | Message to show in maintenance mode. |
| `syncIntervalMinutes` | int | Suggested background sync interval, clamped server-side to 60–1440. |
| `generalSupportMessage` | string | Optional general support line. |

### 6.9 `featureFlags` (object)

A plain `flagKey -> bool` map, sorted by key, so the app can read a flag a newer server
introduced without a client release. An empty map is published as `{}`. Treat an unknown or
missing flag as `false`.

### 6.10 `version` (object)

The app update rule for the active version row.

| Field | Type | Meaning |
|---|---|---|
| `latestVersionCode` | int | Newest available build. |
| `latestVersionName` | string | Semantic version, e.g. `1.0.2`. |
| `minSupportedVersionCode` | int | Oldest build still supported. |
| `mandatory` | bool | The update is forced. |
| `updateSource` | string | `github` or `play`. |
| `playStoreUrl` | string | Play destination, may be empty. |
| `apkUrl` | string | Direct APK destination, may be empty. |
| `apkSha256` | string | Checksum of the direct APK, may be empty. Verify it before installing. |
| `rolloutPercent` | int | 0–100 staged rollout share. |
| `releaseNotes` | string | What changed, for display. |

`minSupportedVersionCode` is never higher than `latestVersionCode`, and a `mandatory` update
always has at least one destination — both are enforced at publish time.

---

## 7. Resources, versions and checksums

A **release** is identified by `configVersion` and increases by one on every publish. On top
of that, each resource carries its own version which moves **only when that resource's
published bytes actually change**. A device holding `offers` v18 sees `offers` still at v18
in the manifest and downloads nothing.

Versions are derived, never hand-maintained: at publish time each section is canonically
encoded, hashed, and compared with the previous release's hash. Same hash keeps the old
version number; a different hash takes the new config version.

**How a resource checksum is computed** — so a client can verify one:

1. Take the section value exactly as published (the `data` field, or the section inside the
   full snapshot).
2. Wrap it as `{"v": <section>}` so a bare list and a bare map hash through the same path.
3. Encode canonically: object keys sorted recursively, array order preserved, `/` not
   escaped, unicode not escaped, no whitespace.
4. `checksum = SHA-256(bytes)`, lowercase hex.

The whole-snapshot `checksum` is `SHA-256` of the canonical bytes of the entire snapshot —
which are exactly the bytes `api/app-data` returns, so there it is a byte-for-byte check
with no re-encoding.

When a release is signed, `signature` is base64 `RSA-SHA256` over those same canonical
snapshot bytes, verifiable with the public key embedded in the app. The private key never
leaves the server and no shared secret is placed in the APK. A snapshot that fails
verification must be discarded and the previous data kept.

`count` is what an operator would recognise: the number of items in a list resource, `1` for
a singleton such as `support`, and `0` when the section is absent.

---

## 8. ETag and If-None-Match

Every data endpoint returns an `ETag` and accepts `If-None-Match`.

| Endpoint | ETag is |
|---|---|
| `api/app-data` | the snapshot checksum |
| `api/sync/manifest` | the snapshot checksum |
| `api/sync/resource/{key}` | that **resource's** checksum |
| `api/sync/resources` | a hash of the release plus the exact requested key set |

Flow:

1. Store the `ETag` string exactly as received, quotes included, next to the data it belongs
   to.
2. Send it back verbatim: `If-None-Match: "8d55c3a1..."`.
3. `304 Not Modified` means what you hold is current. Do not touch local storage.
4. `200` means new content; the response carries a new `ETag` to store after you have
   applied the data successfully.

`api/sync/manifest`, `api/sync/resource` and `api/sync/resources` also accept a list of tags
(`"a", "b"`), weak tags (`W/"a"`) and `*`. `api/app-data` compares a single exact tag, as it
always has.

Because a resource ETag is the resource checksum, a release that only changed `offers`
returns `304` for `smsRules`, `support` and everything else, even though `configVersion`
moved. Bytes only travel when they changed.

---

## 9. Recommended client sync algorithm

Offline-first, cheapest call first, atomic apply, and never destructive.

1. **Show cached data immediately.** Never block the UI on a sync.
2. **Ask the manifest.**
   `GET api/sync/manifest?since=<local configVersion>` with `If-None-Match: <stored manifest ETag>`.
   - `304` or `upToDate: true`: done. Record the attempt time and stop.
   - Any error status: stop, keep everything, retry later with exponential backoff.
3. **Check `schemaVersion`.** If it is greater than the app understands, stop and keep the
   current data. Prompt for an app update if `version.mandatory` was already known.
4. **Compare per resource.** For every entry in `resources`, compare `version` (and, if you
   are being thorough, `checksum`) with what you hold. Build the list of stale keys. If the
   list is empty, stop.
5. **Fetch only what moved.**
   - One stale resource: `GET api/sync/resource/<key>` with its stored `ETag`.
   - Several: `GET api/sync/resources?keys=a,b,c` (20 keys maximum per call).
   - First install, or you want the signature: `GET api/app-data` once and verify the
     signature over the raw bytes.
6. **Verify before you trust.** Recompute each resource checksum as described in
   [section 7](#7-resources-versions-and-checksums) and compare it with the manifest. A
   mismatch means abandon this sync and keep the previous data.
7. **Apply atomically.** Write every fetched resource inside a single local transaction. A
   crash mid-apply must leave the previous, complete configuration in place. Never delete
   first and insert second.
8. **Store the new versions and ETags** only after the transaction commits.
9. **On any failure at any step, keep what you have.** An empty or missing resource is not a
   reason to clear a table. `503 no_published_configuration` means the server has nothing
   yet, not that your data is invalid.
10. **Back off politely.** Respect `Retry-After` on `429`, use exponential backoff on
    network errors, and schedule the normal cycle from `appConfig.syncIntervalMinutes`.

Notes:

- Treat `count` as a display and sanity hint, not a validation rule. If `count` disagrees
  with what you parsed, prefer the parsed data and log it.
- Ignore fields and sections you do not recognise; do not fail a sync because of them.
- Do not download images eagerly. `imageUrl` and `thumbUrl` are relative paths to fetch
  lazily when a slide is about to be shown.

---

## 10. Optional anonymous telemetry

The server can record how synchronisation is going, and it collects nothing about a person
to do it.

- Telemetry is written **only** when the request carries an `X-Install-Id` header.
- The value must be an anonymous, app-generated opaque id: at most 64 characters of
  `A-Za-z0-9._-`. Anything else is ignored silently — the sync still succeeds.
- It must never be a phone number, an advertising id, an account id or anything derived from
  one. A random UUID generated on first launch and stored locally is the intended value.
- Two optional companions may accompany it: `X-App-Version-Code` (digits) and
  `X-Config-Version` (digits, the version the device holds).
- What is recorded: the anonymous id, the app version code, the config version, which
  endpoint was called, the resulting status, which resource was asked for, and the time.
- What is never recorded: IP addresses, phone numbers, user agents, names, payments, or any
  request body — the API has no request body.
- A telemetry write failure never affects the response. The data is served regardless.

Sending no `X-Install-Id` at all is a fully supported mode of operation.

---

## 11. Adding a new resource

The protocol does not change when the product grows. A new resource is:

1. A builder that adds a section to the published snapshot.
2. One line in the server's resource registry (`ResourceVersions::RESOURCES`) naming the
   section and whether it is a list.

From then on it appears automatically in `api/health`, in the manifest's `resources` map
with its own version, checksum, count and URL, at `api/sync/resource/<newKey>`, and as a
selectable key in `api/sync/resources`. No client change is required to keep syncing, and
clients that do not know the key simply ignore it.

Older releases are unaffected: a resource that did not exist when a release was published is
absent from that release's manifest, and `api/sync/resource/<newKey>` answers `404
unknown_resource` with the list this release does support.
