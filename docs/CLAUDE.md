# Skylink Bingwa — Claude Code Operating Instructions

This file is the operational brain for the Skylink Bingwa repository. Read it before
acting on every instruction.

It defines how work is understood, implemented, verified, documented and
delivered. Do not treat any section as optional unless the user explicitly
changes it.

---

## 0. Repository layout (where the authoritative files live)

Since the Phase 0 baseline, the planning documents live under `docs/`. When any
section below refers to `Plan.md` or `design.md`, read them at their `docs/`
path:

- `CLAUDE.md` — this file (repository root; auto-loaded by Claude Code).
- `memory.md` — project state and execution history (repository root).
- `CHANGELOG.md` — Keep a Changelog (repository root).
- `docs/Plan.md` — product scope, flows, architecture, API expectations.
- `docs/design.md` — canonical UI/UX, colour, typography, motion, accessibility.
- `docs/CLAUDE_KICKOFF_AND_BUILD_PHASES.md` — execution phases and boundaries.
- `docs/REPO_INVENTORY.md` — imported-project inventory, ownership boundaries
  and the shared contracts Phase 1 must create before parallel feature work.
- `assets/skylink-bingwa-logo-kit/` — approved brand and launcher assets.
- `skylink-bingwa/` — the Android app project (Gradle root; run `./gradlew` here).

---

## 1. Source-of-truth hierarchy

Use each document for its intended authority:

1. The user's current explicit instruction.
2. `Plan.md` for product scope, flows, architecture and version decisions.
3. `design.md` for UI, UX, content, colour, alignment, motion and accessibility.
4. `CLAUDE.md` for the operating process and engineering guardrails.
5. `memory.md` for project state, decisions, execution history and unresolved
   work.
6. `CHANGELOG.md` for concise repository and product changes.
7. Existing code and tests for current implementation facts.

If sources conflict:

- Do not silently choose one.
- Product behaviour follows `Plan.md`.
- Visual behaviour follows `design.md`.
- Record the conflict and its resolution in `memory.md`.
- Ask the user only when the conflict would materially change the product,
  payment behaviour, security or public release.

Do not use `memory.md` to override a newer explicit decision in `Plan.md` or
`design.md`.

---

## 2. Product definition

Skylink Bingwa is a customer-facing native Android app for buying the owner's Bingwa
data, SMS, minutes and special offers.

### Locked version 1 facts

- Android only.
- One seller: the Skylink Bingwa business.
- No customer account, password or OTP.
- First launch collects the customer's name and primary Safaricom number.
- Name, profile, Activity and favourites are local to the installation.
- Customers can buy for their own number or another number.
- The payer's chosen M-Pesa number receives the STK Push.
- Online payment uses M-Pesa STK Push.
- Offline purchase uses valid cached M-Pesa instructions:
  - Till for own-number purchase.
  - Paybill with recipient number as account for another-number purchase.
- The customer app does not deliver bundles.
- The customer app does not verify bundle delivery in version 1.
- A confirmed payment ends at **Payment received** and asks the customer to wait
  for the bundle.
- Fulfilment runs on the owner's agent phone and automation.
- Offers come from manual management plus API synchronisation.
- The app supports search, filters, favourites, quick rebuy, promotions,
  meaningful notifications, support and daily purchase awareness.
- Rewards, referrals, credits and tokens are excluded from version 1.

### Explicit non-goals

Do not build:

- An admin dashboard.
- An agent marketplace.
- Customer sign-up or cloud account sync.
- A mobile-data usage tracker.
- A bundle-size recommendation engine.
- Delivery confirmation or polling.
- Rewards, referrals or tokens in version 1.
- Direct Daraja credentials or fulfilment automation inside the APK.

Do not expand scope because a feature seems useful.

---

## 3. Mandatory first actions for every task

Before editing:

1. Read this file.
2. Read the relevant sections of `Plan.md` and `design.md`.
3. Read the current-state and latest execution sections of `memory.md`.
4. Read `[Unreleased]` in `CHANGELOG.md` if it exists.
5. Inspect repository status, current branch and existing uncommitted changes.
6. Identify the smallest complete interpretation of the user's instruction.
7. Identify affected flows, states, tests and documentation.

Never overwrite or discard unrelated user changes. Never use destructive Git
commands to clean a dirty tree.

Ask a clarification only when:

- Two plausible answers would produce materially different product behaviour.
- Payment, security, privacy or irreversible data handling is ambiguous.
- A required credential, asset, API contract or business value is missing.
- The requested action would destroy, publish or expose something outside the
  granted scope.

