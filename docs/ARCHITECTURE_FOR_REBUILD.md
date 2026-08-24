# How Skylink Bingwa Works — A Rebuild Guide

This explains three subsystems of the Skylink Bingwa app in plain language, for a developer who wants to rebuild similar features in a different project.

**Correcting a starting assumption first:** this is not a React Native / Expo app. It's a **native Android app written in Kotlin with Jetpack Compose** (no JavaScript layer at all). That changes the vocabulary for two of your questions — there's no "server sending push notifications," no Expo config plugins, no boot receivers written in JS. Everything below describes the real Android mechanisms in place of those.

It's also worth knowing up front that this app is mid-migration: some things you asked about (a daily 6 AM reminder, name-personalized notifications, a real incremental sync protocol) are **designed and documented but not yet built**. I've marked those clearly below so you don't try to copy code that doesn't exist yet — instead you'd be building them fresh, and I explain how.

---

## 1. Automatic Notifications

### The important correction: there is no server pushing notifications, and no daily clock-based reminder today

There's no Firebase Cloud Messaging, no push service, and — despite what you might expect from an app that clearly intends to remind users — **no time-of-day scheduled notification exists in the current code** (no "every day at 6 AM" job). All notifications that exist today are **triggered by something happening**, not by the clock.

### The actual mechanism: reading the user's own incoming SMS

This is the clever part, and it's the real answer to "how does the app generate notifications without any admin panel or server sending them":

The app **listens for incoming SMS messages** on the phone. Android lets an app register to be woken up whenever any SMS arrives, system-wide, without the app needing to be open. When Safaricom sends the user an SMS (e.g. "You have received 500MB from Bingwa Sokoni" or "Your data balance is low"), the app intercepts that broadcast, reads the sender and message text, and checks it against a list of known message shapes.

Those known shapes are just **regular expression patterns** — one for "a bundle delivery message from Safaricom," another for "a low-balance message from Safaricom." If the incoming SMS matches one of these patterns, the app now knows something meaningful happened (bundle delivered, balance getting low) — entirely by reading a text message its own phone already received, with no network call and no backend involvement at all.

Once a match is classified, it's published on an internal in-app event pipe. The main screen is listening on that pipe, and reacts by:
- **On a delivery match:** quietly marking the matching pending purchase as "delivery confirmed" in the app's own records — no system notification banner, just an internal state update.
- **On a low-balance match:** posting a real, visible system notification suggesting the user top up, *and* recording an in-app message in the notification center.

So "automatic notifications" in this app really means: **the app is a smart SMS listener that translates the carrier's own text messages into either quiet app-state updates or genuine push-style banners** — not a scheduling system.

### Where the templates live

The regex patterns ("templates") are **hardcoded inside the app itself**, defined as plain data structures — an id, which sender they apply to (e.g. "Safaricom"), a category (data/SMS/minutes/low-balance), and the regex pattern to test the message body against. They ship baked into the app binary; they are not fetched from any server today.

That said, the backend already has unused endpoints prepared for a future version where these templates could be updated remotely (so patterns could be tweaked without shipping a new app version if Safaricom ever changes its message wording) — but the app doesn't call them yet. If you're rebuilding this, you have a choice: hardcode the patterns for simplicity (like this app does now), or fetch them from a config endpoint on startup for future flexibility.

### How a daily reminder *would* be scheduled (since none exists yet)

If you want to add a genuine "remind the user every day at 6 AM" feature, here's the mechanism this app's own design documents point to, and how you'd build it on Android generally:

