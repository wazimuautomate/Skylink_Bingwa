# Skylink Bingwa — Production Intelligence

Architecture reference for the work delivered by `SKYLINK_BINGWA_APP_PROMPT.md`
(Features 1–10) on branch `feature/production-intelligence`.

This is the "how it actually works" companion to `Plan.md` (what the product is)
and `design.md` (how it looks). Read it before changing notifications, SMS
detection, synchronisation, personalisation or billboard media.

**Nothing here has been through a physical-phone acceptance test yet.** CI
compiles it and runs the unit suite; that is not the same as verified behaviour.

---

## 1. Guiding shape

Five engines were added. They share four properties, and every future change
should preserve all four:

1. **Offline-first.** A failed, null or empty server response KEEPS what is
   already cached. Nothing is ever cleared because the network was unavailable.
   Old content beats no content.
2. **Independently degradable.** Each engine is constructed with nullable
   dependencies and no-ops when unconfigured. A build with no `PAYMENTS_BASE_URL`
   behaves exactly like the app did before this work.
3. **Own storage.** Each engine owns a separate DataStore file. None of them can
   reach `mybingwa_local`, where purchases, favourites and the active order live.
4. **Pure core, thin Android shell.** The judgement (policy, ranking, planning,
   parsing) is Android-free and unit-tested. The Android class is a wrapper.

---

## 2. New local storage

Seven DataStore files, all distinct. The separation is a safety property, not
tidiness: a sync bug cannot corrupt purchase history because the sync engine
has no handle to that file.

| File | Owner | Holds |
|---|---|---|
| `mybingwa_local` | `data/persistence/LocalStore` | **pre-existing** — profile, favourites, Activity, notifications, active order, synced offers/promotions |
| `mybingwa_notification_state` | `NotificationStateStore` | last-posted time per category, last template used, per-day counts, recent content hashes |
| `mybingwa_notification_templates` | `NotificationTemplateStore` | server-published notification wording |
| `mybingwa_remote_notifications` | `RemoteNotificationStore` | admin-published message queue |
| `mybingwa_sms_rules` | `SmsRuleStore` | server-taught SMS detection rules |
| `mybingwa_personalization` | `PersonalizationStore` | learned behaviour profile (**never uploaded**) |
| `mybingwa_sync_meta` | `SyncMetadataStore` | per-resource version/checksum, last attempt, last publish version |

All are Moshi-reflection JSON under a single key, every field defaulted, so an
older snapshot always deserialises. A read failure degrades to empty/null rather
than throwing.

---

## 3. Notification workflow (Feature 1)

```
caller ──> NotificationEngine.notify(category, personalization)
             │
             ├─ NotificationStateStore.load()
             ├─ NotificationPolicy.shouldPost()      ── suppressed? stop, return false
             ├─ NotificationTemplateProvider.current()   (server set if newer, else seed)
             ├─ NotificationComposer.compose()        ── weighted, seeded, no repeat
             ├─ AppNotifier.postEngine()
             └─ NotificationPolicy.record() ──> save
```

**Templates are data.** 54 seed templates, 3+ per category across 18 categories.
Selection is weighted-random seeded by the timestamp, and excludes the previously
used template for that category, so wording never repeats back to back.

**Placeholders:** `{name}` `{greeting}` `{bundle}` `{amount}` `{balance}`
`{recipient}` `{days}` `{category}`. Unknown tokens are stripped, and punctuation
is tidied afterwards so a blank name reads "You're almost out of data" rather
than "Hi , you're...".

**Anti-spam.** Per-category cooldowns (OFFLINE/ONLINE 6h, greetings 24h,
low-balance 3–4h, promotions 24h, inactivity 48h). Quiet hours 22:00–06:59
Nairobi. A cap of 6 non-transactional notifications per Nairobi day.
Content-hash de-duplication. Transactional categories (payment, bundle received,
gift) bypass quiet hours and the cap because they are a direct consequence of
something that just happened.

### Honesty — enforced structurally, not by convention

`NotificationCategory.isBalanceDriven` marks the five categories that can only be
raised by a real Safaricom balance SMS. `NotificationComposer` **filters out any
template containing `{balance}`** for every other category. So the app cannot
state a balance it has no carrier evidence for, even if someone later adds a
careless template. Delivery language is never used for Skylink Bingwa's own payments;
"bundle received" copy is attributed to Safaricom and only fires from an SMS.

If you add templates, the banned strings are asserted by
`DefaultNotificationTemplatesTest`.

---

## 4. SMS rule processing (Feature 2)

The old parser hardcoded a handful of Safaricom formats, so every wording change
by Safaricom needed an app release. Now:

