# Skylink Bingwa — Repository Inventory & Coordination (Phase 0)

**Purpose:** Give every later Claude session an accurate map of the imported
project, clear ownership boundaries for parallel work, and the shared contracts
that must exist before feature branches diverge.

**Status:** Imported Google AI Studio UI prototype, made build-safe in Phase 0.
This is a **UI prototype with a faked, in-memory data layer** — not a
production architecture. Treat generated screens as reference composition, not
as the product authority. `docs/design.md` and `docs/Plan.md` win on every
conflict.

---

## 1. Build & project facts (recorded, not changed in Phase 0)

| Fact | Value |
|---|---|
| Project type | Native Android, Kotlin, Jetpack Compose + Material 3 |
| Gradle root | `skylink-bingwa/` |
| Modules | Single `:app` module (monolithic) |
| Gradle wrapper | **Added in Phase 0** — Gradle `9.3.1` (AGP 9.1.1 minimum), SHA-256 pinned |
| Android Gradle Plugin | `9.1.1` |
| Kotlin | `2.2.10` |
| Compose BOM | `2024.09.00` |
| `namespace` | `com.example` (placeholder — finalise in Phase 1) |
| `applicationId` | `com.aistudio.skylinkbingwa.k3p9zq` (AI Studio placeholder — **unresolved**, do not lock yet) |
| `minSdk` / `targetSdk` / `compileSdk` | `24` / `36` / `36` (compile uses `release(36){ minorApiLevel = 1 }`) |
| `versionName` / `versionCode` | `1.0` / `1` (should become semantic `1.0.0`) |
| Debug signing | AGP auto-generated debug keystore (Phase 0 fix) |
| Release signing | Env-var driven, protected CI only; no keystore committed |

### Dependencies present
- Compose (UI, Material 3, icons core+extended, tooling, `ui-text-google-fonts`)
- Navigation Compose `2.8.9` (string routes)
- Room `2.7.0` (+ KSP) — **declared, unused** (no entities/DAOs/database)
- Retrofit `2.12.0` + OkHttp `4.10.0` + logging-interceptor — **declared, unused**
- Moshi `1.15.2` (+ codegen) — present; **Plan.md specifies kotlinx.serialization instead**
- Coroutines
- Firebase BOM `34.15.0`, `firebase-ai` (Gemini), `firebase-appcheck-recaptcha` — **AI Studio cruft, unused by UI**
- `google-services` plugin (missing-json set to WARN/passthrough), `secrets` plugin (Gemini `.env`) — **AI Studio cruft**
- Robolectric `4.16.1` + Roborazzi `1.59.0` (screenshot testing)

### Missing vs Plan.md architecture
- ❌ Hilt (no plugin/deps) — manual `FakeBingwaRepositoryImpl()` instantiation
- ❌ Screen-level ViewModels (`lifecycle-viewmodel-compose` present but unused; all state hoisted into one `SkylinkBingwaApp` composable)
- ❌ Room entities/DAOs/database (data is in-memory `StateFlow`)
- ❌ DataStore (dependency commented out; profile/theme lost on restart)
- ❌ kotlinx.serialization (Moshi used instead)
- ❌ WorkManager, FCM messaging service/channels, Baseline Profile / Macrobenchmark
- ❌ Bundled Outfit/Poppins fonts (uses **downloadable** Google Fonts; `font_certs.xml` holds **placeholder/fake certs**, so fonts will not load at runtime → system-font fallback)

---

## 2. Source map (`skylink-bingwa/app/src/main/java/com/example/`)

