-- Skylink Bingwa remote settings (managed later from the admin panel).
-- Import once via cPanel → phpMyAdmin → your database → Import.
-- The app fetches these when online and CACHES them so they still work offline.

CREATE TABLE IF NOT EXISTS settings (
    skey       VARCHAR(48) PRIMARY KEY,
    svalue     VARCHAR(191) NOT NULL,
    updated_at DATETIME NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Seed the current live values. Change these any time (admin panel later) and the
-- app picks them up on its next online sync.
INSERT INTO settings (skey, svalue, updated_at) VALUES
    ('till_number',      '',  NOW()),
    ('paybill_number',   '',  NOW()),
    ('support_number',   '',  NOW()),
    ('support_whatsapp', '',  NOW())
ON DUPLICATE KEY UPDATE svalue = VALUES(svalue), updated_at = NOW();