```
SMS_RECEIVED broadcast
   └─ SmsDeliveryReceiver
        ├─ SmsRuleProvider.cachedOrSeed()        (synchronous fast path)
        │    └─ goAsync() + 3s timeout only on a cold cache
        ├─ DynamicSmsParser.parse(sender, body, ruleSet)
        │    ├─ enabled rules ordered by priority, first match wins
        │    ├─ REGEX | KEYWORDS | TEMPLATE; invalid regex is skipped, never thrown
        │    └─ generic extractors: MB/GB, minutes, SMS count, Sh price,
        │       bundle type, both expiry formats
        └─ SmsSignalBus.emit(EventDetected + legacy signal)
```

A rule is `(id, name, senderId, pattern, matchType, eventTypes[], priority,
enabled, description)` — pure data. **Adding a new sender ID or wording is a
server change, not an app release.** `SmsRulesTest` proves this with a rule using
a brand-new sender and Swahili wording, recognised with no code change.

Sender IDs (`Safaricom`, `SAF_Balance`, `SAF_OfaMOTO`) appear only in the seed
data, never in a code branch. Unknown messages are ignored silently — no crash,
no notification.

`SmsSignal` is sealed with three shapes. A server-taught rule does **not** add a
shape: new formats arrive as `SmsEventType` data inside `EventDetected`, and an
unrecognised event name maps to `UNKNOWN`. The legacy `DeliveryDetected` /
`LowBalanceDetected` signals are still emitted alongside for the existing
repository reconciliation.

---

## 5. Sync workflow (Feature 4)

```
trigger ──> SyncOrchestrator.sync(trigger)
              ├─ Mutex.tryLock  ── already running? return "skipped"
              ├─ manifestSource.fetch()            (null = unavailable)
              ├─ SyncPlanner.plan(remote, local, trigger, now, lastAttempts, lastPublish)
              └─ for each planned resource: runCatching { targets.syncX() }
                   success -> advance that resource's local version
                   failure -> record failed, KEEP cache, retry next run
```

**Incremental.** A resource is downloaded only when its `version` **and**
`checksum` differ from the local copy.

> **Do not add `updatedAt` to that comparison.** The server stamps every resource
> with the same publish timestamp, so including it marks all six resources
> changed after *any* publish and re-downloads the whole catalogue — defeating
> the feature. `SyncPlannerTest` has a regression test for exactly this.

**Version is content-derived** (`crc32` of the snapshot section), not the publish
revision, for the same reason. The publish revision is exposed separately as
`publishVersion` and drives force sync.

**Triggers and throttles**

| Trigger | Fired from | Throttle |
|---|---|---|
| `APP_START` | `MainActivity` first composition | 5 min |
| `CONNECTIVITY_RESTORED` | offline→online edge | 2 min |
| `APP_RESUME` | *not yet wired* — see §10 | 15 min |
| `PERIODIC` | `CatalogueSyncWorker`, 6-hourly | none |
| `MANUAL_REFRESH` | pull-to-refresh (*not yet wired* — see §10) | none |
| `FORCE_PUBLISH` | `ForceSyncWatcher` | none |

**Force sync.** While foreground and online, `ForceSyncWatcher` fetches only the
manifest (a few hundred bytes) every 90s. When `publishVersion` moves, the admin
pressed Publish and a `FORCE_PUBLISH` sync runs immediately. This is the path
that gets a corrected Paybill or Till to every online customer with no store
update, reinstall, manual refresh or cache clear. It is hosted in a
`LaunchedEffect`, so backgrounding the app cancels it — it never polls in the
background.

**No manifest endpoint?** `SyncPlanner` plans everything (subject to throttle).
An older backend keeps working; it just syncs less efficiently.

**Conflict rule.** The server owns configuration. The device owns behaviour.
Purchases, favourites, recipients and the behaviour profile are never touched by
a sync.

### Server endpoints

Four new app-key-guarded files in `server/mybingwa-api/`, each mirroring
`get_billboards.php` and returning a valid **empty** payload rather than an error
when its snapshot section is absent:

| Endpoint | Serves | Snapshot section |
|---|---|---|
| `get_sync_manifest.php` | fingerprints + `publishVersion` | all |
| `get_sms_rules.php` | SMS detection rules | `smsRules` |
| `get_notification_templates.php` | notification wording | `notifications[].variations[]` |
| `get_app_notifications.php` | admin-published messages | `notifications[]` |

`get_sync_manifest.php` is polled every 90s by every online client, so it reads
one indexed row (`mb_configuration_releases` has `UNIQUE KEY uniq_version`) and
caches per-release fingerprints in a temp file. It never decodes the snapshot on
the normal path and can only return 200 or a deliberate 503.

---

## 6. Personalisation (Feature 5)

```
PersonalizationEngine.buildProfile(purchases, favouriteIds, recentRecipients, now, offers)
      -> BehaviourProfile   -> OfferRanker.rank(...)      -> Home ordering + badges
                            -> HabitReminderPolicy        -> notification decision
                            -> suggestedPayerNumber()     -> checkout prefill
```

Learns most-purchased offer/amount, favourite category, preferred validity,
favourite hourly/daily bundle, buying hour and time band, frequency band,
preferred M-Pesa payer number and top recipients. Recent purchases are weighted
more heavily (30-day half-life) so the profile tracks changing habits. Only
`RECEIVED` and `WAITING_VERIFY` records are learned from.