```
MainActivity.kt                  Single activity; NavHost (string routes); owns ALL state; purchase sheet overlay
core/model/                      Plain data classes (no serialization/Room annotations)
  OfferItem.kt (+DailyRule)      Catalogue item; has unused `offlineInstructionsExpired`
  OfferCategory.kt               Category enum; HARDCODES light-theme colours (breaks dark mode)
  PurchaseRecord.kt              (+PaymentStatus, PaymentMethod) — status enum labels are honest
  UserProfile.kt                 name, primaryNumber, notificationsEnabled, isOnboardingCompleted
  NotificationItem.kt            id, title, body, isRead, category, timestamp
  AppThemeSetting.kt             SYSTEM / LIGHT / DARK
core/ui/                         Shared components: Buttons, Fields, OfferCard, CopyableValueBlock,
                                 SkylinkBingwaTopAppBar, SkylinkBingwaBottomNav (5 items incl. Settings),
                                 SkeletonsAndEmpty (skeleton defined but unused)
data/fake/
  BingwaRepository.kt            Interface for all app data/actions
  FakeBingwaRepositoryImpl.kt    In-memory: 13 hardcoded offers, seeded purchases/notifications,
                                 faked STK (delay 1800), faked offline pay; fabricates M-Pesa codes
feature/onboarding/              3-step; OVER-animated (gradient ring, confetti, glass cards, bounce)
feature/home/                    Greeting, categories, ONE gradient promo hero; missing several sections
feature/offers/                  Search, category chips, filter/sort sheet, offer list
feature/purchase/                5-step purchase ModalBottomSheet; hardcoded Till/Paybill
feature/activity/                Local activity list, multi-select delete, undo, detail sheet
feature/help/                    Support, offline how-to (centred), FAQ; Paybill inconsistent with purchase
feature/notifications/           Notification centre, soft opt-in prompt
feature/settings/                Profile edit, theme, notifications switch, About (hardcoded v2.4.0)
ui/theme/                        Color.kt (correct design tokens), Theme.kt, Type.kt (Outfit/Poppins), Shapes.kt
```

**Good news the kickoff brief did not assume:** the theme already encodes the
correct `design.md` colour tokens (deep action green `#006B27`, brand green
`#18C964`, info blue, promotion orange) and wires Outfit/Poppins typography
scales. The colour system is a usable starting point; the runtime font loading
and dark-theme category colours are not.

---

## 3. Design & product deviations (summary)

Full cited list is in `memory.md` (Phase 0 entry). Highest-priority items for
later phases (do **not** fix in Phase 0):

- **Payment honesty (Phase 4):** success heading says "Purchase successful" and
  "Your bundle will be received in a few minutes" — must become **Payment
  received** + "please wait for the bundle", no timeframe. Offline path never
  captures the M-Pesa receipt and ignores the expiry field.
- **Prohibited visuals (Phase 2/3):** gradients (Home hero, onboarding ring),
  confetti burst, glassmorphism cards, infinite rotation, bouncy/overshoot
  springs; reduced-motion never checked.
- **White-on-orange / white-on-gradient text** violates colour rules.
- **Bottom nav has 5 items incl. Settings** — spec wants exactly 4; Settings via
  profile/avatar.
- **Compact `OfferCard` has a full Buy button** — spec says it should not.
- **Dark-theme category colours** are hardcoded light hexes.
- **Typography gaps:** `labelMedium`/`bodySmall`/`titleSmall` unmapped → Roboto.
- **Centre-anchoring violations:** offline/help instruction paragraphs centred.
- **Placeholder identity/config:** default name "Bonke", number
  "0727 921 038", inconsistent Paybill (`40450595` vs `4050595`), hardcoded
  app version "2.4.0".

No hardcoded secrets or API keys were found in source. The only external URL is
a WhatsApp support link.

---

## 4. Proposed feature ownership boundaries (parallel Wave A)

These map the phase plan (`docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md`) onto the
actual generated files so parallel sessions edit disjoint areas. After Phase 1
restructures packages, paths change, but ownership stays.

| Phase / branch | Owns (generated equivalents) | Must NOT touch |
|---|---|---|
| **P2** `feature/onboarding-shell` | `feature/onboarding/**`, onboarding state/tests, first-run/profile capture UI | theme tokens, catalogue, checkout, Activity, global NavHost registration |
| **P3** `feature/catalogue-experience` | `feature/home/**`, `feature/offers/**`, offer details, search/filter/favourites/promotions UI + state | payment transport, Activity persistence, global navigation, theme tokens |
| **P4** `feature/checkout-state-machine` | `feature/purchase/**`, recipient/payer forms, payment state machine, offline-instruction UI + tests | real backend/credentials, global Activity screen, FCM, theme tokens |
| **P5** `feature/activity-support-settings` | `feature/activity/**`, `feature/help/**`, `feature/settings/**`, `feature/notifications/**` presentation + state | FCM transport, checkout state machine, global theme tokens |

