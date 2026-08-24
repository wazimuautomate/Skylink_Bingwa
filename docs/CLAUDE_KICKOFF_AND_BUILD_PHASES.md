# Build phases and dependencies

## Phase 0 — Repository baseline and coordination

**Branch:** `feature/bootstrap-generated-ui`  
**Parallel:** No  
**Prompt:** Use the complete first prompt above.

Outcome:

- Imported project audited.
- Baseline build state known.
- Git and Gradle repository safe.
- Initial GitHub Actions debug build added or exact blocker recorded.
- Actual package/build settings recorded.
- Shared architecture risks identified.
- Phase 1 plan based on real code rather than assumptions.

Do not begin parallel sessions until this phase is merged.

## Phase 1 — Shared Android foundation and design system

**Branch:** `feature/android-foundation`  
**Parallel:** No  
**Depends on:** Phase 0 merged

Scope:

- Final practical module structure.
- Core immutable models and shared interfaces.
- Hilt foundation.
- App navigation contracts and route ownership.
- Room/DataStore/network interface shells without fake production success.
- `core:designsystem`.
- Exact light/dark tokens from `design.md`.
- Outfit and Poppins bundled and wired.
- Approved launcher, splash, notification and in-app logo assets.
- Custom buttons, fields, cards, chips, sheets, navigation and status
  components.
- Shared motion tokens and reduced-motion handling.
- Baseline test utilities and screenshot/preview strategy.
- App shell that feature modules can plug into without all editing one NavHost.
- CI kept green.

Critical outcome:

Phase 1 must create stable shared contracts before feature work branches.
Otherwise parallel sessions will all modify models, navigation and theme files.

## Parallel Wave A — Customer-facing feature modules

Start Phases 2–5 only after Phase 1 is merged. Create all branches from the same
Phase 1 `main`.

### Phase 2 — Launch, onboarding and local profile

**Branch:** `feature/onboarding-shell`  
**Owns:** onboarding feature, local profile UI/state and onboarding tests  
**Must not own:** global theme implementation, catalogue, checkout or Activity

Scope:

- Android system splash transition.
- Three polished onboarding steps.
- Field validation and Kenyan phone normalisation UI.
- Local name/primary phone DataStore integration through Phase 1 contracts.
- First-run completion state.
- Contextual notification soft prompt, but not FCM delivery.
- Time-based greeting input contract.
- Light, dark, large-text and reduced-motion states.

### Phase 3 — Home, catalogue, search, favourites and promotions

**Branch:** `feature/catalogue-experience`  
**Owns:** Home, Offers, offer details, search/filter, favourites and promotion
UI plus their feature-level state/tests  
**Must not own:** payment transport, Activity persistence or global navigation

Scope:

- Home sections and category navigation intents.
- Cached catalogue UI against repository interfaces.
- Search, filters, sorting and result state.
- Offer details.
- Favourite toggle and Undo.
- Popular, Bought today, More offers and Buy again sections.
- Once-per-day/multiple-per-day presentation.
- Promotion/announcement surface.
- Loading, empty, error and offline catalogue states.
- Preserve filters and list position.

### Phase 4 — Checkout, payment UI and offline purchase state machine

**Branch:** `feature/checkout-state-machine`  
**Owns:** checkout feature, recipient/payer forms, payment state machine,
offline instruction UI and related tests  
**Must not own:** real backend credentials, global Activity screen or FCM

Scope:

- For my number / for another number.
- Explicit Bundle recipient and M-Pesa payment number.
- Review and exact total.
- Double-tap prevention.
- Honest STK UI states behind a payment repository interface.
- Payment received/cancelled/failed/expired/still checking.
- Process-state restoration contract.
- Offline Till/Paybill instructions from a signed-config interface.
- Copyable business/account/amount values.
- Expired and ambiguous offline configuration states.
- Waiting to verify after customer-marked offline payment.
- Unit tests for the complete payment state machine.

### Phase 5 — Activity, Help, Settings and notification-centre UI

**Branch:** `feature/activity-support-settings`  
**Owns:** Activity, Help, Settings and notification-centre presentation/state
plus feature tests  
**Must not own:** FCM transport, checkout state machine or global theme tokens

Scope:

- Local Activity list, detail, copy, delete, selection and Undo UI.
- Amount available in detail even if compact rows de-emphasise it.
- Buy-again and report-problem intents.
- Help home, support actions, offline guides and searchable FAQ.
- Settings profile editing entry, theme selection, notification preferences,
  About/version/update states and clear-local-data confirmation.
- Notification-centre list, read state and deep-link intents.
- No broad SMS/Call Log permission.
- Empty, loading, dark, large-text and accessibility states.

