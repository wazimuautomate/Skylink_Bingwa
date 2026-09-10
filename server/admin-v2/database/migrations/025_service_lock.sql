-- Service lock ("Danger zone") — the Super-Admin-only global kill switch.
--
-- State lives in the existing key/value settings table rather than a table of its own:
-- it is five scalars, it must be readable by BOTH this admin and the legacy
-- mybingwa-api endpoints (which read `mb_settings` directly), and a key/value read is
-- the cheapest thing that can sit in front of every public request.
--
-- Seeded OFF. Nothing here changes behaviour until a Super Admin turns it on.
INSERT IGNORE INTO {p}settings (skey, svalue, updated_at) VALUES
    ('service_lock.enabled',    '0', UTC_TIMESTAMP()),
    ('service_lock.amount',     '0', UTC_TIMESTAMP()),
    ('service_lock.reason',     '',  UTC_TIMESTAMP()),
    ('service_lock.enabled_at', '',  UTC_TIMESTAMP()),
    ('service_lock.enabled_by', '',  UTC_TIMESTAMP());