**Cross-cutting (Phase 1 only, then frozen):** `ui/theme/**`, `core/model/**`,
`core/ui/**` shared components, DI graph, navigation contract, repository
interfaces, and `MainActivity`/app-shell NavHost. Feature phases consume these;
they do not redefine them.

---

## 5. Shared contracts Phase 1 MUST create before parallel work

If these do not exist as stable contracts, every parallel session will edit the
same model, navigation and theme files and collide. Phase 1 is a hard gate.

1. **Final package/namespace** (replace `com.example`) and module/package
   layout; decide single-module vs `core:*` + `feature:*`.
2. **Design system module** (`core:designsystem` or equivalent): finalized
   light/dark colour tokens, **bundled** Outfit/Poppins, typography (all
   Material roles mapped), shapes, motion tokens + reduced-motion helper, and
   the shared components (buttons, fields, cards, chips, sheets, nav, status).
3. **Immutable domain models** (`core:model`): `OfferItem`, `OfferCategory`
   (theme-derived colours, not hardcoded), `PurchaseRecord` + `PaymentStatus`
   (honest labels) + `PaymentMethod`, `UserProfile`, `NotificationItem`,
   `AppThemeSetting`, and the **payment state machine** state type.
4. **Repository interfaces** (no fake production success): catalogue, profile,
   favourites, activity/purchases, payment, notifications — with test doubles
   distinct from future production impls.
5. **Navigation contract + route ownership**: a route registry / entry-point
   contract so feature modules plug routes into the app shell without every
   phase editing one `NavHost`. Reduce bottom nav to 4 destinations.
6. **Persistence shells**: Room database/entity/DAO interfaces and DataStore
   keys for profile/theme — declared so features code against them, implemented
   in Phase 6.
7. **Offline config contract**: signed/expiring Till/Paybill configuration
   interface (no hardcoded numbers in composables) with an amount-decoding rule.
8. **Test utilities & preview strategy**: base Compose/Robolectric/Roborazzi
   harness, and `@Preview` conventions (light/dark/200% font).

---

## 6. Unresolved inputs (business/security — cannot be invented)

Recorded so no session fakes them. See `memory.md` for the live list.

1. **Permanent production `applicationId`** (current is an AI Studio placeholder).
2. **Signing key ownership/backups** (created once, outside the repo; protected CI secrets).
3. **Backend base URL and API contract** (catalogue, order, payment status).
4. **Daraja server integration + callbacks** (never in the APK).
5. **Production-safe Till/Paybill delivery** + unique/decodable offline amount rules.
6. **Firebase Android project + notification payload/deep-link contract.**
7. **Direct-APK update host/contract** and Google Play app ownership.
8. **Privacy policy, terms, support destinations, final public contact details.**

---

## 7. Build & CI

- Local command-line build: `cd skylink-bingwa && ./gradlew test lint assembleDebug`
  (needs JDK 17+ and Android SDK; this project must never require Android Studio).
- Authoritative build: GitHub Actions
  `.github/workflows/feature-debug-build.yml` — assembles the debug APK, uploads
  `skylink-bingwa-debug-<short-sha>` containing `Skylink-Bingwa-Debug-<short-sha>.apk`,
  then runs `test`/`lint` and uploads reports. Concurrency cancels obsolete runs.
- Known CI risk: bleeding-edge AGP `9.1.1` + `compileSdk 36 (minorApiLevel 1)`
  require very recent SDK components on the runner; the Roborazzi screenshot test
  renders without bundled fonts. The APK is assembled and uploaded before the
  test gate so it survives a test failure. Actual CI result is recorded in
  `memory.md`.