## Phase 6 — Feature integration and offline data

**Branch:** `feature/integrate-offline-data`  
**Parallel:** No; this is the integration checkpoint  
**Depends on:** Phases 2–5 pushed and individually passing

Scope:

- Merge Phases 2–5 one at a time.
- Resolve integration conflicts without losing feature histories.
- Register feature routes in the app shell.
- Complete Room entities/DAOs, DataStore preferences and repository
  implementations.
- Make Room the canonical readable source.
- Wire fake/test and production interfaces distinctly.
- Add catalogue refresh and cached-first behaviour.
- Persist profile, favourites, Activity, purchase awareness and pending
  attempts.
- Implement Nairobi-day daily purchase rules.
- Ensure cross-feature actions work.
- Run full unit, lint, Compose and debug-assemble gates.
- Produce and physically test the integrated debug APK before merging.

## Parallel Wave B — Real services

These sessions may start after Phase 6 contracts are merged, but each requires
real implementation inputs. They must stop at a truthful interface or mock if
the required external service is not supplied.

### Phase 7 — Backend/API and online payment integration

**Branch:** `feature/api-payment-integration`  
**Owns:** network DTOs, API/repository implementation, order/payment transport
and contract tests

Required before completion:

- Confirmed backend base URL.
- Confirmed API contract.
- Server-side offer revalidation.
- Server-side Daraja integration and callbacks.
- Idempotency contract.
- Status polling/stream contract.
- No Daraja secrets in APK.

Scope:

- Retrofit/OkHttp/Kotlin serialization implementation.
- Catalogue/promotion sync into Room.
- Create-order and payment-status integration.
- Idempotency and safe retry.
- Restore pending payment after process death.
- Human-safe error mapping.
- Contract tests.

This phase cannot honestly become production-complete from UI code alone.

### Phase 8 — Notifications and background work

**Branch:** `feature/notifications-background`  
**Owns:** FCM, notification channels, WorkManager, local campaign ledger and
notification integration tests

Required before completion:

- Firebase Android project/config supplied securely.
- Remote payload/deep-link contract.
- Campaign policy confirmed.

Scope:

- Contextual notification permission.
- Separate transaction, promotion, reminder and update channels.
- FCM token lifecycle via backend interface.
- WorkManager catalogue refresh and flexible local reminders.
- Quiet hours, caps, deduplication and recent-purchase suppression.
- Private lock-screen content.
- Deep links and expired notification handling.
- Offline local templates without pretending remote delivery occurred.

### Phase 9 — CI, release identity and updates

**Branch:** `feature/release-pipeline`  
**Owns:** GitHub workflows, versioning, release variants, safe signing wiring,
checksums and update metadata

Required before production release:

- Permanent applicationId approved.
- Permanent signing key created outside the repository.
- Protected GitHub secrets configured.
- Direct-APK update host/contract confirmed.
- Google Play app ownership confirmed.

Scope:

- Debug `.debug` applicationId suffix and **Skylink Bingwa Dev** label.
- `directRelease` APK and `playRelease` AAB.
- Semantic `versionName` and increasing `versionCode`.
- Feature-branch debug APK artifacts.
- Protected signed-release jobs.
- SHA-256 checksum.
- GitHub Release assets.
- `update.json` or approved direct-update contract.
- No secrets exposed to ordinary feature branches.

## Phase 10 — Hardening, acceptance and release candidate

**Branch:** `release/1.0.0-rc1`  
**Parallel:** No  
**Depends on:** required Wave B integrations complete

Scope:

- Full regression.
- Payment retry, timeout, cancellation, double-tap and restart restoration.
- Offline configuration expiry and reconciliation.
- Accessibility at 200% text.
- TalkBack and reduced motion.
- Light/dark/system.
- Samsung A05/A06-class physical testing.
- Cold/warm startup checks, Baseline Profile and Macrobenchmark.
- APK size review.
- Security and privacy review.
- No secrets or sensitive logs.
- Direct APK update test using the same signing identity.
- Play internal test.
- Final changelog, memory and release notes.
- Merge/publish only after all evidence exists.

---

# 6. Decisions required before production completion

Parallel coding does not remove these business and security dependencies:

1. Permanent production applicationId.
2. GitHub repository and branch protection.
3. Permanent signing-key ownership and backups.
4. Backend base URL and API contract.
5. Daraja server integration and callback endpoints.
6. Production-safe Till and Paybill configuration delivery.
7. Unique/decodable offline amount rules.
8. Firebase project and notification payload contract.
9. Direct-APK update URL and signing-compatible update strategy.
10. Privacy policy, terms, support destinations and final public contact
    details.

