# Changelog

All notable changes to the Skylink Bingwa customer app are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Sections used: `Added`, `Changed`, `Fixed`, `Removed`, `Security`, `Internal`
(`Internal` = repository change with no customer-visible effect).

## [Unreleased]

## [1.0.18] - 2026-08-28

### Added

- **Force-refresh button** on Home, Offers and Help — to the right of the notification
  bell on Home, top-right on Offers and Help. Pulls the latest offers, app config and
  billboards from the server immediately (`SyncTrigger.MANUAL_REFRESH`, no throttle)
  instead of waiting for the next scheduled check.
- **Settings → Danger zone.** "Clear local data" is no longer sitting open on the
  Settings screen: it is now behind a collapsed "Danger zone" disclosure, and tapping
  it now asks for confirmation twice — a first dialog, then a second, starker one —
  before anything is deleted.
- **Refresh feedback.** Every refresh button (Home, Offers, Help, Refer & Earn) now
  reports through a Snackbar — "Updated", "Already up to date", or "Couldn't refresh"
  — instead of just spinning and going quiet.
- **Referral account status.** The Earn screen now shows a clear banner naming the
  admin's own reason when an account is banned or blocked, sourced from a new
  `accountStatusReason` field on `referral_summary.php`.

### Fixed

- **Refer & Earn showing a blank code.** The Earn screen now shows the customer's own
  code instantly from the last successful fetch, before the network call for this
  visit even completes — previously nothing was shown until that call returned. If the
  call fails, the screen now says so ("Could not load your code — check your
  connection and tap refresh") instead of silently looking the same as "no code yet".
- **OTP requests had no minimum spacing.** A double-tapped "resend" could burn most of
  the hourly request budget in seconds. Added a one-minute minimum gap between
  requests for the same number, on top of the existing 3-per-hour/10-per-day caps.
  A nightly job also now purges OTP challenge rows a day past their expiry.

## [1.0.17] - 2026-08-28

### Added

- **Refer & Earn — referral codes, commission and self-service M-Pesa withdrawal.**
  Reachable from a gift icon in the top-right of the home screen (the bottom bar is
  already full at five destinations, and this is a reward surface rather than a
  shopping one). Every customer gets a server-minted code shaped `SK` + three digits
  + a letter, e.g. `SK391R`, with tap-to-copy and a share sheet. Onboarding gains an
  optional referral-code field that validates live and confirms the referrer's first
  name; a wrong or unreachable code never blocks onboarding.
- **Commission engine.** The referrer earns Ksh 10 when someone joins with their code
  and a per-offer percentage on everything that person buys. Accrual hooks the single
  observation of the `PAYMENT_REQUESTED → PAYMENT_CONFIRMED` transition in
  `callback.php`, inheriting the exactly-once guarantee that code already provided,
  with a second independent guard from the ledger's unique idempotency key.
- **Append-only commission ledger.** Money is signed integer cents; balances are a
  projection of `SUM(amount_cents)`, never a mutable column. The cached balance is
  written in the same transaction as its ledger row and a nightly job asserts the two
  agree — reporting drift rather than silently "fixing" it, which would destroy the
  evidence.
- **M-Pesa B2C payouts** with the full asynchronous state machine: our own
  `OriginatorConversationID` persisted before the outbound call, a distinct `UNKNOWN`
  state that is never auto-refunded and never auto-retried, and a reconciler that
  resolves it only through Daraja's `TransactionStatus`.
- **Lazy phone verification.** A one-time SMS code, asked for the first time someone
  withdraws, issues a bearer token; the payout destination is the verified number held
  on the server and can never be supplied by the caller. Changing it re-verifies and
  freezes withdrawals for 48 hours.
- **Anti-farming controls.** One referral redemption per handset for life (keyed on a
  hashed device id, which survives the uninstall/reinstall/new-SIM loop), a cap on
  numbers per handset, per-referrer velocity limits, and a signup bonus that does not
  become withdrawable until the referred person actually buys something.
- **Admin: Referrals & commissions.** Overview with outstanding liability and
  unresolved-payout alerts, referrer list and per-referrer ledger, withdrawals queue
  with `UNKNOWN` sorted first, manual adjustments (as ledger entries, never balance
  edits), full programme settings and a payouts kill switch. Offers gain
  `commission_bps` and `margin_bps`, and the form refuses a commission above margin.
- **Notification outbox.** SMS and push are queued and drained by cron with
  exponential backoff, so a slow provider can never stall the Daraja callback.

### Removed

- **The admin's scheduled-notifications module** — editor, campaigns, wording
  variations, catalogue tables, delivery log, publish section, and the two public
  endpoints that served it (`get_app_notifications.php`,
  `get_notification_templates.php`). Instant Push does the same job immediately and
  without a publish cycle; keeping both meant two places to write a message and two
  ways for it to be wrong. The app falls back to the notification wording compiled
  into the APK — already its behaviour on any install with no server configured — and
  Instant Push is unaffected.

### Security

- Endpoints that move money require a bearer token in addition to `X-App-Key`.
  `X-App-Key` is a constant compiled into the APK and extractable by anyone who
  decompiles it; it was sufficient while the worst a forged request could do was push
  an STK prompt to the attacker's own phone, and is not sufficient now that a payout
  endpoint exists.
- OTP codes are stored only as SHA-256, compared with `hash_equals`, single-use,
  capped at five attempts, and rate limited per number and per source IP. Bearer
  tokens are likewise stored only as hashes.

### Internal

- Commission rates default to **0%** and automatic payouts default to **off**, so the
  feature cannot move money until the owner has recorded real per-offer margins and
  completed B2C Go-Live.


## [1.0.12] - 2026-08-24

### Fixed

- **Admin push notifications could never be sent.** Every submit on the dashboard's
  Instant Push page returned "Something went wrong. Please try again." `PushController`
  had been written against methods that do not exist in this codebase, so it raised a
  fatal `Error` before Firebase was ever contacted, and `index.php`'s catch-all rendered
  the generic 500 page — hiding the real cause. Corrected to the actual core APIs:
  `Csrf::check(Request)` (the form also posted `csrf_token` where `Csrf` reads `_csrf`),
  `Validator::make()->validate([...])` with the `maxlen` rule and `firstErrors()`,
  `Audit::log(array)`, and `Database::fetch()`/`run()` in place of the non-existent
  `fetchOne()`/`query()`.
- **Push notifications never appeared on a backgrounded phone.** The server sent
  `android.notification.channel_id = "news_channel"`, but the app's channel is
  `NotificationChannels.NEWS` = `"news"`. Android 8+ silently drops a notification posted
  to a channel that does not exist. Messages are now data-only, so the app's own
  `onMessageReceived` is the single delivery path in every app state — it posts through
  `AppNotifier` on the correct channel AND records the message in the in-app
  notification centre, which the SDK-drawn notification never did.
- **Migration `021_fcm_push.sql` could never apply.** It carried no `-- @@` statement
  separators (so its three statements ran as one `exec`) and its `ALTER TABLE`/`CREATE
  INDEX` were not idempotent, while the customer API adds the same `fcm_token` column
  itself. The migration threw, was never recorded, and `Migrator::run()` aborts on first
  error — blocking every later migration too. Each schema change is now guarded through
  `information_schema` and applied via `PREPARE`.
- The dashboard reported "0 active FCM tokens" and an empty broadcast history whether or
  not the schema existed, because both failures were swallowed. Both now say what is
  wrong and point at the pending migrations.
- A topic broadcast reported success while reaching nobody: FCM returns HTTP 200 for a
  topic with zero subscribers and the app never subscribed to one. The app now
  subscribes to `all_users` at startup, and topic delivery is counted separately from
  per-device delivery so the numbers on the dashboard stay honest.

### Added

- Failed sends now surface Firebase's own reason (`SENDER_ID_MISMATCH`, a rejected
  service account, an unreachable host) instead of a bare failure.
- Device tokens FCM reports as `UNREGISTERED`/`NOT_FOUND` are cleared automatically, so
  an uninstalled handset stops costing a request on every broadcast.

### Changed

- **The app is called "Skylink Bingwa" again**, reverting the v1.0.11 change back to the
  name established in v1.0.9. The launcher label, top app bar, onboarding, Settings,
  Help, Activity, the permission screen, notification channels and update messages all
  speak the Skylink Bingwa name; the debug variant is "Skylink Bingwa Dev".
- **The v1.0.11 logo was reverted** to the artwork that shipped in v1.0.10, across every
  launcher, round, adaptive, monochrome, splash, onboarding and status-bar asset, plus
  the logo kit.
- The name is now carried through the repository structure, not just user-facing strings:
  the Gradle module moved from `my-bingwa/` to `skylink-bingwa/`, `MyBingwaApplication`,
  `MyBingwaFirebaseService`, `MyBingwaTopAppBar` and `MyBingwaBottomNav` were renamed to
  their `SkylinkBingwa*` equivalents, `ic_stat_my_bingwa` became
  `ic_stat_skylink_bingwa`, the logo kit and its assets were renamed, and the release
  folders became `Skylink-Bingwa-v*`. CI workflows and docs follow.

### Internal