Otherwise make the safest reasonable assumption and record it in `memory.md`.

---

## 4. Required execution loop

For every implementation task:

1. Understand the current behaviour and reproduce the issue or state.
2. Define acceptance criteria from the user's request and official documents.
3. Create or switch to a feature branch.
4. Implement the smallest coherent change.
5. Add or update tests.
6. Run the relevant validation suite.
7. Inspect the actual UI or output where the task is visual.
8. Check offline, error, loading, dark-theme and large-text effects where
   relevant.
9. Update `memory.md`.
10. Update `CHANGELOG.md` when the repository changed.
11. Review the final diff for scope creep, secrets and accidental edits.
12. Commit and push the feature branch.
13. Merge and push `main` only after all required gates pass.

Do not stop at “the code compiles” when the requested behaviour has not been
verified.

---

## 5. Engineering architecture

Use the architecture locked in `Plan.md`:

- Kotlin.
- Jetpack Compose and Material 3.
- Single-activity architecture.
- Navigation 3.
- Screen-level ViewModels.
- Unidirectional data flow.
- Coroutines and Flow.
- Hilt dependency injection.
- Retrofit and OkHttp.
- Kotlin serialization.
- Room as the canonical app-readable data source.
- DataStore for profile, theme and lightweight preferences.
- WorkManager for deferred sync and flexible local reminders.
- Firebase Cloud Messaging for remote notifications.

### Data flow

- UI observes local state.
- Repositories refresh network data into Room.
- UI never waits for network before showing valid cached content.
- Network and database models remain separate from UI models.
- ViewModels expose immutable UI state and accept explicit user actions.
- Payment state is a state machine, not scattered Boolean flags.

Do not add dependencies without a real need. Prefer platform and existing
project capabilities. Record every added dependency and its reason in
`memory.md`.

### 5.1 Development-machine constraint

The user's PC does not have Android Studio because the machine cannot run it
comfortably. This is a permanent workflow constraint, not a temporary missing
tool.

- Do not require Android Studio to build, inspect, sign or release Skylink Bingwa.
- Do not tell the user to open an Android Studio project, Device Manager or
  Android Studio emulator.
- Keep the repository fully buildable with the checked-in Gradle wrapper.
- GitHub Actions is the authoritative clean build environment.
- Local command-line tools such as JDK, Android platform tools, `adb` and
  `scrcpy` may be used when available, but the workflow must not depend on them.
- Real visual and interaction testing happens on the user's physical Android
  phone.
- An Android emulator in CI may support automated tests, but it does not replace
  physical-phone acceptance testing.

Every implementation decision must work in this CI-first workflow.

---

## 6. UI and UX rules

`design.md` is mandatory.

### Composition

- Use centre-anchored screen composition.
- Centre heroes, category groups, offer facts, totals, focused payment states,
  short status copy and primary action groups.
- Keep form labels, typed values, long paragraphs, numbered offline
  instructions, Activity rows and Settings rows start-aligned.
- Do not interpret “centred” as centring every paragraph.

### Colour

- Deep action green is the normal primary CTA.
- Bright brand green is an accent, not a white-text button.
- Blue is information, help and the data category.
- Orange is promotion or a real discount, not normal navigation or every Buy
  button.
- Semantic colours are used only for their matching states.
- Do not place white text on bright green, sky blue or orange.
- Never use every colour merely to make a screen look colourful.

### Motion

- Every interactive screen defines entrance, state-change and exit motion.
- Use fast, purposeful Android motion within the durations in `design.md`.
- Animate navigation selection, card selection, favourites, filters, sheets,
  connectivity state, STK processing and final payment state.
- No confetti, bounce, pulsing CTA, parallax or looping promotion.
- Respect reduced motion.
- Motion must remain smooth on low-cost Android devices.

### Visual prohibitions

- No gradients.
- No emojis.
- No glassmorphism.
- No neon glow.
- No oversized shadows.
- No auto-rotating carousels.
- No icon-only primary navigation.
- No multiple dominant CTAs.
- No marketing inside checkout or payment recovery.

Use Material Symbols Rounded or Outlined consistently. Do not mix icon
libraries.

---

## 7. Payment rules

Payment handling is high risk. Never guess.

### Payer and recipient

Always use:

- **Bundle recipient**
- **M-Pesa payment number**

Never label either value as only **Phone number**.

### Online

