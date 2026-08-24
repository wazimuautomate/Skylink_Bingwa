# SKYLINK BINGWA PRODUCTION RELEASE

# PART 2A — PHP SERVER & ADMIN IMPLEMENTATION

This prompt is for the **PHP Backend & Admin Dashboard** only.

The Android application already exists and will be updated separately. Your responsibility is to build all backend capabilities required to support the production Android application.

Do not redesign the system from scratch.

Extend the existing architecture cleanly.

---

# PROJECT PHILOSOPHY

The server is **NOT** the application.

The Android app is the application.

The server is simply the control room.

Its responsibility is to distribute content, configuration and updates to Android devices.

The server should never become dependent on user data.

The application remains Offline First.

Meaning:

Server goes down

↓

Users should still continue buying bundles using previously synchronized information.

The server only feeds data.

The Android app owns the user experience.

---

# EXISTING SERVER

The server already manages:

* Offers
* Payment Details
* Support Details
* Billboards
* Notifications
* Configurations
* Preview & Publish
* MySQL Database
* PHP APIs

Do not break existing APIs unless necessary.

If changes are required,

maintain backward compatibility where possible.

---

# DEVELOPMENT GOALS

The server should become:

* Cleaner
* Faster
* Easier to maintain
* Easier for administrators
* Easier to extend

Avoid hardcoding.

Everything that may change in future should become configurable through Admin.

---

# FEATURE 1

# Dynamic SMS Rule Management

This is one of the biggest new modules.

Currently,

Android understands Safaricom messages because developers hardcoded them.

That ends now.

The server should teach Android how to understand messages.

---

## New Admin Module

Create

SMS Rules

inside Admin.

---

Each rule should contain

Rule Name

Sender ID

Pattern Type

Message Pattern

Event Type

Priority

Enabled

Description

Created Date

Updated Date

Created By

Last Modified By

---

# Pattern Types

Support multiple matching methods.

Examples

Regex

Contains

Starts With

Ends With

Exact Match

Keyword Combination

This allows future flexibility.

---

# Supported Events

Initially support

DATA_RECEIVED

SMS_RECEIVED

MINUTES_RECEIVED

LOW_DATA

VERY_LOW_DATA

NO_DATA

LOW_SMS

LOW_MINUTES

GIFT_RECEIVED

UNKNOWN

Design this to support future event types without database redesign.

---

# Default Rules

Populate the database with the current Safaricom messages.

These become starter templates.

---

## Sender

Safaricom

Message

You have received 20 SMS Daily SMS Bundle.

Meaning

SMS_RECEIVED

---

Sender

Safaricom

You have received Sh20=250MB 24hr from Bingwa Sokoni.

Meaning

DATA_RECEIVED

---

Sender

Safaricom

Thank You For Purchasing 1024.0 MB hourly data bundle.

Meaning

DATA_RECEIVED

Bundle Type

Hourly

---

Sender

Safaricom

You have received 45 Talkmore Minutes.

Meaning

MINUTES_RECEIVED

---

Sender

Safaricom

You have received Easy-Talk 50 Minutes + 50 SMS.

Meaning

MINUTES_RECEIVED

*

SMS_RECEIVED

---

Sender

SAF_Balance

Dear customer,

your deal of the day data balance is 75MBs.

Meaning

LOW_DATA

---

Sender

SAF_Balance

your deal of the day data balance is below 2MBs.

Meaning

VERY_LOW_DATA

---

Sender

SAF_Balance

you do not have an active data bundle.

Meaning

NO_DATA

---

Sender

SAF_Balance

Easy-Talk Minutes are below 10 Minutes.

Meaning

LOW_MINUTES

---

Sender

SAF_OfaMOTO

You have received a gift...

Meaning

GIFT_RECEIVED

---

These should simply be the initial database entries.

Future changes should never require PHP code changes.

---

# Rule Testing

Admin should have

Test Rule

Paste SMS

↓

Choose Sender

↓

See

Matched Rule

Detected Event

Extracted Variables

Reason for Match

This allows administrators to test new Safaricom formats before publishing.

---

# Rule Priority

Sometimes one SMS may match multiple rules.

Priority determines which wins.

Higher Priority

↓

Evaluated first.

---

# Rule Publishing

SMS Rules should participate in Preview & Publish.

Draft

↓

Preview

↓

Publish

↓

Android downloads them.

---

# FEATURE 2

# Notification Management Redesign

Current notifications are too basic.

We need a flexible notification management system.

---

Each notification should support

Title

Body

Message Variations

Category

Priority

Active

Start Date

End Date

Allowed Time Range

Cooldown

Target Trigger

---

# Notification Categories

Support

Offline

Online

Morning

Afternoon

Evening

Night

Promotion

Purchase Success

Bundle Received

Low Data

Very Low Data

No Data

Low Minutes

Low SMS

Gift Received

General

System

Future categories should require no code changes.

---

# Multiple Variations

One notification should contain multiple wording variations.

Example

Offline

Variation 1