- `versionCode` 12 → 13 and `versionName` 1.0.11 → 1.0.12.
- Production identities were deliberately NOT renamed, because changing them breaks a
  live system rather than rebranding it: the `com.bingwasokoni` application ID (Play
  update continuity), the `mybingwa.blazetechscope.com` API host, the `server/mybingwa-api`
  cPanel directory, the `MYBINGWA_ADMIN_CONFIG` environment variable, the Firebase
  project `my-bingwa` and its service-account key, and the `all_users` topic.
- On-device storage keys were likewise kept: the DataStore/SharedPreferences names
  (`mybingwa_local`, `mybingwa_notification_state`, `mybingwa_personalization`,
  `mybingwa_sync_meta` and the rest) and the WorkManager unique names. Renaming them
  would have made every existing install look brand new — losing the customer's profile,
  favourites, activity history and pending order on upgrade.

## [1.0.11] - 2026-08-23

### Changed

- Restored the customer-facing app name to **My Bingwa** across the launcher,
  onboarding, settings, help, notifications, and update messages, while keeping
  the production application ID `com.bingwasokoni` unchanged.
- Replaced the previous brand artwork with the approved `new-logo.png` in every
  Android launcher, adaptive, splash, onboarding, notification, and Play Store
  logo asset.

### Internal

- `versionCode` 11 → 12 and `versionName` 1.0.10 → 1.0.11.
- Made the logo asset generator Windows-safe and reproducible from the approved
  source artwork.

## [1.0.10] - 2026-08-22

### Added

- **Instant Admin Push Notifications via Firebase FCM HTTP v1**:
  - Integrated Firebase Cloud Messaging SDK (`firebase-messaging-ktx`).
  - Added Android `MyBingwaFirebaseService` to receive push payloads in foreground and background, post local system notifications, and sync into the in-app notification center.
  - Implemented pure PHP `FcmService` supporting Google Service Account OAuth2 JWT authorization with OpenSSL (`RS256`).
  - Added Admin Dashboard "Instant Push" module at `/push` to compose and broadcast push notifications to registered customers with audit history.
  - Created migration `021_fcm_push.sql` adding `fcm_token` column to `mb_customers` and `mb_push_broadcasts` log table.
- **Google Play In-App Updates**:
  - Integrated Google Play In-App Update API (`com.google.android.play:app-update-ktx:2.1.0`) on the `play` flavor with `AppUpdateType.IMMEDIATE` ensuring non-dismissible prompt for critical updates.
  - Maintained dev updater strictly for debug/direct builds.

### Changed

- **Streamlined 3-Step Onboarding Flow**:
  - Combined intro and benefits into a single Welcome card (`1 of 3`), followed by Details Setup (`2 of 3`) and Notification Permission (`3 of 3`).
  - Added textual step counter `"$step of $total"` alongside the animated progress bar.
- **Dynamic Catalogue Notifications**:
  - Offline and online morning/evening engagement notifications now dynamically bind real active catalogue prices and allowances from cached storage.
- **In-App Review Pop-up Policy**:
  - Fires after **1** successful online purchase (`MIN_SUCCESSFUL_PURCHASES = 1`) with a **3-second delay** after checkout sheet dismissal and a **30-day** prompt interval.

### Removed

- **1.25GB Offer Discontinued**: Removed `1.25GB for KSh 55` (`data_4`) across all repository seeds, database scripts, and notification copy.

### Internal

- `versionCode` 10 → 11, `versionName` 1.0.9 → 1.0.10.

## [1.0.9] - 2026-08-11

### Changed

- **The app is now called "Skylink Bingwa".** Name only: the launcher label
  (`app_name` / the `appLabel` manifest placeholder, and the debug variant's
  "Skylink Bingwa Dev"), the top app bar title, and every customer-facing string
  that spoke the product's name — onboarding, the permission-required screen,
  Settings, Help, Activity's empty state, and the notification channels/templates.
  Nothing else changed: same `applicationId` (`com.bingwasokoni`), same signing
  identity, same logo, same behaviour. `versionCode` 10 / `versionName` 1.0.9 for
  the Play upload.

## [1.0.7] - 2026-08-10

### Removed

- **The SMS-reading permission and feature, entirely, on both channels.** Google
  Play declined the app in production for `RECEIVE_SMS`. Rather than stripping it
  only from the Play flavour, the whole on-device "read Safaricom bundle/balance
  messages" feature is gone: the manifest permission, the telephony `<uses-feature>`
  declaration, `SmsDeliveryReceiver`, the on-device rule engine/parser/store
  (`core/sms/*`), the legacy message-template classes, and the SMS-rule remote sync
  source. Onboarding's SMS permission step is gone (it now ends at Notifications,
  still required); the blocking "a permission is switched off" screen no longer
  mentions SMS. The server's entire SMS Rules admin module (controller, matching
  engine, the three `/sms-rules/*` views, the `SMS_RULES` sync resource, the legacy
  `templates` snapshot section it fed, and the notification campaign's "which phone
  message triggers this" option) is removed, with a new migration
  (`020_drop_sms_rules.sql`) dropping the now-unused tables. The only permission the
  app requests now is notifications.

### Fixed

- **Morning/evening engagement notifications now actually fire.** They were
  implemented in a previous release but never worked: `MyBingwaApplication.onCreate()`
  rescheduled the daily notification job with `ExistingWorkPolicy.REPLACE` on every
  cold start, including the cold start the pending job itself caused — silently
  cancelling the very notification about to fire, on essentially every real-world
  run. Cold-start scheduling now uses `ExistingWorkPolicy.KEEP` (matching the
  sibling periodic catalogue-sync job); only the worker's own tail-of-run reschedule
  still uses `REPLACE`. Also split the morning/evening nudges onto their own
  notification categories instead of sharing the connectivity online/offline
  categories, so an ordinary connectivity blip can no longer consume the daily
  nudge's rate-limit budget.
- **"Buy for myself" now has an editable number.** The confirmation step showed the
  saved profile number as plain, uneditable text; it is now a real editable field
  (mirroring the already-editable "buy for another number" fields), so a wrong
  digit can be fixed without leaving checkout.

### Internal

- `versionCode` 7 → 8, `versionName` 1.0.6 → 1.0.7.

## [1.0.6] - 2026-08-08

### Added

- **Offers carry the hours Safaricom sells them, end to end.** Safaricom restricts
  several bundles to a window of the day, and buying one outside its window fails at the
  carrier *after* the customer has paid. The window is now set in Admin → Offers
  (**Sells from** / **Sells until**, Nairobi wall clock, both blank = sold all day, and a
  window may cross midnight), published in the snapshot as `availableFrom` /
  `availableTo`, and served by `get_offers.php`. **Every** offer card shows it
  ("Available now · 5:00 PM – 11:00 PM", or "Available from 5:00 PM to 11:00 PM" when
  shut); outside the window the Buy button becomes an "Opens 5:00 PM" chip and tapping
  the card explains the window instead of opening checkout. The review step restates it,
  and offline purchase of a shut offer is withheld with the same explanation.
  `stk.php` refuses it server-side (`OFFER_NOT_AVAILABLE_NOW`) *before* the payment row
  is claimed and before Daraja is called, so a refusal costs nothing — no STK prompt, no
  charge, no order to reconcile. Existing offers are unaffected: both columns default to
  NULL, which means "sold all day".
- **A once-a-day bundle now names the number that already had one.** Offer lists show
  "Already bought today for 0712 345 678" instead of a bare "bought today", and typing a
  number that already received the bundle today blocks checkout with an explanation that
  says both what the rule is and what to do — buy it for that number again after
  midnight, or use a different number right now, without leaving the sheet. The rule is
  enforced again in `stk.php` (`ALREADY_BOUGHT_TODAY`), counting confirmed payments plus
  requests started in the last ten minutes so a prompt still on the customer's screen
  cannot be duplicated. **The reset is the Nairobi day boundary itself, not a stored flag
  or a scheduled job**: a purchase at 23:58 stops blocking at 00:00 because the day
  comparison decides, never a written marker. Server-side the offset between the MySQL
  clock and Nairobi is *measured* rather than assumed, so the reset lands on midnight
  whatever timezone the host runs in.
- **Both numbers on the review step are tappable.** Seeing a wrong digit at the moment of
  paying is exactly when it needs fixing; hunting for "Change details" below the fold was
  a step too many.
- **Ask for a Play Store rating, from inside the app.** After a purchase the customer
  actually received, Google's own rating card is shown over the app: they rate, write a
  comment and submit without ever leaving My Bingwa, and it lands on the Play listing.
  Three rules govern it (`core/review/ReviewPolicy.kt`), and each one exists to avoid a
  one-star review: only after a payment that succeeded, never before the second received
  purchase, and at most once every 60 days. It is deliberately **not** immediate — a
  short settle delay lets the M-Pesa SMS, the caller-ID summary and the Safaricom
  confirmation land first, because a card in that pile-up is dismissed unread — and it
  never appears over the open checkout sheet. Google never reports whether the card
  actually displayed, so the attempt is recorded either way rather than re-asking on the
  assumption it did not. There is no "Do you like the app?" pre-question: filtering who
  sees the card is against Play policy. The Play library ships on the Play flavour only;
  the direct APK, where the card cannot work at all, falls back to opening the Play
  listing under the same 60-day rule.
