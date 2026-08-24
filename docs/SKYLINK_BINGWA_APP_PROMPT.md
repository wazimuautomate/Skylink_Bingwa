Background

You are taking over development of Skylink Bingwa, an Android application for purchasing Safaricom Bingwa bundles (Data, SMS and Minutes).

The application is already functional and has completed its first round of testing with real users.

The purpose of this phase is NOT to redesign the application.

The goal is to transform the existing app into a production-ready application by improving reliability, personalization, offline experience and automation while preserving all existing functionality.

The application is intentionally designed as an offline-first application.

Unlike most apps, users continue interacting with Skylink Bingwa even when internet connectivity disappears.

Whenever the user loses internet, the application should immediately switch into Offline Mode instead of becoming unusable.

Whenever internet returns, everything should automatically synchronize in the background without disturbing the user.

The user should rarely notice synchronization happening.

Existing Features

These already exist.

Do NOT remove or break them.

Buying bundles
Buying SMS
Buying minutes
Buying for another number
Purchase history
Favorites
Offline purchase mode
Billboards
Local database
Sync engine
Search
Onboarding
Payment details
Support details

You may refactor internal implementation where necessary but preserve user behaviour.

General Development Principles

This application should feel:

Personal
Intelligent
Helpful
Fast
Calm

Never spam users.

Never overwhelm users.

Every notification should feel like the application actually understands the user.

The application should feel more like a personal assistant than a store.

FEATURE 1
Complete Push Notification System

This is one of the largest improvements.

Currently the application barely uses notifications.

That changes now.

We want notifications to become one of the strongest features in the application.

Notifications should work in two ways:

A. Local Notifications

Generated entirely on the device.

No internet required.

Examples:

User has gone offline.
Internet has returned.
User's data is almost finished.
User has no data.
User's SMS bundle is almost over.
User's minutes are almost finished.
User has received purchased bundles.
User has received gifted bundles.
User has not bought bundles for several days.
User normally buys bundles every evening but forgot today.

The app should intelligently determine when a notification is useful.

Never send notifications every few minutes.

Use cooldown periods.

Randomize notification wording.

Notification Personality

Notifications should never sound robotic.

Bad:

Low Data Remaining.

Good:

James, you're almost out of data.

Better:

James, looks like Instagram has almost finished today's bundle 😄

Better:

Morning James ☀️
Looks like you're offline. Let's fix that.

The user should smile.

Personalization

Always use:

User Name

Time of day

Recent activity

Previous purchases

Examples:

Morning

Good morning James.

Afternoon

Good afternoon James.

Evening

Good evening James.

Late Night

Still awake James? Need another bundle?

Never repeat identical messages repeatedly.

Implement multiple templates.

Choose randomly.

Bundle Suggestions

Notifications should recommend bundles intelligently.

Example

If user usually buys

Sh20 = 250MB

recommend that first.

If user usually buys hourly bundles

recommend hourly bundles.

If user always buys SMS

recommend SMS.

Do not randomly suggest offers.

Use local behaviour.

Offline Notifications

One important observation from testing:

Users love when the application notices they are offline.

Therefore:

When internet disappears:

Within a few seconds

display notification.

Example

James,
Looks like you're offline.

Need another bundle?

The application should not wait until the next launch.

It must react immediately.

Online Notifications

When internet returns

Example

You're back online 🎉

We've refreshed today's offers for you.

Remote Notifications

Besides local notifications

Admin should also be able to publish notifications.

The application downloads them during synchronization.

These notifications are stored locally.

The application decides when to display them.

If the device later goes offline

the cached notifications still work.

Notification Categories

Support categories such as

Offline

Online

Morning

Afternoon

Evening

Purchase Success

Low Data

No Data

Low SMS

Low Minutes

Bundle Received

Gift Received

Promotions

General Information

Cooldown

Prevent spam.

Examples

Offline notification

Maximum once every several hours.

Low Data

Don't repeatedly notify every SMS received.

Implement cooldown logic.

FEATURE 2
Dynamic SMS Detection Engine

Current implementation is too static.

It recognizes a few messages by hardcoding.

This must be completely redesigned.

The application should no longer understand Safaricom messages because they are hardcoded.

Instead

it should understand them because the server teaches it.

Why

Safaricom frequently changes:

Sender IDs

Message wording

Formatting

Bundle names

Hardcoding requires app updates.

We don't want that.

Instead

the admin dashboard should define everything.

Server Will Provide