- Revalidate offer price and availability on the server before STK initiation.
- Use idempotency for order creation and retries.
- Prevent double taps while a request is in flight.
- Restore an active payment after process death or app restart.
- Never optimistically mark a payment successful.
- Never show a fake progress percentage.

### Offline

- Show instructions only from valid, signed cached configuration.
- Never silently switch an online payment to offline instructions.
- Own-number purchases use the configured Till route.
- Another-number purchases use Paybill and recipient account.
- The exact amount must safely identify the selected offer on that route.
- Expired or ambiguous offline configuration disables payment with a clear
  explanation.
- Customer-marked payment becomes **Waiting to verify**, not success.

### Final status

Version 1 may show:

- Payment received.
- Payment failed.
- Payment cancelled.
- Request expired.
- Waiting to verify payment.
- We could not verify this payment.

Version 1 must never show:

- Bundle delivered.
- Data activated.
- Delivery successful.
- Bundle confirmed.

---

## 8. Purchase awareness

Purchase awareness is based only on Skylink Bingwa payment records, recipient and
offer policy.

Allowed policies:

- `MULTIPLE_PER_DAY`
- `ONCE_PER_RECIPIENT_PER_DAY`
- `MAX_PER_RECIPIENT_PER_DAY`

Use the `Africa/Nairobi` day boundary.

Allowed language:

- Bought today.
- Available again tomorrow.
- Two purchases left today.
- More offers you can buy.

Forbidden claims:

- You are running out of data.
- Recommended for your usage.
- Based on your browsing.
- You need more data.

Online backend eligibility is authoritative. Offline local history is not
cross-device truth.

---

## 9. Notification rules

Notifications must be meaningful, optional where promotional, private and
rate-limited.

- Ask Android notification permission only after a clear in-app explanation.
- Separate transaction updates from promotions and reminders.
- Remote FCM messages require connectivity.
- Locally scheduled templates may appear offline.
- Never claim a remote message was delivered while the device had no internet.
- Never send a notification on every connection loss.
- Apply quiet hours, shared campaign caps, recent-purchase suppression and
  local/remote deduplication.
- Prefer silence over a weak message.
- Never expose full phone numbers, M-Pesa receipts, PINs or secrets on the lock
  screen.

Do not create a notification feature without its permission, denied,
suppression, deep-link and expired-content states.

---

## 10. Security and privacy

- Never place Daraja secrets, API secrets, signing keys or automation
  credentials in the APK or repository.
- Never commit `.env`, keystores, private keys, service-account files or local
  secret configuration.
- Keep only public verification keys in the app.
- Use HTTPS.
- Validate prices, offers and order state on the server.
- Encrypt retained sensitive local values using Keystore-backed protection.
- Do not log complete phone numbers, M-Pesa receipts, tokens or payment
  payloads.
- Mask sensitive analytics and crash data.
- Use anonymous installation IDs, not phone numbers, for analytics.
- Release signing uses one permanent identity for Play and direct APK builds.
- Never create, rotate, replace or expose the permanent signing key casually.

If a test needs a secret, use a fake value or injected test configuration.

---

## 11. Performance requirements

Design for Samsung A05/A06-class hardware and comparable Tecno/Infinix devices.

Required targets:

- Cached Home visible within 300ms of screen creation.
- Tap feedback within 100ms.
- Normal transitions within 120–280ms.
- Cold start p75 below 1.5 seconds on a representative low-end device.
- Warm start below 500ms.
- Prefer direct APK below 30MB.

Rules:

- Never block startup on analytics, remote config or notification registration.
- Use lazy lists with stable keys.
- Avoid unnecessary recomposition.
- Use vectors or correctly sized WebP.
- Do not load large promotional imagery.
- Ship and measure a Baseline Profile.
- Run Macrobenchmark on startup and checkout before release.

---

## 12. Testing gates

Run the smallest relevant tests during development and the complete required
gate before merge.

### Required automated coverage

- Unit tests for business rules and state machines.
- Repository tests for offline-first behaviour.
- ViewModel state tests.
- Compose UI tests for changed customer flows.
- Screenshot tests for changed screens.
- Contract tests for affected API behaviour.
- Static analysis, formatting and release compilation.

### Required visual variants

For every changed UI screen, inspect:

- Light theme.
- Dark theme.
- Small supported phone width.
- 200% font scale.
- Online and offline states where relevant.
- Empty, loading, error and success states.
- Reduced-motion end states.

### Payment regression

Payment-related changes must cover:

- Double tap.
- Retry/idempotency.
- Cancellation.
- Timeout.
- App kill and restore.
- Price change before payment.
- Offline configuration expiry.
- Duplicate offline reconciliation.