- **A customer register, and a Customers page in the admin.** The app sends the name and
  Safaricom number typed at onboarding to the seller's backend **once** per install
  (`register_user.php`), so the owner knows who their customers are. It is the only
  customer detail that ever leaves the device — purchases, favourites and behaviour stay
  on the phone (CLAUDE.md §10). The call is retried on a later launch if it fails (a
  customer finishing setup on a weak connection is exactly the likely failure), and the
  endpoint is idempotent on the number, so a retry or a reinstall updates that customer
  rather than duplicating them. Admin → **Customers** shows total / new today / new this
  week, searches by name or number, filters by date, removes customers singly or by
  select-all, and exports the current selection to CSV.

### Changed

- **Notifications and Safaricom bundle messages are now required to use the app** (owner
  decision — both are the product, not extras). Onboarding is reordered to
  Welcome → What you gain → **Name & number** → Notifications → Safaricom messages: the
  permission steps come last, immediately after the personal details, and cannot be
  skipped. The "Skip" shortcut and the "Not now" escape are gone; the only alternative
  offered is **Close My Bingwa**. After two refusals Android stops showing its dialog, so
  the button becomes "Open settings and allow" rather than a control that appears to do
  nothing. If a permission is later revoked in Android's own settings, the app shows a
  blocking screen on next foreground with the same two outcomes. The Play flavour is
  unchanged: it strips `RECEIVE_SMS` from the manifest, so that step does not exist there
  and nothing is required that cannot be granted.
- **Settings no longer offers the "Push Notifications" or "Reads Safaricom SMS"
  toggles.** With both permissions required and granted during onboarding, a switch the
  app would immediately override is worse than no switch at all.
- **Google Play is the only update channel for a shipped build.** The `release` build
  type sets `GITHUB_UPDATER_ENABLED` false and a build type always wins over a product
  flavour, so **no** production artifact — direct APK or Play AAB — fetches `update.json`,
  shows "Check for updates", posts the update notification, renders the Home update
  billboard or raises the force-update gate. Only debug builds do. A second in-app update
  channel alongside Play is redundant at best and grounds for rejection at worst. The
  implementation is kept behind the flag rather than deleted, so it can come back if
  distribution ever changes.
- **The in-app GitHub update check is now a debug-build feature only.** Shipped builds
  no longer fetch `update.json`, no longer show the "Check for updates" control, the
  update notification, the Home "update available" billboard or the force-update gate.
  Google Play distributes and updates the production app natively; a Play build offering
  to download and install an APK from GitHub could not work anyway (the `play` flavour
  removes `REQUEST_INSTALL_PACKAGES`) and would breach Play's distribution policy. The
  debug APK keeps the whole flow for development installs.
- **The Play build no longer shows the "Reads Safaricom SMS" setting.** The `play`
  manifest overlay removes `RECEIVE_SMS`, so requesting it at runtime was denied by the
  OS the instant the customer tapped Allow and the toggle snapped straight back off. The
  section is hidden when the permission is not in the build (`SMS_DETECTION_AVAILABLE`);
  the direct APK is unaffected.


### Fixed

- **The Support page no longer starts blank on a fresh install.** The Till, Paybill and
  support numbers defaulted to empty on the reasoning that the owner sets them in the
  admin and the app syncs them — so a customer whose first sync had not landed (weak
  connection, slow first launch) saw an empty Support page and offline instructions that
  refused to show a number to pay, and the numbers appeared, or appeared and vanished,
  purely according to whether a sync had succeeded. Reported by 4 of 20 test users, with
  exactly that inconsistency. The current production numbers are now **bundled in the
  APK** (`SEED_*` in `build.gradle.kts`, overridable per build via Gradle properties or
  env vars). They are a floor, never the truth: the first successful sync replaces them,
  the synced copy is cached and preferred from then on, and the admin remains the only
  place a number is changed. A stale bundled number is corrected by the next sync; a
  blank one could never be corrected offline at all.
- **The same phone number written three ways is now one number in the daily ledger.**
  Numbers were compared by stripping non-digits only, so `0712345678` and `254712345678`
  looked like different lines — which would have let the same once-a-day bundle be bought
  twice in a day for one number. They are now matched on their last nine digits.

- **The STK price is now read from the published catalogue, not a static file.**
  `stk.php` recomputed the amount from the hardcoded `offers.php` map while
  `get_offers.php` served the admin's published snapshot to the app. The two agreed only
  by hand. Editing a price in the admin would have charged the old amount, and the
  callback's amount cross-check would then have held the customer's real payment as an
  unconfirmed mismatch; adding an offer would have failed every purchase of it with
  `UNKNOWN_OFFER`. `offer_price()` now resolves the published snapshot first, then the
  legacy `offers` table, and only falls back to the static map when neither can be read.
  An un-published offer is no longer payable.
- **Offline instructions can no longer show a blank number to pay.** An install that had
  never reached the server has blank Till and Paybill (no seller numbers are baked into
  the app), and the offline sheet rendered a "copy the Till number" button with nothing
  behind it. A blank configuration — or a blank number for the chosen route — now shows
  the "connect to refresh" state instead (CLAUDE.md §7).
- **The admin gateway overlay was dead in production.** `config.php` looked for
  `../admin-v2/cutover/gateway_bridge.php`, a sibling path that only exists in the
  repository checkout; on cPanel the admin lives at `public_html/admin/`, so `@include`
  silently returned false and the Payment-gateway page could never override `party_b`,
  the fulfilment phone or the other routing values. Both layouts are now tried.

### Added
Two workstreams landed in parallel: the Android app (production intelligence) and the
PHP server (production release). They are listed separately because they ship and are
verified independently.

### Android app - production intelligence

#### Added

- **Notification engine** (`core/notifications/engine/`). 54 seed templates, 3+ per
  category across 18 categories, personalised with name, time-of-day greeting, usual
  bundle and recent activity, chosen by weighted deterministic random and never
  repeating back-to-back. Per-category cooldowns, Africa/Nairobi quiet hours
  (transactional categories bypass), a 6/day cap on non-transactional messages, and
  content-hash de-duplication. Server-published wording and admin-published messages
  cache locally and still display offline.
- **Dynamic SMS detection engine** (`core/sms/`). Detection rules are data — sender
  id, pattern (REGEX/KEYWORDS/TEMPLATE), event types, priority — downloaded from the
  server and cached. 12 seed rules cover every observed Safaricom, SAF_Balance and
  SAF_OfaMOTO message. **A new sender id or wording now needs a server change, not an
  app release.** Generic extractors read MB/GB, minutes, SMS counts, Sh price, bundle
  type and both expiry formats.
- **Incremental sync engine** (`data/sync/`, `data/remote/`). Per-resource version +
  checksum, so only what actually changed is downloaded. Force sync polls a
  few-hundred-byte manifest every 90s while foreground, so an admin publish (e.g. a
  replaced Paybill or Till) reaches online customers without a store update, reinstall
  or cache clear.
- **On-device personalisation** (`core/personalization/`). Learns most-purchased
  bundle and amount, favourite category, buying hour and frequency, preferred payer
  number and top recipients, with a 30-day recency half-life. Ranks Home and adds
  "Buy again" / "Your usual bundle" / "Bought yesterday" labels, and pre-fills the
  M-Pesa payer number at checkout. Nothing ever leaves the device.
- **Billboard images and animated GIFs**, with click actions (offer, category,
  internal route, external link), a bounded 32 MB Coil disk cache so a synced slide
  still renders offline, and `mediaVersion` cache-busting.
- **Animated cold-start splash** (`core/ui/BrandSplashOverlay.kt`). Logo scales
  0.72→1 with alpha 0→1 on an overshoot interpolator over 460ms; the app name fades in
  and rises 12dp→0 over 320ms starting at 160ms; after a 900ms hold the logo scales to
  1.08 while the overlay fades out over 280ms and detaches. Honours reduced motion;
  cold start only.
- Four app-key-guarded server endpoints: `get_sync_manifest.php`, `get_sms_rules.php`,
  `get_notification_templates.php`, `get_app_notifications.php`.

#### Changed

- **Instant offline/online switching.** Connectivity is now observed through
  `registerDefaultNetworkCallback` plus an INTERNET-capability callback, using
  `NET_CAPABILITY_VALIDATED` as the real-internet signal — so a captive portal
  correctly reads as offline instead of failing a payment. Offline is reported
  instantly; online settles for 400ms to avoid announcing a half-open network. No app
  restart or manual refresh is needed.
- **Notification and SMS permissions moved into onboarding**, each explaining why
  before the system dialog, with a "Not now" escape. Declining never blocks the app.
  The SMS step hides itself on the Play flavour.
- **GitHub updater is now gated by flavour, not build type**
  (`BuildConfig.GITHUB_UPDATER_ENABLED`): off for `play` (the store updates itself),
  on for `direct` (sideloaded users have no store) and on for all debug builds.
- `BingwaRepository` implements `SyncTargets`; `MyBingwaApplication` implements
  `SyncOrchestratorProvider` and owns the engines.

#### Fixed

- **Billboard scheduling in Nairobi local time.** Start/end timestamps previously
  parsed only UTC `...Z`, so a slide published with a Nairobi-local timestamp could
  stay hidden for up to three hours.

