ALTER TABLE chat_record
    ADD COLUMN event_id VARCHAR(64) NULL AFTER session_id,
    ADD COLUMN user_id BIGINT NULL AFTER event_id,
    ADD UNIQUE KEY uk_chat_record_event_id (event_id),
    ADD INDEX idx_chat_record_user_time (user_id, created_time);

CREATE TABLE IF NOT EXISTS chat_event_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    session_id VARCHAR(255) NOT NULL,
    payload_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    next_retry_time DATETIME NULL,
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_time DATETIME NULL,
    INDEX idx_chat_outbox_status_retry (status, next_retry_time),
    INDEX idx_chat_outbox_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