- **Option A — WorkManager periodic job:** schedule a background job to run roughly once a day. Android's WorkManager doesn't guarantee an exact clock time (it's designed to be battery-friendly and flexible), so this is good for "check something and maybe notify" but not for a precise 6:00:00 AM alarm.
- **Option B — AlarmManager exact alarm:** if you need a genuinely precise daily time, you schedule an exact alarm for that time. The catch: **exact alarms do not survive a phone restart** — the OS wipes them on reboot. To fix that, you register a small receiver that listens for the "device finished booting" system broadcast, and when it fires, your code re-schedules the next day's alarm. This boot-receiver pattern is the standard Android answer to "how do I survive a restart."

The SMS listener described above needs none of this, which is worth understanding: because it's registered directly in the app's manifest (not created dynamically at runtime), Android automatically re-arms it after every reboot with zero extra code — reboot survival is a freebie you only get for manifest-declared, event-based receivers, not for time-based alarms.

### Personalization (using the user's name)

**This doesn't happen today.** The user's name — collected once at first launch — is stored in the local profile and is only ever used in two places: the Settings screen (to show/edit it) and a greeting on the Home screen ("Good morning, Jane"). It is never inserted into any notification text; all notification copy is fixed, generic English strings (e.g. "Safaricom just messaged you about your data bundle").

If you want to personalize notifications in your rebuild, the pattern is simple: read the stored profile object at the moment you're about to build the notification text, and substitute the name into a template string (e.g. `"Hey {name}, ..."` → `"Hey Jane, ..."`), falling back to a generic word like "there" or "Customer" if no name is stored. The app already does exactly this fallback pattern for the Home screen greeting, so that's the template to imitate.

### The notification "channels"

Android groups notifications into categories called channels, each with its own importance level that the user can independently mute in system settings. This app defines four: **transactions** (payment/delivery updates — allowed to alert normally), **offers**, **reminders**, and **updates** (the latter three are set to low-importance/silent, so they show up in the notification shade without buzzing the phone). This separation matters for your rebuild too — mixing "your payment succeeded" with "here's a promo" in the same channel means a user muting promos also mutes their payment confirmations.

### One more real trigger: app-update notice

Every time the app starts, it checks whether a newer version is available and, if so, posts an "update available" notification. This is launch-triggered, not clock-triggered — worth knowing since it's the only other "automatic-feeling" notification besides the SMS ones.

---

## 2. Offers Synchronization

### The model in one sentence

The app doesn't do smart incremental syncing today — it does **"fetch the entire current offer list, validate it, and if it looks good, replace everything I have locally."** There's no delta/diff logic, no ETags, no version comparison against the server on the wire. Since you're planning to swap the backend for a database, this matters: you can keep this same simple replace-everything approach, or upgrade to a real incremental protocol (there's already a documented plan for one — see the note near the end of this section).

### Step by step, what actually happens

1. **Something triggers a sync check** (see triggers below).
2. The app makes a plain network request that returns the **complete current list of offers** as JSON — no filtering, no "since last time," just everything currently published.
3. If the request fails, times out, or throws any error, the app **silently keeps whatever offers it already has locally** and does nothing else. Nothing is ever left blank because of a failed sync.
4. If the request succeeds, the app runs a **validation pass over every single offer** in the response: each one must have a real ID, a real name, a real validity period, a price greater than zero, and a real category. If the returned list is empty, or **even one single offer in the list fails this check**, the app throws away the *entire* incoming batch and keeps the old data. This is a deliberate "all or nothing" safety rule — the reasoning is that a partially-corrupt catalogue (say, one offer with a null price due to a server bug) is worse than no update at all, since a broken offer could let someone buy something at the wrong price or with no valid ID to reference.
5. If everything passes validation, the app builds the new list — but for each offer, it **carries forward two purely local pieces of state that don't come from the server at all**: whether the user has favourited it, and whether they've already bought it today. It does this by matching offers by ID between the old list and the new list and copying those two flags across. This is important: without this step, every sync would wipe out the user's favourites and "already bought today" markers, which would be a confusing experience.
6. The new list is saved to local storage (see below), completely replacing the old one.
7. A local counter is bumped by one to record that "a real sync happened." This counter is not compared against anything on the server — it's just internal bookkeeping for now (a placeholder for a real version-number system, described below).