#### Internal

- Seven separate DataStore files, one per engine, so no engine can reach the file
  holding purchases, favourites and the active order.
- `SmsSignal` gained `EventDetected` (matched rule + extracted values) and stays
  `sealed`; the two legacy signals are still emitted for existing reconciliation.
- Architecture reference added at `docs/PRODUCTION_INTELLIGENCE.md`.

### Server - offer performance analytics

#### Added

- **Dashboard rebuilt around what actually sold.** Four cards, each clicking through to the
  page holding the detail: total revenue (today and all time), today's sales split across
  Data / SMS / Minutes / Special, the buy-for-myself vs buy-for-another trend, and the
  catalogue size. Below them, the best performing bundles over 30 days and a 14-day trade row.
- **The payments page became the performance view.** Cards for money in today / all time /
  this view / average sale / attempts completed, sales by category, who the bundle was for,
  and payment outcomes with a success rate - every figure a link that applies that filter.
  Plus a sortable bundle-performance table (sales, revenue, attempts, conversion), a 14-day
  bar row, filters for category, buyer, state, date range, search and amount, and the offer
  name, category and buyer kind on every record. CSV export honours all of it.
- Preview explains the first publish after a server upgrade, instead of leaving the operator
  looking at changes they did not make.

#### Fixed

- **Payment figures could be counted on the wrong day.** `payments.created_at` is written
  with MySQL `NOW()` - the database server's clock, which on shared hosting may be UTC or
  EAT - but the code assumed UTC in one place, local time in another, and every view
  formatted the value as if it were UTC. On an EAT host that displayed payments three hours
  late and pushed early-morning sales into the previous day. The offset is now measured at
  runtime and applied everywhere, with no dependency on MySQL timezone tables.

#### Internal

- Regression tests that publish a snapshot, read it back exactly as the code does, and fail
  the build if the pending-changes list is not empty - the standing guarantee that
  publishing clears Preview instead of looping. A companion test proves a genuinely edited
  price is still detected, so the guard cannot pass by detecting nothing.

### Server - production release

#### Added

- **Admin — SMS rules.** Message recognition is now editable data instead of hardcoded
  patterns. A rule carries a name, sender, pattern type (regular expression, contains,
  starts with, ends with, exact match, keyword combination), event type, priority,
  enabled flag and positive/negative samples. Event types and pattern types live in
  catalogue tables, so a new event is a data change rather than a code change. Ships with
  the ten Safaricom formats in use today as ordinary editable rows.
- **Admin — SMS rule tester.** Paste a message, choose a sender, and see which rule wins,
  the detected events, the extracted variables and a plain-English reason for every
  candidate rule. The tested message is never stored.
- **Admin — notification management.** A notification is now a rule the app evaluates
  locally: category, trigger, optional date range, weekdays, time window and cooldown,
  plus several wording variations the app picks from at random. `{{variables}}` are
  substituted on the device. Categories, triggers and variables are catalogue tables.
- **Admin — billboard media.** PNG, JPEG, WEBP and animated GIF uploads validated by file
  content rather than extension, with generated still thumbnails, metadata stripping,
  explicit display order, an enabled switch and a declared tap target (offer, category,
  internal screen or an https URL).
- **Admin — offer categories and feature flags.** The Home tabs and the app's capability
  switches are published configuration now, editable on the App configuration page.
- **Incremental synchronisation API.** `GET /api/sync/manifest` returns a small document
  with a version and checksum per resource; `GET /api/sync/resource/{key}` and
  `GET /api/sync/resources?keys=…` return only what actually moved. Per-resource ETags
  mean an unchanged resource answers `304` even when the release version rose.
- **Legacy API endpoints for the new resources** — `get_sms_rules.php`,
  `get_app_notifications.php`, `get_notification_templates.php` and
  `get_sync_manifest.php`, so the shipped app can consume them from the API it already
  uses. Each returns a valid empty set rather than an error when nothing is published.
- **Release management.** Every publish records a release identifier, a change count, a
  per-resource version map and a field-level change breakdown. Release history shows what
  each release contained.
- **API documentation** (`docs/server/API.md`) and a **deployment guide**
  (`docs/server/DEPLOYMENT.md`) with a changed-files-only cPanel package builder
  (`server/tools/build-deploy-package.ps1`).
- **Server CI** (`.github/workflows/server-checks.yml`): lints every PHP file, runs the
  logic test suite, and checks migrations and committed secrets on every push.

#### Changed

- **Preview & publish rebuilt as a release screen.** A summary card (live version, draft
  version, pending changes, last published, published by), changes grouped by module in
  collapsed sections, and per-item changed fields shown as `Price  KSh 19 → KSh 25`.
  Publishing now requires an explicit confirmation and accepts optional release notes.
- **Audit log** is filterable by module, actor, action, entity, outcome, date range and
  free text, with the same filters applied to the CSV export, and renders before/after as
  readable field changes with the raw JSON collapsed.
- The published snapshot gained `categories`, `notifications`, `smsRules`, `featureFlags`
  and `resourceVersions`. Every section the shipped app already reads keeps its exact
  shape, so devices in the field are unaffected.

#### Fixed

- **Unchanged items no longer appear in Preview.** Change detection compares field values
  instead of assuming a save meant an edit, so opening an offer and pressing Save without
  editing anything now produces no pending change. Two specific phantom sources are gone:
  the legacy `templates.version` field (which moved on every publish) and an empty
  capture map decoding differently from the working state.
- The legacy `templates` resource version no longer moves on every publish, so devices
  stop re-downloading message patterns that did not change.
- Rolling back to a release published before SMS rules existed now restores its patterns
  into the table that actually feeds publishing, instead of silently changing nothing.
- The Settings "Edit administrator" button worked nowhere: its behaviour sat in an inline
  `<script>` that the Content-Security-Policy (`script-src 'self'`) blocked. Moved into
  `assets/js/app.js` along with the new notification form behaviour.
- A stale unit test asserted a duplicate-price warning that was deliberately removed in
  1.0.2, so the suite could not pass. Replaced with tests for the behaviour that is
  actually intended.

#### Removed

- **Admin — Message templates page.** Superseded by SMS rules, which is strictly more
  capable. Existing templates are imported into the new table by migration, and the
  published `templates` section is now derived from the rules so apps already installed
  keep recognising messages. `/message-templates` redirects to the new page.

#### Internal

- Migrations `013`–`017`: SMS rules and catalogues, notification variations and
  scheduling, per-resource versions and field-level change records, billboard media
  columns, offer categories and feature flags. No column or table is dropped.
- `App\Services\ResourceVersions` derives each resource's version from its published
  bytes, so a version only moves when the content does.
- Test suite split so each module owns `tests/cases/<module>.php`; five new case files.

## [1.0.2] - 2026-07-26

Released to the direct/GitHub channel (`versionCode 3`).

### Added

- **In-app update install (no browser hand-off).** The direct build now downloads the
  signed APK, optionally verifies its SHA-256 against `update.json`, and launches the
  system installer via a `FileProvider` (`REQUEST_INSTALL_PACKAGES`). The in-place update
  keeps the same `applicationId`/signing key, so profile, favourites and Activity survive
  and onboarding never reappears. The `play` flavor omits the permission (Play self-updates).
- **Force update.** A non-dismissible blocking screen when the release is `mandatory` or the
  installed `versionCode` is below `minSupportedVersionCode`, plus an update notification and
  a Home "update available" billboard. A new optional `update.json` field `updateSource`
  (`github` | `play`, default `github`) routes the update action to the in-app installer or
  the Play listing.
- **Admin — Payments delete.** A CSRF-protected, audited action to delete a payment record.
- **Admin — Updates & versions.** Fetch the latest GitHub release into a version rule,
  force-update + `minSupportedVersionCode`, a Play-Store-vs-GitHub update-source selector,
  and a copy-paste `update.json` panel.
- **Admin — real Preview page.** "Preview changes" is now a page that shows the actual
  working snapshot (offers, billboards, templates, support, config, version) instead of
  placeholder data; publishing pushes exactly what it shows.

### Fixed

- **Billboards now appear in the app.** `selectPromotions()` was dropping every synced
  "offer" billboard whose `linkedOfferId` wasn't in the cached catalogue; visibility now
  depends only on the active time window, so admin-published billboards show.
- **Admin — one working sidebar collapse.** Removed the two broken header toggle icons and
  replaced them with a single working collapse toggle (persisted via `mb_nav`).

### Changed

- **Admin — Payments show full identifiers.** Payer, recipient and M-Pesa receipt are shown
  unmasked on the Payments pages and CSV for owner reconciliation (owner-operated console).
- **Admin — simple billboards** drop the CTA-destination, image and image-alt fields.

## [1.0.1] - 2026-07-26

Released to the direct/GitHub channel (`versionCode 2`). Signed
`My-Bingwa-v1.0.1-direct.apk` + Play AAB published on the `v1.0.1` GitHub Release;
`update.json` points at it so devices on 1.0.0 are offered the update.

### Fixed