Each SMS Rule contains

Rule Name

Sender ID

Message Pattern

Event Type

Priority

Enabled

Description

The application downloads these rules.

Stores them locally.

Uses them when SMS arrives.

Supported Sender IDs

The first implementation should support these known senders.

Sender ID

Safaricom

SAF_Balance

SAF_OfaMOTO

These should not be hardcoded forever.

They should simply be default rules already available on the server.

Future sender IDs should require only server changes.

Current Safaricom Messages

These are the messages currently observed.

They should be entered into the server as initial rules.

Sender

Safaricom

Received SMS

You have received 20 SMS Daily SMS Bundle. Expiry date: 22/05/2026 07:39AM.

Interpretation

EVENT_SMS_RECEIVED

Action

Update bundle status

Show success notification

Refresh dashboard

Sender

Safaricom

Received Data

You have received Sh20=250MB 24hr from Bingwa Sokoni. Dial 4449# to check balance. Dial *444# for other exciting offers.

Interpretation

EVENT_DATA_RECEIVED

Sender

Safaricom

Received Hourly Bundle

Thank You For Purchasing 1024.0 MB hourly data bundle.

Expiry date 29-07-2026 09:57PM.

Drama iko real...

Interpretation

EVENT_DATA_RECEIVED

Extract

1024MB

Expiry

Bundle Type

Hourly

Sender

Safaricom

Minutes

You have received 45 Talkmore Minutes.

Expiry date...

Interpretation

EVENT_MINUTES_RECEIVED

Sender

Safaricom

Easy Talk

You have received Easy-Talk 50 Minutes + 50 SMS.

Interpretation

EVENT_MINUTES_RECEIVED

EVENT_SMS_RECEIVED

Both resources were received.

Sender

SAF_Balance

Low Data

Dear customer, your deal of the day data balance is 75MBs.

Interpretation

EVENT_LOW_DATA

Action

Update remaining balance.

Recommend next purchase.

Very Low Data

Dear customer,

your deal of the day data balance is below 2MBs.

Interpretation

EVENT_VERY_LOW_DATA

Immediately notify user.

Suggest purchase.

No Active Bundle

Dear customer,

you do not have an active data bundle.

Interpretation

EVENT_NO_DATA

Highest priority.

Application should immediately switch user into Offline Experience.

Display intelligent notification.

Recommend bundle.

Low Minutes

Your Easy-Talk Minutes are below 10 Minutes.

Interpretation

EVENT_LOW_MINUTES

Suggest Easy Talk renewal.

Sender

SAF_OfaMOTO

Gift Received

You have received a gift of Sh20=43 Mins...

Interpretation

EVENT_GIFT_RECEIVED

Show congratulation notification.

Update local bundle status.

Unknown Messages

If SMS does not match any rule

Ignore.

Do not crash.

Do not notify.

Simply continue.

SMS Parser

The parser should support:

Regular Expressions

Keyword matching

Template matching

Future extensibility.

Server should be able to introduce new rules without app updates.

Runtime Permissions

READ_SMS permission should no longer be requested inside Settings.

Instead

during onboarding

Explain WHY.

Example

Skylink Bingwa reads only Safaricom bundle messages so it can notify you when your bundles are running low and automatically update your bundle status.

We never upload or store your SMS.

Everything stays on your phone.

If user declines

Continue.

Disable SMS-powered features only.

Never block the app.

FEATURE 3
Internet Detection

Current implementation is poor.

Users reported

they must close the app

then reopen it

before Offline Mode appears.

This is unacceptable.

Rewrite internet monitoring.

The application should instantly detect:

Internet lost

Internet restored

WiFi changes

Mobile data changes

No restart required.

No refresh required.

No manual action required.

Immediately transition between:

Online Experience

Offline Experience

without delay.

Do not simply check whether a network exists.

Verify actual internet availability where appropriate to avoid false positives.

END OF PART 1A

The next part (Part 1B) will cover:

Complete Sync Engine redesign
Force Sync architecture
Local-first personalization engine
Billboard image/GIF support
GitHub update changes
Local analytics (privacy-preserving)
Performance improvements
Testing requirements
Final deliverables

# SKYLINK BINGWA - PRODUCTION RELEASE IMPLEMENTATION

## PART 1B - ANDROID APPLICATION IMPLEMENTATION (Continuation)

Continue from Part 1A. Everything below builds on the previous requirements.

---

# FEATURE 4

# Complete Sync Engine Redesign