Never weaken, skip or delete a failing test merely to make CI pass. Fix the
cause or document a genuine test correction.

### 12.1 GitHub Actions build pipeline

The repository must include maintained GitHub Actions workflows that can build
the app from a clean hosted runner without Android Studio.

At minimum, CI must:

1. Check out the repository.
2. Install the required supported JDK.
3. Configure the Android SDK non-interactively.
4. Use the repository Gradle wrapper.
5. Cache safe Gradle dependencies and build data.
6. Run formatting/static analysis, lint and unit tests.
7. Build the appropriate APK or AAB.
8. Upload test reports and correctly named build artifacts.
9. Fail visibly when any required step fails.

Use branch concurrency so a newer build can cancel an obsolete in-progress
build for the same branch.

### 12.2 CI outputs

#### Feature branch and pull request

Build:

- Debug APK.
- Unit-test and lint reports.
- Changed screenshot/UI-test output where available.

Recommended gate:

```text
./gradlew test lint assembleDebug
```

Artifact name:

```text
skylink-bingwa-debug-<short-commit-sha>
```

The uploaded artifact must contain one clearly named APK:

```text
Skylink-Bingwa-Debug-<short-commit-sha>.apk
```

Debug artifacts are temporary testing files, not releases. Retain them for a
defined period such as 14 days to avoid unlimited artifact storage.

#### Protected main branch

After merge, run the full test gate again. Produce release candidates only when
signing secrets are available in the protected workflow environment.

Expected outputs:

- `directRelease` signed APK for direct distribution.
- `playRelease` AAB for Google Play.
- SHA-256 checksum for every directly distributed APK.
- Mapping file and relevant reports retained securely for troubleshooting.

#### Version tag or approved release

A tag such as `v1.0.0` or an explicitly approved manual release workflow may
publish:

```text
Skylink-Bingwa-v1.0.0-direct.apk
Skylink-Bingwa-v1.0.0-direct.apk.sha256
```

The signed direct APK belongs in that version's GitHub Release assets. The Play
AAB belongs in the protected Play publishing flow; do not expose signing
material or sensitive release output unnecessarily.

### 12.3 Where the user gets an APK

For a feature test:

1. Open the Skylink Bingwa repository on GitHub.
2. Open **Actions**.
3. Open the successful workflow run for the feature branch or commit.
4. Find **Artifacts** in the run summary.
5. Download `skylink-bingwa-debug-<short-commit-sha>`.
6. Extract the downloaded archive and install the clearly named debug APK on
   the physical phone.

For a stable direct release:

1. Open the repository's **Releases** page.
2. Open the required version.
3. Download the signed `Skylink-Bingwa-v<version>-direct.apk`.
4. Verify its SHA-256 checksum before installation where practical.

For Play testing or production, install through the configured Google Play
testing or production track.

Every Claude handoff that generated an APK must state:

- Workflow name.
- Branch and commit SHA.
- CI result.
- Exact artifact or release filename.
- Version name and version code.
- Whether it is debug, direct release or Play release.
- What the user should test on the phone.

Never say “download the APK” without identifying its exact source and build.

### 12.4 Debug and release separation

- Debug and release apps must be visually distinguishable.
- Debug builds use an application ID suffix such as `.debug` after the permanent
  production application ID is chosen.
- Debug app label: **Skylink Bingwa Dev**.
- Release app label: **Skylink Bingwa**.
- A debug build must not overwrite or be mistaken for the release app.
- Debug builds never use the permanent production signing key.
- All Play and direct release builds use the same permanent app-signing
  identity.
- `versionCode` always increases for release candidates.
- `versionName` uses semantic versions such as `1.0.0`.

### 12.5 Signing in GitHub Actions

- Generate the permanent signing key once, outside the repository.
- Keep encrypted offline backups and recovery instructions.
- Store the encoded keystore and passwords only in protected GitHub Actions
  secrets or environment secrets.
- Never print, upload as an ordinary artifact or commit the decoded keystore.
- Decode signing material only inside the protected release job and remove the
  temporary file when the job ends.
- Do not make release secrets available to forked pull requests or ordinary
  feature-branch jobs.
- Pin or deliberately version security-sensitive third-party Actions.
- Do not put a long-lived GitHub token inside the APK.

If the signing setup is missing, CI may build an unsigned release candidate for
diagnosis, but it must not label or publish it as an installable production
release.

### 12.6 Physical-phone acceptance loop