- **Payments — buy-for-myself now collects to the Till, not a Paybill (critical).**
  The live payment config had `transaction_type = CustomerPayBillOnline` with
  `party_b = 4050595`, so own-number STK pushes were initiated against a Paybill instead
  of the Buy Goods Till that recommends the data. `server/mybingwa-api/config.php` now
  pins the self route to `CustomerBuyGoodsOnline` with `party_b` = the Buy Goods Till
  (fallback `4953696`) and keeps a separate `paybill_shortcode` (`4050595`) so
  buy-for-another still collects on the Paybill with the recipient number as the account
  reference. No `lib.php` change was needed — the routing code was already correct; only
  the config values were wrong.

### Added

- **Server→app billboard (promotions) sync.** The Home billboard now keeps a local,
  offline-safe copy of the admin's published promotions instead of a hardcoded list,
  mirroring the offer-catalogue sync. A new `get_billboards.php` endpoint serves the
  published snapshot's `billboards` verbatim (empty when nothing is published, so the app
  keeps its cache). The app fetches them through a new Retrofit `AndroidRemoteBillboardSource`
  (same `X-App-Key` auth as the catalogue), maps each to a `Promotion` with a
  kind-derived accent (offer→green, announcement→blue, update→navy — never orange), and
  persists them in the installation snapshot. Promotions are replaced only on a non-empty
  response and restored on launch, so a failed/empty sync never blanks the board and
  synced promotions stay available offline and across process death. Remote images are
  still deferred (no image-loading library) — a slide renders as a coloured text slide,
  matching current behaviour.

- **Admin V2 — payment routing on App configuration.** Two new fields, **Payment Till
  number** (the Buy Goods Till that collects buy-for-myself money → STK `party_b`) and
  **Fulfilment number** (the phone that receives the buy-for-another notification SMS →
  `fulfilment_phone`). Stored digits-only in the `mb_settings` key/value table and read
  live by the payment API through the new `server/admin-v2/cutover/gateway_bridge.php`
  overlay. They apply immediately (server-side routing, not part of the app Publish
  snapshot); blank falls back to the `config.php` defaults. The bridge only ever
  overrides `party_b` and `fulfilment_phone` — never auth/paybill shortcodes — and fails
  safe to no-op if the admin is absent or the DB is unreachable.

- **Android background sync (WorkManager).** A periodic `CatalogueSyncWorker`
  (`CoroutineWorker`, `CONNECTED` constraint, 6-hour period, exponential backoff)
  refreshes the seller config and offer catalogue from the published server data and
  persists them on-device. Synced offers are now stored in the installation snapshot
  and restored on launch, so the UI reads offers from **local storage** (not the
  network), previously synced offers stay available **offline** and across process
  death, and a failed/empty/incomplete sync never overwrites good local data — offers
  and the stored catalogue version are only replaced after a complete, validated
  response. Repository construction moved into a new `MyBingwaApplication` so the UI
  and the worker share one process-wide instance.
- **Admin V2 — three-dot (kebab) row menus** on Offers (View, Edit, Duplicate,
  Archive/Restore, Delete), Message templates (View, Deactivate, Edit, Delete) and
  Payments (View + existing actions), replacing the visible per-row button strips.
- **Admin V2 — payment details open in an overlay modal** (not a separate page),
  showing every recorded field unmasked for operator reconciliation.
- **Admin V2 — collapsible desktop sidebar** (icon-only rail, persisted in `mb_nav`)
  alongside the existing mobile off-canvas drawer, and the real My Bingwa logo asset in
  the brand/header (plus favicon) in place of the placeholder "B".
- **Admin V2 — a simple private control panel (`server/admin-v2/`).** A small PHP 8.2
  admin for **two people** (Super Admin + Admin), built beside the legacy
  `server/mybingwa-api` (which is preserved and untouched), coexisting in the same MySQL
  database via the `mb_` prefix and reading the legacy `payments` table read-only. Runs
  on plain cPanel with **no Composer/Node dependency at runtime**.
  - Eleven sidebar pages: Dashboard, Offers, Billboard adverts (simple/advanced + secure
    image re-encoding), Notifications, Message templates (ReDoS-safe regex + a
    single-sample test), Payments (read-only, masked identifiers), Support details, App
    configuration, Updates & versions, an append-only Audit log, and Settings.
  - **Two account types only:** Super Admin (full control) and Admin (the Super Admin
    ticks which sidebar pages the Admin may see/edit). No roles matrix, no 2FA.
  - **Auto-generated offer IDs** (`data_14`-style) — the admin never types an ID.
  - **Draft → publish → rollback** with immutable, versioned, SHA-256 checksummed
    snapshots; a baseline is published on install so imported records are live (not
    dozens of "drafts"); rollback creates a new later version.
  - **One read-only sync endpoint** `GET /api/app-data` returns the latest published
    offers, adverts, templates, support details, app config, update info and version
    (ETag/`304`, rate-limited).
  - **Zero-touch install:** the database provisions all tables + seed data + baseline on
    first load (no phpMyAdmin, no SQL). On cPanel you create the DB + user once in the
    MySQL wizard and fill `config.php`; everything inside the DB is automatic thereafter.
  - **Offline Till/Paybill/support** are set on the Support page and served via
    `/api/app-data` — never hardcoded, and distinct from the server-side STK shortcode
    (which stays in `mybingwa-api/config.php`).
  - My Bingwa brand design system (Outfit/Poppins, action-green primary, light/dark/
    system themes, responsive mobile drawer). Safe MySQL migrations + idempotent seeder.
    Pure-logic test harness (`tests/run.php`).
- **Permanent release identity for version 1.** The app now carries its permanent
  production `applicationId` **`com.bingwasokoni`** with **`versionName 1.0.0`** and
  **`versionCode 1`** — the identity used on both Google Play and the direct/GitHub
  channel forever, so updates on either channel supersede correctly. (The internal
  `namespace` stays `com.example`; it names generated classes only and is invisible
  to users and Play.)
- **Dual distribution from one codebase and one signing identity.** Two product
  flavors — **`direct`** (signed APK for GitHub/sideload) and **`play`** (AAB for the
  Google Play Console) — share the same `applicationId` and the same app-signing key,
  so a user can move between the two channels and updates apply cleanly.
- **Debug/release separation.** Debug builds install alongside the release app using
  the `.debug` application-id suffix, a `-debug` version-name suffix and the **"My
  Bingwa Dev"** launcher label (release stays **"My Bingwa"**), and use AGP's
  auto-generated debug keystore — never the permanent release key.
- **Signed release pipeline (`.github/workflows/release.yml`).** Runs only on `v*`
  tags or a manual dispatch (never on feature branches, so signing secrets are never
  exposed). It builds `:app:assembleDirectRelease` and `:app:bundlePlayRelease`,
  produces `My-Bingwa-v<version>-direct.apk`, its `.sha256` checksum and
  `My-Bingwa-v<version>-play.aab`, and publishes them as assets on a GitHub Release.
- **One-time keystore bootstrap workflow (`.github/workflows/bootstrap-keystore.yml`).**
  Generates the permanent upload key and delivers it as a GPG-encrypted artifact for
  the owner to decrypt, back up offline and store as the `KEYSTORE_BASE64` secret.
- **In-app update contract for the direct/GitHub channel (`update.json`).** The
  sideload build checks a published `update.json` (via the `UPDATE_MANIFEST_URL`
  BuildConfig field) so directly-installed users get an "update available" prompt;
  Play users are updated natively by Google.
- **Release documentation.** A public privacy policy (`PRIVACY.md`, required for a
  Google Play listing), a first-time Play publishing runbook
  (`docs/RELEASE_PLAYSTORE.md`) covering secrets, keystore bootstrap, the release
  build, Play App Signing with the owner's own key, the Data safety/store-listing
  steps and cross-channel updates, and public release notes
  (`RELEASE_NOTES_v1.0.0.md`).

### Changed

- **Admin V2 is now centre-aligned everywhere.** The shared design system and
  components centre page content, cards, sections, headings, titles, text, forms and
  empty states across every admin page; tables keep their column structure but the
  table, its headings, cells and the actions column are visually centred.
- **Admin V2 dashboard trimmed.** The "Last app sync" card was removed and "Latest
  payments" now shows only the six most recent records.
- **Admin V2 removals (per owner request):** the Support page no longer exposes the
  editable "Offline purchase instructions" fields (stored values are preserved and
  still published); App configuration no longer has the "General support message"
  setting; and the Settings page no longer has any Appearance/theme controls.
- **Admin V2 drastically simplified to a two-person control panel.** Removed the
  enterprise features that were never needed: two-factor authentication, granular
  role/permission matrix, the Analyst & Publisher roles, the "why-this" billboard
  simulator, personalisation weight controls, feature flags, emergency-disable controls,
  quiet-hours / campaign caps, snapshot-signing UI, the separate payment "gateway" page
  (and its `mybingwa-api` config overlay), separate Sender-ID management, the message
  regex "console", system diagnostics (`diag.php`), device telemetry / sync-events,
  active-session management, production/environment badges, per-record draft badges and
  the sidebar draft count, and the sync-health / release-history / revenue-chart
  dashboard cards. Access is now Super Admin (full) + Admin (page-level), the dashboard
  shows only six tiles + latest payments, and every page uses plain customer-friendly
  language. Payment and database functionality is unchanged.