**Badges:** "Buy again", "Your usual bundle", "Bought yesterday", "Favourite".
Never "Recommended". At most one badge per offer, and only a couple per screen.

**Regression guard.** `profile.isEmpty()` returns the *same* `HomeSections`
instance with no badges, so a fresh install is byte-identical to the previous
release. This is what let ~130 existing tests pass unmodified.

**Privacy.** Everything stays in `mybingwa_personalization`. No network call
exists anywhere in `core/personalization`. Clearing app data erases it. The
server never learns user habits.

---

## 7. Billboard media (Feature 6)

`Promotion` gained `mediaUrl`, `mediaType` (NONE/IMAGE/GIF), `clickAction`
(NONE/OFFER/CATEGORY/EXTERNAL_LINK/INTERNAL_ROUTE), `clickTarget`, `mediaVersion`
and `mediaAltText` — all defaulted, all read through non-throwing parsers, so a
v1.0.2 snapshot still deserialises and an unknown value degrades instead of
crashing.

`BillboardImageLoader` is a process-wide Coil loader with GIF decoders
(`ImageDecoderDecoder` on API 28+, else `GifDecoder`) and a bounded 32 MB disk
cache with `respectCacheHeaders(false)`. **The disk cache is what makes a
once-synced slide render offline.** `mediaVersion` is folded into the memory and
disk cache keys, so republished artwork refetches exactly once.

Failed or missing artwork falls back to the coloured text slide — never a blank
space, never an error dialog. The composable never launches an `Intent`; it calls
`onPromotionAction`, and `MainActivity` routes it (re-validating that an
EXTERNAL_LINK target is http/https before handing control to another app).

**Bug fixed here:** start/end timestamps previously parsed only UTC `...Z`, so an
admin publishing a Nairobi-local timestamp could have a slide hidden for up to
three hours. Local forms are now accepted in separate branches, because
`SimpleDateFormat.parse` silently ignores trailing text.

---

## 8. Connectivity (Feature 3)

`registerDefaultNetworkCallback` plus an INTERNET-capability callback, across
five overrides, emitting immediately at subscription.

`NET_CAPABILITY_VALIDATED` is the real-internet signal — **"a network exists" is
not "internet works"**. A captive portal or half-associated hotspot now correctly
reads as offline, which is the point: the app shows cached content and offline
payment instructions instead of firing an STK push into a dead network.

**Asymmetric debounce.** Offline is emitted instantly. Online settles for 400ms,
because reconnection is noisy and announcing "online" a beat early sends a
payment retry into a half-open network. Since `_isOffline` defaults to `false`
and `NONE` bypasses the debounce, a cold start neither flashes an offline banner
nor delays a genuine offline detection.

`NetworkStateDeriver` holds the classification rules as pure, tested logic.

---

## 9. Server configuration required

1. **Upload** the four new PHP files to the cPanel API directory. No schema
   change, no `.htaccess` change.
2. **Admin console** already publishes every section they read. Two optional
   improvements, both degrading gracefully today:
   - `sms_rules.description` exists as a column but is not published by
     `PublishingService::buildSmsRules()`; the endpoint already reads it.
   - Notification variations have no `weight` column, so every variation is
     published with `weight = 1` (uniform selection).
3. `notification_campaigns.priority` is a word (`high`/`normal`/`low`) and is
   mapped to `2`/`1`/`0`.

---

## 10. Known gaps

Honest list of what is **not** done, so nobody assumes otherwise:

- **No physical-phone acceptance test yet.** CI compiles and unit-tests; the
  full testing matrix in `SKYLINK_BINGWA_APP_PROMPT.md` is unverified on a device.
- **`APP_RESUME` and `MANUAL_REFRESH` triggers are not wired.** The planner
  supports them; nothing calls them. `APP_RESUME` wants a `ProcessLifecycleOwner`
  observer, `MANUAL_REFRESH` a pull-to-refresh gesture.
- **Admin-published notifications are stored but never displayed.**
  `RemoteNotificationSelector.due()` and `NotificationEngine.notifyRaw()` both
  exist; no caller joins them. This is the smallest remaining piece.
- **`HabitReminderPolicy` is never evaluated.** It needs a periodic worker to
  call it and post `HABIT_REMINDER` / `INACTIVITY`.
- **Billboard personalisation seam is unused.** `BillboardSelection` accepts a
  category-affinity hint; `CatalogueLogic.selectPromotions` still ignores it.
- **Expedited sync work is API 31+ only.** Below that a normal one-off request is
  used, because honouring expedited work would require a visible foreground
  notification for a silent sync.
- **PHP was never executed.** No local PHP; the four endpoints are unverified at
  runtime. Highest-uncertainty spots are `sys_get_temp_dir()` writability and
  `DateTime`/`Africa/Nairobi` availability, both wrapped defensively.