James,

Looks like you're offline.

Variation 2

Morning James ☀️

Need another bundle?

Variation 3

Internet disappeared.

Let's get you back online.

Android randomly chooses one.

This prevents repetition.

---

# Variables

Support placeholders

Example

{{first_name}}

{{bundle_name}}

{{remaining_data}}

{{time_of_day}}

{{recommended_offer}}

Android replaces these locally.

---

# Scheduling

Notifications may have

Start Date

End Date

Specific Days

Morning Only

Evening Only

Weekends Only

etc.

---

# Trigger Types

Support

Manual

Offline

Online

SMS Event

Purchase Event

Bundle Expiry

Time Based

Promotion

The Android app decides whether to display them based on local conditions.

---

# FEATURE 3

# Synchronization API Redesign

The existing synchronization system should be upgraded to support incremental updates instead of downloading everything.

Every synchronizable resource must expose:

* Resource version
* Last updated timestamp
* Optional checksum/hash
* Publish version

The Android app should first request lightweight metadata to determine what has changed. Only changed resources should be downloaded.

Design endpoints that are extensible so future resource types can be added without changing the sync protocol.

Resources that should support incremental sync include:

* Offers
* Categories
* Billboards
* Notification templates
* SMS rules
* Payment details
* Support details
* Configuration
* Feature flags
* Any future content modules

Do not delete or invalidate existing client data if synchronization fails. The API should always allow clients to keep using previously synchronized content.

---

**END OF PART 2A**

Great. This is the final part of the server prompt. This part focuses on making the **Admin Dashboard** the true control center of Skylink Bingwa while fixing the current issues you've observed.

---

# SKYLINK BINGWA PRODUCTION RELEASE

# PART 2B — PHP SERVER & ADMIN IMPLEMENTATION (Continuation)

Continue from Part 2A.

---

# FEATURE 4

# Complete Preview & Publish System Redesign

The current Preview & Publish system works conceptually, but it has a major bug and poor user experience.

This system is extremely important because it controls how changes are released to every Android device.

Treat this as a release management system, not just another admin page.

---

## Current Problems

The current implementation lists many resources as "Modified" even when nobody changed them.

This creates several problems:

* Administrators cannot tell what was actually changed.
* The publish screen becomes very long.
* It reduces confidence before publishing.
* It increases the risk of accidentally publishing unnecessary changes.

The screenshot attached shows this exact problem.

This must be fixed completely.

---

# New Publish Workflow

Every editable module should support two states:

## Live Version

Currently used by Android users.

## Draft Version

Working copy used by administrators.

Changes remain inside Draft until they are published.

Android users must NEVER see Draft changes.

Only published changes become available to devices.

---

# Dirty Tracking

This is the most important requirement.

Instead of assuming something changed, the system must detect real changes.

When an administrator edits an item:

Compare Draft

vs

Live

If nothing actually changed

↓

Do NOT mark it as modified.

Only resources with actual differences should appear inside Preview & Publish.

Example

Admin opens Offer A

Clicks Save

Without changing anything.

Expected Result

Offer A should NOT appear in Preview.

---

Another Example

Admin changes

Price

Only.

Preview should show

Offer A

Modified

Changed Fields

* Price

Nothing else.

Do not simply compare timestamps.

Compare field values.

---

# Field-Level Change Detection

When possible, record exactly which fields changed.

Example

Offer

Price

Description

Priority

Only "Price" changed.

The Preview screen should say:

Offer A

• Price updated

instead of simply

Offer modified.

This gives administrators confidence before publishing.

---

# New Preview UI

The current page is cluttered and requires excessive scrolling.

Replace it with a cleaner release-oriented interface.

---

## Top Summary Card

Display:

Current Live Version

Draft Version

Pending Changes

Last Published

Published By

Example

Live Version

v1.4.2

Draft Version

v1.4.3

Pending Changes

7

Last Published

Yesterday 3:42 PM

---

# Group Changes

Instead of one long list,

group changes by module.

Example

Offers (3)

Billboards (2)

Notifications (5)

SMS Rules (4)

Payment Details (1)

Support Details (1)

Configuration (2)

Each section remains collapsed until clicked.

This dramatically reduces scrolling.

---

# Inside Each Group

Display only:

Item Name

Change Type

Changed Fields

Example

Offer

Sh20 Daily

Price updated

Priority updated

---

Billboard

Weekend Promo

Image changed

Schedule changed

---

Notification

Offline Reminder

Message updated

Cooldown changed

---

# Publish Button

Before publishing show:

Summary

Total Changes

Resources affected

Estimated sync impact

Confirmation

After confirmation:

Publish immediately.

---

# Publish Process

Publishing should:

Increment application version.

Generate release identifier.

Record release notes.

Update synchronization version.

Invalidate outdated sync metadata.

Notify Android clients that a newer configuration exists.

Android will detect this and synchronize automatically.

---

# Release Notes

Allow administrators to optionally enter release notes.

Example

Updated Till Number.