- **Hardcoded seller numbers removed everywhere — including the app.** The Till
  (`4953696`), Paybill (`40450595`/`4050595`) and personal number (`0727921038`) are no
  longer baked into the server (`settings.sql`, `get_config.php`, `admin/*`,
  `config.sample.php`, admin-v2 seed) **or the Android app** (`AppConfig.DEFAULT` and
  `CachedOfflineConfigProvider.DEFAULT` are now blank). They are set once from the admin
  **Support** page and synced. The **offline** Till/Paybill shown to customers is decoupled
  from the server-side STK shortcode used to initiate payments (which stays only in
  `mybingwa-api/config.php`).
- **The app now shows the admin's published data with no app change.** The payment API's
  `get_offers.php` / `get_config.php` / `get_templates.php` now serve the latest
  **published** admin snapshot (read from the shared `mb_configuration_releases` table),
  falling back to the legacy tables when the admin isn't installed/published. So the
  existing app — which already syncs these endpoints on connectivity — reflects what the
  owner publishes, while the admin's own `GET /api/app-data` remains available for direct
  consumption.
- **Offer IDs are generated automatically** (`data_14`-style, per category) instead of
  being typed by the operator; existing IDs stay immutable.
- **App-sync API consolidated to a single `GET /api/app-data`** (was
  `/api/v1/app/manifest|snapshot|sync|offers|config|templates|sync-events`).
- **Payment API auto-creates its `payments` table** on first DB connection
  (`mybingwa-api/db.php`), so `schema.sql` no longer needs a manual phpMyAdmin import.
- **Publishing/rollback simplified** (no signing UI, no re-auth prompts, no draft
  badges): a "Preview changes" button appears in the header only when changes exist, and
  a baseline is published on install so imported records are live rather than drafts.
- **Release output is now flavor-qualified.** With the `direct`/`play` flavors, the
  shipping variants are `directRelease` (APK) and `playRelease` (AAB) instead of a
  single release variant.

### Security

- **Leaked server secrets purged from all Git history + push guards added.** An older
  `server/mybingwa-api/config.php` (live Daraja consumer key/secret/passkey) had been
  committed early in the project and later un-tracked, so its blobs remained reachable
  in the public repo's history. All 93 commits were rewritten with `git filter-repo` to
  strip the file from every branch and the `v1.0.0` tag, then force-pushed. `.gitignore`
  now blocks `**/config.php` and common secret files (templates excepted), and
  `.githooks/pre-commit`/`pre-push` (enabled via `core.hooksPath`) refuse to commit or
  push `config.php` or files containing live-secret markers. The exposed keys must be
  rotated (in progress) and treated as compromised; forks/clones and GitHub's cache may
  retain old objects until GitHub GC.
- **The Google Play build ships no restricted permission.** The `play` flavor's
  manifest overlay removes `RECEIVE_SMS` and the `SmsDeliveryReceiver`, so the Play
  (AAB) submission needs no SMS permissions declaration and cannot be rejected for
  one. The **`direct`** (GitHub) build keeps `RECEIVE_SMS` for the opt-in, local
  Safaricom delivery / low-balance detection only — SMS content is read on-device and
  never leaves the phone.
