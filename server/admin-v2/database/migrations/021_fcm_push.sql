-- FCM push notifications.
--
-- Stores the Firebase device registration token per customer so the admin can push
-- a message straight to a phone, plus a history table so every broadcast is auditable.
--
-- IDEMPOTENCY NOTE: the customer API (mybingwa-api/register_user.php) also adds
-- fcm_token to mb_customers on its own, so this column may already exist by the time
-- the migration runs. MySQL has no ADD COLUMN IF NOT EXISTS, so each schema change is
-- guarded through information_schema and executed via PREPARE. Without this the
-- migration throws, is never recorded, and Migrator::run() aborts — blocking every
-- later migration too.
--
-- Statements are separated by a line that is exactly "-- @@" (see database/migrate.php).

SET @has_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = '{p}customers'
       AND COLUMN_NAME  = 'fcm_token'
);
-- @@
SET @sql := IF(@has_col = 0,
    'ALTER TABLE {p}customers ADD COLUMN fcm_token VARCHAR(255) NULL DEFAULT NULL',
    'DO 0'
);
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
SET @has_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = '{p}customers'
       AND INDEX_NAME   = 'idx_customer_fcm_token'
);
-- @@
SET @sql := IF(@has_idx = 0,
    'CREATE INDEX idx_customer_fcm_token ON {p}customers (fcm_token)',
    'DO 0'
);
-- @@
PREPARE mb_stmt FROM @sql;
-- @@
EXECUTE mb_stmt;
-- @@
DEALLOCATE PREPARE mb_stmt;
-- @@
CREATE TABLE IF NOT EXISTS {p}push_broadcasts (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    title            VARCHAR(120) NOT NULL,
    body             TEXT         NOT NULL,
    deep_link_route  VARCHAR(32)  NOT NULL DEFAULT 'notifications',
    recipients_count INT          NOT NULL DEFAULT 0,
    success_count    INT          NOT NULL DEFAULT 0,
    failure_count    INT          NOT NULL DEFAULT 0,
    created_by       VARCHAR(64)  NOT NULL DEFAULT 'admin',
    created_at       DATETIME     NOT NULL,
    KEY idx_push_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