The exact same "fetch whole thing → fail-safe on error → validate all-or-nothing → replace" shape is reused for two other pieces of remote-managed content in this app: promotional banners, and general app configuration (support numbers, till/paybill numbers, etc.) — so if you rebuild one of these, you get the pattern for all three for free.

### What triggers a sync

Three independent things all call the same "sync everything" logic:

1. **Network connectivity changes / the app comes to the foreground.** The app is continuously watching the device's network state. The moment the phone has any usable connection (including right when the app screen first appears), it fires off a sync attempt automatically. This is the main "automatic" trigger — the user doesn't have to do anything.
2. **A periodic background job**, scheduled to run roughly every 6 hours regardless of whether the app is even open, as long as the phone has a network connection at the time. This job is scheduled once when the app process starts, using a "don't double-schedule if one's already pending" safety flag, so restarting the app repeatedly doesn't stack up duplicate jobs. This is what keeps offers reasonably fresh even for a user who rarely opens the app.
3. **Pull-to-refresh**, with an important caveat for your rebuild: in the current app, pull-to-refresh does **not** actually hit the network. It just re-shuffles the offers the app already has in memory, mainly to recover from a local UI glitch or to re-apply local favourite state. If you want a "real" manual refresh in your rebuild, make sure it actually calls the network fetch — don't copy this particular shortcut.

There is no notification-triggered sync (no "silent push wakes the app up to sync" pattern) — that would require a push messaging service, which isn't wired in.

### Where offers are stored locally

Everything the app needs to remember between launches — the user's profile, theme choice, favourite offer IDs, which offers were bought today, purchase history, notification history, recently-used phone numbers, an in-progress order, **and the synced offers list itself** — is stored as **one single JSON document** in a lightweight key-value local storage layer (Android's Preferences DataStore, the modern replacement for SharedPreferences). There is no per-offer database table, no rows you can query individually — the whole state gets serialized to JSON and the entire document is rewritten every time anything changes.

Two details worth knowing if you're replacing this with a real database:
- The favourite/bought-today flags are deliberately **stripped out of the stored offer objects** and stored as separate ID lists instead, then re-merged back onto the offers when the app reads its saved state back. This is the same "carry local state across a wholesale replacement" trick mentioned in step 5 above — it's what makes it safe to overwrite the entire offers list without losing favourites.
- On first-ever launch, there's a flag distinguishing "this is a fresh install, so seed some demo offers" from "the user or app explicitly cleared everything, so stay empty" — worth keeping if you don't want a wiped database to accidentally look "unconfigured" to your app.

### Avoiding downloading everything every time — what's real vs. what's planned

Today: it genuinely **does** download everything, every single sync (no incremental fetch exists). The safety net against wasted bandwidth is just the sync *frequency* (foreground trigger + 6-hourly job) rather than the payload size.

However, there's already a fully designed (but not yet wired into the app) **versioned sync protocol** worth adopting when you build your own database-backed version:
- The server exposes a small "manifest" endpoint that just returns a version number, a timestamp, and a checksum/hash of the full data snapshot for that version — this is a tiny, cheap request.
- The app compares that version number to the version it last saved locally. If it's unchanged, the app can stop right there (or the server can simply reply "not modified" to a conditional request, the standard HTTP pattern where the client says "only send me data if it's changed since version X").
- Only if the version actually increased does the app fetch the full data snapshot for that new version, verify its checksum matches (protecting against a corrupted or tampered download), and then do the same atomic "replace everything" swap described above.

This gives you real incremental behavior — cheap "is anything new?" checks most of the time, full downloads only when something actually changed — while keeping the same simple "replace the whole local copy atomically" mental model you already have. Since you're moving off the current backend anyway, this is the point worth designing in from day one rather than retrofitting later.

---

## 3. Buying Logic (Purchase Flow)

### How the purchase screen is opened and how the offer travels through it