The sync engine is the backbone of the application.

The application is **offline-first**, meaning the server is only responsible for feeding the application with new content and configuration. Once data has been synchronized, the application should continue working normally even when internet disappears.

The user should almost never notice synchronization happening.

---

## Current Problem

The current sync implementation works but is basic.

It downloads data periodically but is not intelligent enough.

It also doesn't immediately respond when critical server changes are published.

We need a production-grade synchronization engine.

---

# Synchronization Principles

Whenever internet is available, the application should silently synchronize:

* Offers
* Categories
* Billboard adverts
* Notification templates
* SMS detection rules
* Payment details
* Support details
* Configuration
* App settings
* Feature flags
* Any future resources

Synchronization must happen silently in the background.

Never interrupt the user.

Never show unnecessary loading screens.

---

# Incremental Synchronization

The application should no longer download everything every time.

Instead:

Each resource should have:

* Version
* Last Updated timestamp
* Checksum (or equivalent lightweight change detection)

When syncing:

If server version equals local version

→ Skip downloading.

If different

→ Download only that resource.

For example:

Offers changed

Download Offers only.

Payment details changed

Download Payment Details only.

Notification templates changed

Download Notification Templates only.

Avoid unnecessary bandwidth usage.

---

# Sync Triggers

Synchronization should occur when:

* App starts
* Internet becomes available
* Periodically in background
* User manually refreshes
* Admin publishes changes (force sync)
* App resumes after being in background for a long time

The app should intelligently throttle sync frequency to avoid unnecessary battery usage.

---

# Force Sync

This is extremely important.

The admin dashboard has a **Preview & Publish** system.

When the admin publishes changes, the app must receive them as quickly as possible.

Example:

The displayed Paybill or Till number is no longer working.

The admin replaces it with a new one.

Within a short time, all online users should automatically receive the updated payment details.

No Play Store update.

No reinstall.

No manual refresh.

No cache clearing.

Just synchronize.

Design the sync architecture to support this.

---

# Conflict Resolution

The server is the source of truth for configuration.

The device is the source of truth for user behaviour and personalization.

Never overwrite:

* Purchase history
* Favourite bundles
* Personal recommendations
* Behaviour history

These remain local.

Only synchronize shared application content.

---

# Offline Safety

If synchronization fails:

Keep existing local data.

Never leave the application empty.

Never delete existing cached content because the network failed.

Old content is better than no content.

---

# FEATURE 5

# Local Personalization Engine

One of the biggest goals of the production version is making every user feel that the application knows them personally.

This personalization must happen entirely on-device.

No behavioural data should ever be uploaded to our servers.

If the user clears app data or uninstalls the app, all personalization data disappears.

---

## Data to Learn

The application should gradually learn patterns such as:

### Purchase Behaviour

* Most purchased bundle
* Most purchased amount
* Favourite category (Data, SMS, Minutes)
* Preferred bundle duration
* Favourite hourly bundle
* Favourite daily bundle

---

### Time Behaviour

Determine:

Morning buyer

Afternoon buyer

Evening buyer

Night buyer

For example:

If a user almost always buys bundles between 7:00 PM and 9:00 PM, the application should recognize this.

---

### Payment Behaviour

Remember:

Preferred payment phone number.

Users rarely change this.

Auto-suggest it next time.

---

### Recipient Behaviour

Track:

Frequently used recipient numbers.

Instead of forcing users to type numbers repeatedly, intelligently suggest the most common recipients.

---

### Purchase Frequency

Examples:

Daily buyer

Weekend buyer

Occasional buyer

Heavy buyer

This should influence recommendations and notifications.

---

# Homepage Personalization

The homepage should not always show identical ordering.

Prioritize:

* Frequently bought bundles
* Recently bought bundles
* Favourite bundles
* Time-appropriate bundles

Example:

If the user always buys Sh20 Data,

that bundle should naturally appear higher.

---

# Intelligent Suggestions

Suggestions should never be random.

Examples:

Instead of

"Recommended"

Use

"Buy Again"

or

"Your Usual Bundle"

or

"Bought Yesterday"

The application should feel familiar.

---

# Notification Personalization

Use learned behaviour.

Example:

The user normally buys at 8 PM.

It's now 8:15 PM.

No purchase has happened.

The app might gently remind them.

Do not overdo this.

Respect cooldown periods.

---

# Privacy

This is extremely important.

Nothing from personalization should ever be uploaded.