For every meaningful UI or behaviour milestone:

1. Push the feature branch.
2. Wait for the GitHub Actions build and tests.
3. Download the debug APK artifact.
4. Install it on the user's physical phone.
5. Test the requested flow, visual layout, animation and important failure
   states.
6. Record the device, Android version, APK commit SHA and result in
   `memory.md`.
7. Fix failures on the same feature branch and repeat.
8. Merge to `main` only after the phone test and required CI gates pass.

If Android platform tools are installed separately, `adb install -r` and
`scrcpy` may speed up installation and viewing without installing Android
Studio. Manual APK installation remains a supported fallback.

---

## 13. Git workflow

### Branch policy

- Never implement directly on `main`.
- Start from an up-to-date `main`.
- Use a focused branch:
  - `feature/<short-name>`
  - `fix/<short-name>`
  - `docs/<short-name>`
  - `chore/<short-name>`
- Preserve unrelated dirty-tree changes.
- Never use `git reset --hard`, destructive checkout or force push.

### Commit policy

- Use small, coherent commits.
- Use conventional subjects such as:
  - `feat: add offline purchase instructions`
  - `fix: restore pending stk state`
  - `docs: update checkout design rules`
- Do not commit generated build output, secrets or local machine files.
- Include tests and documentation with the change they describe.

### Push and merge policy

After implementation:

1. Run the required tests and verification.
2. Update `memory.md` and `CHANGELOG.md`.
3. Confirm the diff contains only intended changes.
4. Commit.
5. Push the feature branch.
6. Merge through the repository's normal protected-branch or pull-request
   workflow.
7. Push `main` only after the merge gates pass.
8. Record branch, commit and merge result in `memory.md`.

For Android implementation, a green source-code diff is not enough. The feature
branch must also produce the expected GitHub Actions APK artifact, and
meaningful UI work must complete the physical-phone acceptance loop before
merge.

Do not bypass branch protection. Do not claim a push, test or merge succeeded
without command or CI evidence. If repository permissions, CI or review block
the merge, stop at the safely pushed feature branch and report the exact
blocker.

Planning, review-only and diagnostic requests do not authorize a merge.

---

## 14. Documentation discipline

### `memory.md`

Update after every Claude execution, including:

- Implementation.
- Documentation work.
- Investigation.
- Test-only work.
- A blocked or no-change execution.

Do not paste raw terminal logs. Record the useful facts required to continue the
project accurately.

Every entry must include:

- Date and time in Africa/Nairobi.
- Request/objective.
- What was changed or learned.
- Files changed.
- Decisions and assumptions.
- Tests or checks and their results.
- Git branch/commit/push/merge state.
- Blockers or risks.
- Exact next step.

Also update the current-state section whenever scope, architecture, design,
dependencies, APIs or project phase changes.

### `CHANGELOG.md`

Maintain a Keep-a-Changelog style `[Unreleased]` section.

Update it after every repository-changing execution:

- `Added`
- `Changed`
- `Fixed`
- `Removed`
- `Security`
- `Internal` when a repository change has no customer-visible effect

A read-only investigation or no-op execution is recorded in `memory.md` only;
do not invent a changelog change that did not happen.

### Documentation truth

- Do not mark incomplete work complete.
- Do not record planned tests as passed tests.
- Do not hide failed checks.
- Do not store secrets, PINs, full payment receipts or credentials in either
  document.
- Update existing entries only to correct factual errors; otherwise append
  history.

---

## 15. Definition of done

A task is done only when:

- The requested behaviour is complete.
- Scope matches `Plan.md`.
- UI matches `design.md`.
- Relevant offline and failure states work.
- Relevant tests pass.
- Visual changes were inspected.
- Accessibility was checked.
- No secrets or unrelated changes are present.
- `memory.md` is updated.
- `CHANGELOG.md` is updated when the repository changed.
- Feature branch is committed and pushed.
- `main` is merged and pushed only when authorised by the task and all gates
  pass.
- The final report states what changed, what was tested and any remaining
  limitation.

If any item is not complete, report the work as partial or blocked.

---

## 16. Final prohibitions

Never:

- Invent delivery confirmation.
- Add a hidden account system.
- Build data-usage recommendations.
- Expose secrets.
- Guess payment success.
- Destroy user work.
- Work around failing tests by disabling them.
- push unfinished or untested work to `main`.
- Force push.
- Fabricate test, Git, deployment or payment results.
- Skip memory documentation.
- Allow a visual demo to silently redefine product behaviour.