Added Weekend Offers.

Fixed Promotion Banner.

Improved Notifications.

Android does not necessarily need to display these, but they should remain stored for auditing.

---

# Release History

At the bottom of the page:

Release History

Display:

Release Version

Published By

Date

Release Notes

Number of Changes

Status

Newest first.

Collapse older releases.

Allow viewing the detailed changes included in each release.

---

# Rollback (Future Ready)

Do not implement rollback yet.

However, design the release architecture so rollback can be added later without redesigning the database.

---

# FEATURE 5

# Billboard Management Upgrade

The Android application will now support media billboards.

Upgrade the server accordingly.

---

Each billboard should support:

Title

Description

Image

Animated GIF

Priority

Display Order

Start Date

End Date

Enabled

Target Action

Button Label

Click URL

Internal Action

Target Offer (optional)

Target Category (optional)

---

# Media Upload

Support uploading:

PNG

JPEG

WEBP

GIF

Optimize uploaded files.

Generate thumbnails if necessary.

Store metadata in the database.

---

# Scheduling

Billboards may automatically activate.

Example

Weekend Offer

Starts Friday

Ends Sunday

Administrator should not need to manually enable it.

---

# Priority

If multiple billboards are active

Display highest priority first.

Android may still personalize ordering locally.

---

# FEATURE 6

# Version Management

The server should maintain one global synchronization version.

Every successful publish increments this version.

Android compares:

Server Version

Local Version

If identical

↓

No synchronization.

If newer

↓

Synchronize only modified resources.

---

# Resource Versioning

Each resource should also maintain its own version.

Example

Offers

Version 18

Notifications

Version 9

SMS Rules

Version 5

Payment Details

Version 23

This prevents downloading unrelated resources.

---

# FEATURE 7

# Audit Logging

Every administrator action should be logged.

Include:

Administrator

Action

Module

Item

Timestamp

Old Value (where practical)

New Value (where practical)

IP Address (optional)

Examples

Created Offer

Edited Payment Details

Deleted Billboard

Published Release

Created SMS Rule

Disabled Notification

Audit logs should be searchable and filterable.

---

# FEATURE 8

# Database Improvements

Review the current schema and normalize where necessary.

Add only the tables required to support:

SMS Rules

Notification Variations

Release History

Resource Versions

Audit Logs

Billboard Media

Do not introduce unnecessary complexity.

Preserve existing data.

Provide SQL migration scripts.

---

# FEATURE 9

# API Documentation

Document every new endpoint.

For each endpoint provide:

Purpose

HTTP Method

Authentication

Parameters

Example Request

Example Response

Error Responses

Android developers should be able to integrate without reading PHP source code.

---

# FEATURE 10

# Deployment Package

The production server is deployed manually via cPanel.

To simplify deployment, provide:

1. Updated PHP project source.
2. SQL migration scripts.
3. A ZIP containing ONLY the modified PHP files.
4. Any new assets or uploads required.
5. A deployment checklist describing:

   * Which files replace existing ones.
   * Which SQL scripts to run.
   * Any configuration changes.
   * Cache clearing steps (if needed).

The ZIP should preserve the existing folder structure so the modified files can be uploaded directly over the current installation.

---

# TESTING CHECKLIST

Before marking the implementation complete, verify:

### SMS Rules

* Rules can be created, edited, duplicated, disabled, and deleted.
* Rule tester correctly identifies matching messages.
* Unknown messages do not match incorrectly.

### Notifications

* Multiple message variations work.
* Scheduling works.
* Categories are respected.
* Variables are supported.

### Preview & Publish

* Unchanged items never appear.
* Only modified fields are listed.
* Publishing increments versions correctly.
* Release history is created.
* Android clients detect the new version.

### Billboards

* Images upload correctly.
* GIFs display correctly.
* Scheduling works.
* Priority ordering works.

### Synchronization

* Incremental sync returns only changed resources.
* Unchanged resources are skipped.
* Failed syncs do not corrupt client state.

### Audit Logs

* Every administrative action is recorded.
* Logs are searchable.
* Publish events are fully captured.

---

# FINAL DELIVERABLES

Provide the following:

1. Updated PHP backend source code.
2. Updated Admin Dashboard.
3. SQL migration scripts.
4. Updated synchronization APIs.
5. SMS Rules management module.
6. Notification management improvements.
7. Billboard media management.
8. Preview & Publish redesign.
9. Release history and version management.
10. Audit logging.
11. API documentation.
12. ZIP containing only the modified PHP files for deployment via cPanel.

---

## FINAL ARCHITECTURAL REQUIREMENT

The Android application and the PHP server **must remain loosely coupled**.

The server's role is to publish and distribute content, configuration, and rules.

The Android application's role is to deliver a fast, intelligent, offline-first experience.

**User behaviour, purchase habits, favourite bundles, phone numbers, purchase history, and personalization data must never leave the user's device.** The server should never collect or require this information. Personalization happens entirely on-device, while the server remains a configurable content and release management platform.

---