No analytics.

No purchase behaviour.

No favourite bundles.

No phone numbers.

Everything remains inside local storage.

The server must never know user habits.

---

# FEATURE 6

# Billboard Improvements

Billboards already exist but are currently text-only.

This greatly limits engagement.

---

## Supported Media

Add support for:

* Images
* Animated GIFs
* Click actions
* External links (where appropriate)
* Internal navigation
* Scheduling
* Priority ordering

---

## Offline Behaviour

When synchronized,

billboards should be stored locally.

If internet disappears,

billboards should continue working normally.

No blank spaces.

---

## Caching

Media should be downloaded once.

Use efficient caching.

Only re-download when the server version changes.

---

## Personalization

Billboards may later be filtered using local behaviour.

Example:

Heavy SMS buyers should see SMS promotions more often.

Heavy Data buyers should prioritize data promotions.

This decision happens locally.

The server only provides billboard metadata.

---

# FEATURE 7

# GitHub Update Changes

The application currently supports updates from:

GitHub

and

Play Store.

For production this creates unnecessary complexity.

---

## Required Behaviour

Debug Builds

Keep GitHub updater enabled.

Release Builds

Disable GitHub updater.

Only Play Store updates should remain.

Do not remove the GitHub implementation completely.

Use build configuration or feature flags so it can easily be re-enabled if Play Store distribution changes in the future.

---

# FEATURE 8

# Onboarding Changes

Currently permissions are requested later from Settings.

This creates poor adoption.

Move runtime permission requests into onboarding.

Request:

* Notification permission
* READ_SMS permission

Each permission should include a short, user-friendly explanation of why it is needed.

If denied, continue onboarding and disable only the related functionality.

Do not block access to the app.

---

# FEATURE 9

# Performance

While implementing these features, improve overall responsiveness.

Requirements:

* Faster startup.
* Faster offline/online transitions.
* Efficient background workers.
* Minimize battery usage.
* Minimize unnecessary network requests.
* Avoid UI freezes during synchronization.
* Ensure scrolling remains smooth even while syncing.

---

# FEATURE 10

# Error Handling

Every new feature must fail gracefully.

Examples:

* Sync failure → keep cached data.
* SMS parsing failure → ignore unknown SMS.
* Missing billboard image → show placeholder.
* Notification download failure → use cached templates.
* Internet unavailable → continue offline mode.
* Permission denied → disable dependent features without crashing.

The user should rarely encounter an error dialog.

---

# TESTING REQUIREMENTS

Before considering this implementation complete, verify the following scenarios:

### Notifications

* Offline notification triggers correctly.
* Online notification triggers correctly.
* Low data notification.
* No data notification.
* Bundle received notification.
* Gift received notification.
* Cooldowns prevent spam.
* Random templates rotate correctly.
* User name appears correctly.
* Time-of-day greetings work.

### SMS Engine

* All provided Safaricom message formats are recognized.
* Unknown messages are ignored.
* Rules downloaded from server function correctly.
* New rules can be added without updating the app.

### Synchronization

* Incremental sync works.
* Unchanged resources are skipped.
* Force sync updates critical data.
* Offline cache remains intact after failed sync.

### Offline Mode

* Transition to offline is immediate.
* Transition back online is immediate.
* Offline purchases continue working.
* Cached content remains available.

### Personalization

* Favourite bundles are learned.
* Preferred payment number is remembered.
* Suggested recipient numbers appear correctly.
* Recommendations improve over time.
* No personal data leaves the device.

### Billboard

* Images load correctly.
* GIFs animate correctly.
* Cached media works offline.
* Click actions behave correctly.

### Permissions

* Notification permission requested during onboarding.
* READ_SMS permission requested during onboarding.
* App functions correctly when permissions are denied.

---

# DELIVERABLES

Provide:

1. Updated Android project source code.
2. Database migration scripts (if local schema changes).
3. Updated synchronization implementation.
4. New notification engine.
5. Dynamic SMS rule engine.
6. Personalization engine.
7. Billboard media support.
8. Updated onboarding flow.
9. Debug APK only (no AAB).
10. Documentation describing:

* Architecture changes.
* New local database tables.
* Sync workflow.
* Notification workflow.
* SMS rule processing flow.
* Personalization logic.
* Any configuration required on the server.

Do **not** remove or regress existing features. The goal is to make Skylink Bingwa feel significantly more intelligent, reliable, and personal while preserving its offline-first philosophy.

---