When a user taps "Buy" on an offer, the app doesn't navigate to a separate screen in the traditional sense — it opens a **bottom sheet** (a panel that slides up over the current screen) and simply hands it the selected offer object directly as a parameter. There's no global "currently selected offer" store and no navigation-route parameter passing — it's the simplest possible approach: the parent screen holds "which offer is currently open for purchase, if any" as a small piece of local state, and passes that offer straight into the bottom sheet's inputs. The bottom sheet re-reads the live version of that offer from the current catalogue on every screen update, so if something changes (like the user favouriting it) while the sheet is open, it stays in sync.

Internally, the purchase bottom sheet moves through four steps in sequence: **choose recipient → review and confirm → processing → result**, with a fifth branch for the offline/manual-payment path. This is tracked with simple local step state, not a formal navigation stack.

### Step 1 — Buying for yourself vs. buying for another number

The screen presents two selectable options: "For my number" and "For another number." This is a single yes/no switch that changes what the rest of the form looks like:

- **For my number:** both the "who receives the bundle" number and the "which M-Pesa line pays for it" number are automatically set to the user's own saved phone number (collected once at profile setup) and shown as **read-only** — there's nothing to type.
- **For another number:** two separate editable fields appear — the **bundle recipient's number** (who gets the data/SMS/minutes) and the **M-Pesa payment number** (which line the STK prompt goes to). These are allowed to be different numbers on purpose — you might buy a bundle for a family member's phone but pay from your own M-Pesa line. To speed this up, the app also shows a row of "recently used numbers" as tappable chips, built from a small history of numbers the user has previously sent bundles to.

### Where validation happens

Phone number validation is a single, self-contained rule used everywhere a number needs checking: strip out anything that isn't a digit, then accept any of the common Kenyan formats (starting with the country code, starting with a leading zero, or just the bare 9-digit number), and require that after normalizing, the number's local part starts with a digit that's a valid Safaricom mobile prefix. If it doesn't pass, it's rejected — the "Confirm" button on the recipient step stays disabled until **both** the recipient number and the payer number pass this check. There's no live carrier lookup or SMS-based number verification; it's a pure formatting/prefix rule, and it deliberately only recognizes Safaricom-shaped numbers (this app only deals in Safaricom bundles).

Beyond phone validation, there's a second validation layer for offline/manual payments specifically: before the app will show cash/manual-payment instructions, it checks whether that particular offer is even eligible to be paid that way right now — for example, some offers can only be verified through the automatic online flow because they can be bought multiple times a day and there'd be no reliable way to match a manually-reported payment back to the right purchase; others get blocked if two different offers happen to cost exactly the same amount (since offline verification is partly based on matching the exact shilling amount paid, and an ambiguous amount can't be safely resolved).

### Step 2 — Review, then the actual payment call

The review step just summarizes everything: offer name, validity, who's receiving it, which number is paying, and the total price, with one big "Pay" button for the online path (or a link to view step-by-step instructions for the offline path).

When "Pay" is tapped for the online path, here's the full sequence:

1. A brand-new unique request ID is generated for this specific attempt (this is the mechanism that prevents a user's double-tap, or a network retry, from accidentally charging them twice — every attempt gets its own fingerprint, and both the app and the server refuse to process the same fingerprint twice).
2. Both phone numbers (payer and recipient) are normalized into the exact digit format the payment network expects. If either one somehow fails this normalization, the app fails immediately without even attempting a network call.
3. Before any network request goes out, the app **saves a record of this in-progress order to local storage first**. This is specifically so that if the app crashes, gets killed by the OS, or the user force-closes it while waiting on the payment prompt, the next time the app opens it can recognize "there was a payment in progress" and show an honest "still waiting to be verified" state — rather than either silently losing track of it or, worse, letting the user think it's safe to try paying again.
4. The app sends a request containing: which offer was selected, the price it believes the offer costs, the payer's number, the recipient's number, the unique request ID from step 1, and a flag indicating whether this is a "for myself" or "for another number" purchase.
5. **Here's the important architectural decision for the number-routing question you asked:** the app does *not* decide which payment number/account the money should be sent to based on self-vs-other — it just sends the "is this for myself" flag along with both numbers, and the **server** is the one that decides the destination business number and payment reference:
   - If it's for the user's own number, the server directs the payment prompt to the business's fixed "Till" number, tagged with a fixed account reference (basically the business's own name/brand tag) — because with a Till, there's no need to distinguish who it's for.
   - If it's for another number, the server instead directs the prompt to the business's "Paybill" number, and — critically — uses the **recipient's own phone number as the account reference** for that payment. This means when the business owner looks at their M-Pesa statement, a Paybill payment automatically shows which customer phone number the bundle needs to be sent to, purely from the transaction record itself, without needing any other lookup.
6. The server does **not** trust the price the app sent — it looks up the offer ID in its own authoritative price list and uses that instead. This protects against someone tampering with the app's request to pay less than the real price.
7. The server also enforces the double-charge protection at the database level: it tries to insert a row for that unique request ID with a uniqueness constraint *before* even talking to the payment provider, so if two identical requests land at the same instant (e.g. a flaky retry), only one of them actually triggers a real payment prompt — the second one just gets told about the first one's outcome.
8. Once the payment request is accepted, the app **polls for status periodically** (every few seconds, for up to about half a minute) waiting for the user to approve the prompt on their phone, showing a "check your phone" message, and offering a way to resend the prompt if it's taking a while.
9. When a final answer comes back (approved, cancelled, failed, or timed out), the app records the outcome, and — only on real confirmed success — marks the offer as "bought today" if it's a once-a-day offer, adds a notification to the in-app notification list, and (if this number hasn't been used before) adds it to the recent-recipients list for next time.

