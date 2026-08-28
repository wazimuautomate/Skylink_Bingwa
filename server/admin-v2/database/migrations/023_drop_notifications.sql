-- Remove the scheduled-notifications module.
--
-- The owner's instant push feature (mb_push_broadcasts + FcmService, migration
-- 021) does everything this module was built for, and does it immediately rather
-- than through a publish cycle. Keeping both meant two places to write a message
-- and two ways for it to be wrong.
--
-- WHAT GOES: notification campaigns, their wording variations, the catalogue
-- tables that fed the campaign editor's dropdowns, and the delivery-event log.
-- The admin UI, controller, routes, service and the two public endpoints that
-- served them (get_app_notifications.php, get_notification_templates.php) are
-- deleted in the same change, and the app stops syncing them -- it falls back to
-- the notification wording seeded into the APK, which is exactly what it already
-- does on an install with no base URL configured.
--
-- WHAT STAYS: mb_message_sender_ids / mb_message_templates /
-- mb_message_template_revisions from migration 005. Despite the similar name
-- those are the Safaricom SMS-recognition patterns, a different feature with no
-- notification-campaign dependency.
--
-- Dropped rather than archived: these tables hold editorial copy that is
-- reproducible from the publish history in mb_releases, not financial or
-- customer records. Statements are separated by a line that is exactly "-- @@".

DROP TABLE IF EXISTS {p}notification_variations;
-- @@
DROP TABLE IF EXISTS {p}notification_events;
-- @@
DROP TABLE IF EXISTS {p}notification_templates;
-- @@
DROP TABLE IF EXISTS {p}notification_campaigns;
-- @@
DROP TABLE IF EXISTS {p}notification_categories;
-- @@
DROP TABLE IF EXISTS {p}notification_trigger_types;
-- @@
DROP TABLE IF EXISTS {p}notification_variables;
-- @@
-- The publish pipeline no longer builds a `notifications` section, so drop the
-- resource-version row that tracked it. Leaving it would make the app's sync
-- manifest advertise a resource that no longer exists.
DELETE FROM {p}resource_versions WHERE resource_key = 'notifications';
