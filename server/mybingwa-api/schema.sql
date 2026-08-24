-- Skylink Bingwa payments table.
-- Import once via cPanel → phpMyAdmin → your database → Import (or paste in the SQL tab).

CREATE TABLE IF NOT EXISTS payments (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_request_id    VARCHAR(64)  NOT NULL,
    checkout_request_id  VARCHAR(64)  DEFAULT NULL,
    offer_id             VARCHAR(32)  NOT NULL,
    amount               INT          NOT NULL,
    payer                VARCHAR(16)  NOT NULL,
    recipient            VARCHAR(16)  DEFAULT NULL,
    status               VARCHAR(24)  NOT NULL DEFAULT 'PAYMENT_REQUESTED',
    mpesa_receipt        VARCHAR(24)  DEFAULT NULL,
    result_code          VARCHAR(12)  DEFAULT NULL,
    result_desc          VARCHAR(191) DEFAULT NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    UNIQUE KEY uniq_client_request (client_request_id),
    KEY idx_checkout (checkout_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