### The offline/manual path, briefly

If the user instead chooses to pay by manually going into their M-Pesa menu (useful when the automatic prompt isn't working, or for cash-style flows), the app shows the correct Till or Paybill number plus, for the "another number" case, the exact same "use the recipient's number as the account reference" instruction, along with the exact amount to pay. The user is asked to report back an M-Pesa confirmation code once they've paid. Critically, this path **never marks a purchase as fully successful on the device** — it can only ever end up as "waiting to be verified" or "not yet confirmed," because nobody but the actual M-Pesa system can prove the payment really went through; the app is just recording that the user *claims* to have paid.

### Summary of what "the offer" carries through the whole flow

The offer object picked at the very start (id, name, price, validity, category, and its daily-purchase rule) rides along as a plain in-memory value the whole way through the bottom sheet's four steps — it's never re-fetched or looked up again until the very end, when the server independently re-derives the true price from the offer's ID as a trust boundary. The phone numbers, by contrast, start as free-text/selected strings and only get normalized into the strict payment-ready format right before the network call — keeping the loose "whatever the user typed" and the strict "what the payment API accepts" concerns cleanly separate is the detail most worth copying into a rebuild.

---

## Key takeaways if you're rebuilding these features elsewhere

- **Notifications:** the "automatic" magic here is an SMS listener + regex classifier, not a scheduler. If you want a real clock-based daily reminder, you need to build it from scratch (WorkManager for a loose daily check, or AlarmManager + a boot-listener for a precise time) — nothing here does that today. Personalization is a one-line addition (read the stored name, substitute into the template, fall back to a generic word) that simply hasn't been done yet.
- **Sync:** the safe pattern to copy is *fetch → on any failure keep the old data → validate every record before accepting any of it → merge in local-only flags by ID → replace atomically → persist*. The "download everything every time" behavior is a simplification you can improve on immediately by adding a version/checksum manifest check before pulling the full payload — the design for that already exists, just not the app-side wiring.
- **Buying:** keep the "payer number" and "recipient number" as two genuinely separate fields even when they're usually the same value, generate a fresh idempotency key per payment attempt, persist the in-progress order to disk *before* calling the network (not after), and put the routing/business-number decision and the authoritative price on the server side — never trust the client for either.