- **Release signing material stays out of the repository.** No keystore is committed;
  the permanent key is supplied to CI only through protected GitHub Actions secrets
  (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`, `KEY_ALIAS`), decoded into the
  runner's temp dir inside the protected release job and deleted in an always()-run
  cleanup step. Daraja/payment secrets remain only on the payment backend.

- **Real on-device persistence (replaces the in-memory "Fake" store).** A new
  `data/persistence/LocalStore` (Preferences DataStore + Moshi JSON, no KSP) loads
  state on start and re-saves the whole snapshot on every change, so the customer's
  **name, profile, favourites, Activity (purchases), notifications, recent recipients
  and any in-flight order now survive process death** — the CLAUDE.md §2 promise that
  these are "local to the installation" is now actually true. Previously everything
  lived in `MutableStateFlow` and reset to seeded demo data on every restart. Unit
  test: full serialisation round-trip of the persisted snapshot (incl. enums).
- **Safe process-death payment restore.** The in-flight order is persisted; on a
  relaunch after the app was killed mid-payment it is settled to an honest **Waiting
  to verify** record (never silently lost, never re-charged) and appears in Activity.
- **Buy-for-another is now a real payment route (was permanently mocked).** With a
  configured backend it goes through the real gateway carrying `forSelf=false`; the
  hardened backend routes it to **Paybill with the recipient number as the account**
  (`stk.php` + `lib.php`). Only a debug build with no backend still simulates it.

### Security

- **Payment callback is authenticated by Safaricom source IP + amount cross-check.**
  `callback.php` authenticates Daraja's result webhook by its source IP (Safaricom's
  `196.201.212/213/214.x` block plus an explicit allowlist) and cross-checks the
  callback amount against the server-recomputed price (a mismatch is flagged, not
  confirmed), so a spoofed "paid" POST from any other IP is ignored. NOTE: an earlier
  `?token=` URL-secret approach was removed — Daraja **strips the query string** from
  the CallbackURL, which silently rejected every real callback and broke all payment
  confirmation; IP auth is the reliable, standard fix (validated live end-to-end for
  both buy-for-myself and buy-for-another).
- **`X-App-Key` validation is now fail-closed** on `stk.php`/`status.php` (empty/
  missing key → 401), and **STK idempotency is atomic** (insert-first on the unique
  `client_request_id`) so concurrent duplicate requests no longer fire two real STK
  prompts or 500. New server config keys: `callback_secret`, `callback_ip_allowlist`,
  `paybill_shortcode`, `paybill_passkey`, `trusted_proxy_header` (documented in
  `config.sample.php`; no real secrets committed).
- **A release build can never fake a payment success.** When no backend is configured,
  release builds use `UnavailablePaymentGateway` (payments fail honestly) instead of
  the dev simulation that returned a fabricated M-Pesa receipt. Real STK requires both
  a base URL (defaults to the production API host) and the `PAYMENTS_APP_KEY` secret.

### Added

- **Buy-for-another number is now implemented (was a mock).** Per
  `docs/Buy For Another Number - Implementation Spec.md`, adapted to our Paybill setup:
  - The app routes a buy-for-another purchase through the real backend (`forSelf=false`);
    the server charges the payer via the Paybill with the **recipient's number** as the
    AccountReference.
  - On a **confirmed** buy-for-another payment, `callback.php` sends a **mocked M-Pesa
    SMS** to the fulfilment phone whose "received from" number is the **recipient** (not
    the payer), so the operator loads the bundle for the right line. The message is a
    byte-for-byte reproduction of the Safaricom format (`lib.php`
    `build_mocked_mpesa_message`), sent via the BlazeTechScope bulk-SMS API
    (`send_mocked_mpesa_sms`). Self-purchases never trigger it.
  - Duplicate-safe: the SMS fires only on the atomic REQUESTED→CONFIRMED transition, so
    Daraja's repeated callbacks never double-send. New config keys (`fulfilment_phone`,
    `business_name`, `sms_api_url`, `sms_api_key`, `sms_sender_id`) documented in
    `config.sample.php`.

### Fixed

- **Support Till/Paybill/phone details now save.** The Support form validated these
  fields with `max:24`, but the validator treats a numeric-looking string as a numeric
  comparison, so any real Till/Paybill/phone number greater than 24 was rejected with
  "Must be at most 24…". A new length-based `maxlen`/`minlen` rule (always `mb_strlen`)
  replaces `max:24` on those fields, so valid shortcodes and numbers save while length
  is still bounded and the `msisdn` format check is unchanged.
- **A fresh install no longer shows prefilled data and opens on onboarding.** The
  default profile was seeded with a real name/number and `isOnboardingCompleted=true`,
  so a new install skipped onboarding and showed the owner's details. The default
  profile is now empty with onboarding not completed (app opens on onboarding on first
  launch), and all seeded demo data (purchases, notifications, recent recipients,
  favourites — which also carried a real phone number) is removed. Tests seed their own
  fixtures via new test-only constructor params. Phone normalisation already supports
  `07…`/`01…` (e.g. `0112385760` → `254112385760`); the earlier mangling was a stale APK.

- **Online STK push now actually works (was failing / not delivering).** Two live
  defects found by firing a real KSh 1 STK against production Daraja:
  - `offers.php` (the server's authoritative price map) used stale ids `off_1..off_16`
    while the app sends `data_6`/`sms_2`/etc., so `stk.php` returned `UNKNOWN_OFFER` and
    the app showed an instant "could not start payment". Rewrote `offers.php` to the
    real catalogue ids/prices (matching `offers.sql`).
  - The seller shortcode `4050595` is a **Paybill**, but the config used
    `CustomerBuyGoodsOnline` (Till), so Daraja accepted the request (ResponseCode 0)
    yet never delivered the prompt. The online buy-for-myself route now uses
    `CustomerPayBillOnline` (deployment change in the server's git-ignored `config.php`).
    A real KSh 1 Paybill STK was confirmed delivered to the test phone.
- **Buy-for-another is a mock again (owner decision).** It uses a different M-Pesa
  integration that is not built yet, so it no longer routes through the real
  self/Paybill gateway — always a labelled simulation, even when a backend is
  configured.
- **Settings notification/SMS toggles now reflect the real OS permission** and are
  persisted, instead of showing "on" optimistically regardless of the actual grant.
  MainActivity writes the true `POST_NOTIFICATIONS` / `RECEIVE_SMS` state into the
  profile on start and after every permission result.

- **Offline-first (Phase 6) + server sync & admin (Phase 7).** The app now knows when
  it is offline and stays fully usable; the server (owner's cPanel) is sync-only.
  - Real connectivity drives the offline state (`setConnectionState(NONE)` ⇒ offline).
  - **Offline manual payment:** when offline, tapping buy skips the online review and
    shows a **Copy Till/Paybill & open M-Pesa** action that copies the number and
    opens the SIM Toolkit (own number ⇒ Till, another ⇒ Paybill). Honest "I've paid"
    receipt tracking is kept (Waiting to verify / Payment not confirmed).
  - **Server config sync:** Till, Paybill, support number and WhatsApp are fetched
    from `get_config.php` when online and CACHED (SharedPreferences) so they always
    work offline; baked-in defaults cover a fresh install. Help + the offline steps
    read these (also fixes the old Help Paybill `4050595` → `40450595` mismatch).
  - **Server offers sync:** the catalogue is fetched from `get_offers.php` when online
    (preserving local favourites/bought-today); the bundled catalogue is the
    guaranteed offline base. `data/catalogue/*`, repo `syncCatalogue()`.
  - **Admin panel** (`server/mybingwa-api/admin/`): brand-styled, password-protected
    dashboard to manage offers, payment/support details and notification templates —
    creates its own tables on first load. New server endpoints `get_config.php`,
    `get_offers.php` (+ `settings.sql`, `offers.sql`, `templates.sql` seeds).
  - Unit tests: connectivity→offline flag, config seed/sync, catalogue sync + local
    fallback + favourite preservation.

- **Activity-aware notification system (owner request).** A new
  `core/notifications` subsystem plus the integration to drive it:
  - **Brand-styled, non-noisy system notifications** via `AppNotifier` using the
    monochrome status icon (`ic_stat_my_bingwa`) and brand-green accent, on
    separated channels (Transactions default importance; Offers/Reminders/Updates
    low importance, silent) — transaction updates kept apart from promotions (§9).
  - **Notification permission on opt-in:** enabling **Push Notifications** in
    Settings shows an in-app rationale, then requests `POST_NOTIFICATIONS`
    (Android 13+); a denied state links to app settings.
  - **Connection-state awareness** (`ConnectivityObserver`): Wi-Fi / mobile data /
    both / none, fed into the offer-suggestion logic.
  - **Safaricom SMS watching (delivery + low balance)**, opt-in behind a separate
    "Bundle & balance alerts" toggle that requests `RECEIVE_SMS` after a clear
    rationale. A `SMS_RECEIVED` receiver classifies messages with a **pure,
    server-syncable template + parser** (`SmsTemplates`/`DefaultTemplates`/
    `SafaricomSmsParser`) seeded from the real Safaricom formats (data/SMS/minutes
    delivery from `Safaricom`/`SAF_OfaMOTO`; low-balance from `SAF_Balance`).
    Templates are data, not code — a `RemoteTemplateSync` seam is left for the
    future server.
  - **Honest delivery reconciliation:** a matched delivery SMS flips the newest
    matching purchase's new `PurchaseRecord.isDeliveryConfirmed` flag and adds a
    quiet, **Safaricom-attributed** in-app note (never a loud "bundle received"
    every time, never a "we delivered/activated" claim — §7). Activity shows a
    small "Safaricom confirmed delivery" line when confirmed.
  - **Low-balance nudges + suggestions** (`OfferSuggestionEngine`) use only §8
    allowed language ("More … offers for you", "Top up with these deals") — never
    "you are running out / you need more data".
  - Unit tests: SMS parser (all four real samples + negatives), suggestion engine,
    and delivery/low-balance reconciliation.

### Fixed

- **App launcher icon and Home header now use the real brand logo, not the mock.**
  The mock `ic_mybingwa_symbol` vector was still driving the Home header **and**
  the adaptive launcher foreground (`ic_launcher_foreground.xml` wrapped it), so
  modern phones showed the mock even after the PNG mipmaps were replaced.
  `ic_mybingwa_symbol`(+`_mono`) are now the approved asset PNGs
  (`my-bingwa-symbol-transparent` / `ic_launcher_monochrome`), fixing the header,
  the adaptive foreground (kept inside the safe-zone layer-list) and the themed
  monochrome icon in one place; the mock vectors were deleted.
- **"Special" category icon now gently glitters** (soft scale + brightness pulse
  on the star) to draw attention to high-value offers; respects reduced motion.

- **Launch splash now uses the approved brand mark.** The Android 12+ splash was
  showing a crude hand-drawn vector (`ic_splash_logo.xml`) instead of the real
  logo. Replaced it with the approved `my-bingwa-splash-mark-512.png` asset
  (`drawable-nodpi/ic_splash_logo.png`); the launcher and onboarding logos were
  already correct.
- **Promotion billboard CTA no longer overlaps the text.** The "Buy now" button
  was absolutely positioned over the subhead and hid it. The slide is now a
  `Row` with the text taking `weight(1f)` and the CTA reserving its own space, so
  they can't overlap — robust at small width and 200% font scale.
- **Settings permission dialogs (notifications & SMS) now have a clean button
  layout.** The rationale dialogs previously cross-aligned "Allow" with a stacked
  "Not now / Open app settings" column. They now use a single full-width vertical
  stack — primary **Allow**, then **Not now**, then **Open app settings**.

### Security

- **`RECEIVE_SMS` is Google Play-restricted.** It is declared for the opt-in
  bundle/balance detection and is intended for the **direct-APK** distribution;
  the Play (AAB) build should exclude it. SMS logic is isolated behind a receiver
  + in-memory signal bus; full SMS bodies and phone numbers are never logged.

### Changed

- **Offers filter reverted to the classic compact styling (owner feedback).** The
  Phase-3 filter-sheet redesign (bold green price flourish, rounded highlight
  rows, "Filter & sort") was reverted to the classic look ("Filter Offers", calm
  values, `FieldButtonShape` rows) while keeping all functionality — category,
  price range, validity and the five sort orders.

- **Notification centre is now an in-app slide-up overlay (per owner request),
  not a standalone page.** Tapping the Home header bell opens a `ModalBottomSheet`
  above the app shell instead of navigating to a disconnected full-screen route,
  so context and the bottom navigation are preserved. Each notification can be
  read (tap), copied (to clipboard) and cleared (single or "Clear all"); a
  notification carrying a deep-link route routes to it and closes the overlay.
- **Settings moved to the bottom navigation (per owner request).** The Home
  header no longer shows a profile avatar; that space now holds only the
  notification bell. Settings is a fifth primary bottom-nav destination and is
  reached and highlighted like every other tab.
- **Home simplified (per owner feedback):** removed the Home search bar and the
  Popular / Bought today / Buy again sections. After the promotion billboard the
  Home now shows only **Your favourites** (vertical list) and **You may also
  like** (a horizontally swipeable row of similar offers).
- **Offer card reverted to the classic compact design** (category tag, name +
  validity, price, buy-tag, and a **Buy** button). The earlier Phase 3 card
  redesign was undone — only the underlying feature logic was wanted, not a UI
  change. Tapping a card or its Buy button opens the purchase sheet directly
  (the interim offer-details sheet was removed).
- **Catalogue replaced with the real My Bingwa offers** (Data, SMS, Minutes,
  Special) with correct prices, validity, per-day tags and validity bands; the
  offer model gained an explicit `validityBand` so the Offers validity filter is
  exact. Billboard promotions now advertise the real high-value monthly/weekly
  offers.
- **Billboard CTA** moved to the right and vertically centred, with more height/
  padding so its label is never clipped.

### Added

- **Checkout & payment state machine (Phase 4):** real payment logic behind a
  transport-agnostic payment-gateway interface, replacing the demo `delay()` stub.
  - Payment state machine (`core/payment`): the Plan.md §6 online (STK) and offline
    device-first transitions as a pure, unit-tested machine with the exact
    customer-facing copy; illegal transitions throw so a payment is never
    optimistically confirmed.
  - **Daraja via the owner's cPanel PHP API** (`server/mybingwa-api/`): a tiny
    4-endpoint PHP API (`stk.php`, `status.php`, `callback.php` + shared `lib/db/
    offers/config`) that holds the Daraja consumer key/secret + passkey and owns the
    CallbackURL, so **no Daraja secrets ship in the APK**. `stk.php` recomputes the
    price from `offerId` and is idempotent on `clientRequestId`; `status.php` falls
    back to Daraja `stkpushquery` if the callback is slow. Ships with `schema.sql`
    and a beginner cPanel walkthrough README. The app calls it over Retrofit
    (`stk.php`/`status.php`) with an `X-App-Key` header; base URL + app-key are
    non-secret `BuildConfig` fields (`PAYMENTS_BASE_URL`, `PAYMENTS_APP_KEY`) injected
    from GitHub secrets, empty by default.
  - A clearly-labelled local **simulation** gateway used when no backend URL is
    configured (and for the still-mocked buy-for-another path), so the app stays
    testable on a phone without ever faking a real success.
  - Idempotent checkout: every attempt carries a `clientRequestId`; a double-tap or
    retry returns the existing record instead of charging twice. Airtight in-flight
    guard in the sheet plus repository-level idempotency.
  - Offline signed-config interface (`OfflinePaymentConfig` + provider): Till/Paybill
    values with a validity window and signature check, and pure eligibility rules —
    expiry, amount ambiguity (shared price on the same route), Till vs Paybill route,
    and hard once-per-day offline blocking.
  - Offline receipt capture: **I've paid** with an M-Pesa code → **Waiting to verify**;
    without a code → **Payment not confirmed**. Never shown as success.
  - Process-death restoration **contract** (`ActiveOrder` + `activeOrder` flow),
    in-memory this phase; Phase 6 persists it.
  - Kenyan phone normalisation (`KenyanPhone`): `07…/01…/254…/+254…` → E.164, grouped
    display and MSISDN for the gateway; invalid numbers block STK.
  - New payment statuses (`EXPIRED`, `NOT_CONFIRMED`, `COULD_NOT_VERIFY`) and
    `PurchaseRecord` fields (`clientRequestId`, `orderReference`).
  - Unit tests: state machine (full transition table + illegal transitions + copy),
    phone normalisation, offline eligibility, and repository idempotency/honesty.
- **Catalogue experience (Phase 3):** real logic for Home, Offers, offer
  details, search, filters, sorting, favourites, promotions and daily purchase
  awareness, replacing the demo screens.
  - `CatalogueViewModel` derives immutable `HomeUiState`/`OffersUiState` from the
    repository flows (screen-level ViewModel, injectable clock for tests).
  - `CatalogueLogic` — pure, unit-tested functions for filtering, five sort
    orders (incl. shortest/longest validity), Nairobi-day daily purchase state,
    Home section derivation, restrained personalised suggestions and promotion
    selection.
  - Home now follows the Plan.md §5.2 order: greeting, search, category
    shortcuts, one promotion billboard, Popular, Bought today, More offers you
    can buy, Buy again, Your favourites and a restrained "You might also like".
  - `PromotionBillboard` — a swipeable advert surface (the "television"): solid
    brand-colour slides (no gradients), optional bundled artwork, manual-swipe
    carousel with page indicators (no auto-rotation), and a breathing CTA that
    respects reduced motion. Rotates the seller's biggest weekly/monthly/
    high-value offers plus announcements and app updates.
  - `OfferDetailsSheet` — offer details bottom sheet (allowance, price, validity,
    daily state, favourite, **Buy bundle**) that hands the purchase to checkout.
  - Favourite toggle with an **Undo** snackbar on Home and Offers.
  - Daily purchase awareness presentation: Available today / Bought today /
    Available again tomorrow / {n} purchases left today / Waiting to verify,
    per-recipient and per Africa/Nairobi day.
  - Offers filter sheet now offers category, **price range**, validity and all
    **five** sort orders; results, query, filters, sort and scroll position are
    preserved across tab switches.
  - Loading (skeleton), empty-from-filters, empty-catalogue and offline states
    for both Home and Offers.
  - New core models: `Promotion` (+ `PromotionKind`/`PromotionAccent`),
    `PurchasePolicy` and `OfferDailyState`/`DailyStateKind`.
  - Repository contract extended with `promotions`, `catalogueLoading`,
    `setFavourite(id, isFavourite)` and `refreshCatalogue()` (fake pool seeded;
    Phase 6/7 syncs real data into Room).
- Bundled brand typefaces: Outfit (variable) and Poppins (Regular/Medium/
  SemiBold/Bold static) under `app/src/main/res/font`, with OFL licences kept in
  `app/licenses/`. Typography now maps every Material 3 role to Outfit/Poppins,
  so no text falls back to the system font.
- Theme-aware category colours (`ui/theme/CategoryColors.kt`): category chips and
  icon tiles resolve their accent/container/on-container from the active theme,
  designed for both light and dark (design.md §7.3) instead of baked light hexes.
- Branded launcher icon set: proper Android 13+ themed (monochrome) silhouette
  layer, adaptive foreground/background, and My Bingwa raster launcher icons for
  API 24–25; monochrome notification icon (`ic_stat_my_bingwa`) placed for all
  densities ahead of the notifications phase.
- Android 12+ launch splash showing the My Bingwa mark on the brand canvas
  (light and dark), via `androidx.core:core-splashscreen`.
- Checked-in Gradle wrapper (Gradle 9.3.1) under `my-bingwa/gradle/wrapper/`
  with `gradlew`/`gradlew.bat`, so the project builds from the command line and
  CI without Android Studio. Distribution is pinned with a SHA-256 checksum.
- Root `.gitignore` covering build output, `local.properties`, `.env`,
  keystores, signing material, Firebase config/logs and IDE state.
- `CHANGELOG.md` (this file) following Keep a Changelog.
- Top-level `README.md` describing the repository, the CI-first build workflow
  and where to get a debug APK.
- `docs/REPO_INVENTORY.md`: imported-project inventory, module/package map,
  proposed feature ownership boundaries for parallel phases, and the shared
  contracts that Phase 1 must create before parallel feature work starts.
- GitHub Actions workflow `.github/workflows/feature-debug-build.yml` that, on
  feature/chore branches and `main`, runs `test lint assembleDebug` via the
  Gradle wrapper and uploads a clearly named debug APK plus test/lint reports.

### Changed

- **Honest payment language (Phase 4):** the checkout success screen now shows
  **Payment received** with "Please wait for the bundle on {recipient}" and no
  delivery timeframe (was "Purchase successful" / "Your bundle will be received in a
  few minutes"). The checkout now surfaces every honest state — Payment cancelled,
  Payment failed, Request expired, Still checking payment and We could not verify —
  and never claims delivery. Phone-field labels use the exact spec strings
  **Bundle recipient** and **M-Pesa payment number**.
- The checkout Till/Paybill values are read from the signed offline config (single
  source of truth) instead of hardcoded literals in the sheet.
- Offer cards are now pure selection surfaces: the compact full-size **Buy**
  button was removed (Plan.md §5.3) — tapping a card opens offer details / the
  purchase sheet. Cards now present calm daily-state labels.
- Home promotion surface no longer uses a gradient banner; it is the solid
  brand-colour `PromotionBillboard`.
- Typography engine switched from the downloadable Google Fonts provider (which
  needed real Google certificates and silently fell back to the system font) to
  the bundled font files.
- Bottom navigation reduced to the four primary destinations — Home, Offers,
  Activity, Help (design.md §12.1). Settings opens from the Home avatar.
- Repository layout: planning documents (`Plan.md`, `design.md`,
  `CLAUDE_KICKOFF_AND_BUILD_PHASES.md`) moved into `docs/`. Operating brain
  (`CLAUDE.md`), `memory.md` and `CHANGELOG.md` remain at the repository root.
- Brand asset kit folder renamed from `assests/` to `assets/` (typo fix).

### Fixed

- **Bottom-navigation bug:** tapping Home from Offers could get stuck, and
  tapping Home from Help/Activity could land on Offers. Root cause was mixing
  plain `navigate()` calls to routes that are also bottom-nav tabs with the
  save/restore tab state machine, plus popping to `graph.startDestinationId`
  (which can still be `onboarding`). All jumps to a tab route now use one
  consistent `popUpTo("home"){ saveState }` + `launchSingleTop` +
  `restoreState`, guarded against re-navigating the current route; reselecting a
  tab scrolls its list to the top.
- Debug build no longer references a non-existent, git-ignored
  `debug.keystore`; it now uses AGP's auto-generated debug signing config, which
  unblocks `assembleDebug` in a clean CI environment.
- Corrected `ExampleRobolectricTest` to expect the real app name "My Bingwa"
  (was the template default "My Application"), so the unit-test gate passes
  truthfully.
- Pinned `ExampleRobolectricTest` to `@Config(sdk = [34])`; Robolectric 4.16.1
  has no SDK 36 sandbox and threw `UnsupportedOperationException`, failing the
  test gate the first time CI reached it (after the KSP crash was removed).

### Removed

- Fake placeholder font certificates (`res/values/font_certs.xml`) and the
  `ui-text-google-fonts` dependency, now that fonts are bundled locally.
- AI Studio scaffolding that My Bingwa does not use and that broke the CI build:
  the KSP plugin with its unused Room/Moshi codegen (KSP2 crashed on the runner
  during annotation processing), the `google-services` and `secrets` Gradle
  plugins, the Firebase BOM, `firebase-ai` (Gemini) and `firebase-appcheck`
  dependencies, and `.env.example`.
- Empty stray `firebase-debug.log` from the repository root.
- Broken custom `debugConfig` signing config from `app/build.gradle.kts`.
- Orphaned template `GreetingScreenshotTest.kt` (and its `greeting.png`) that
  referenced deleted template symbols (`MyApplicationTheme`, `Greeting`) and
  could not compile.

### Security

- Confirmed no keystores, `.env` files, `google-services.json`,
  service-account files or other secrets are present in the imported project.
- `.gitignore` hardened so signing material and secrets cannot be committed.

### Internal

- Phase 0 baseline audit of the Google AI Studio-generated UI recorded in
  `memory.md` and `docs/REPO_INVENTORY.md`.
