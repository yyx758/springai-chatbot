CREATE TABLE IF NOT EXISTS chat_session_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    user_id BIGINT NULL,
    summary TEXT NOT NULL,
    last_summarized_record_id BIGINT NOT NULL,
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_session_summary_session (session_id),
    INDEX idx_chat_session_summary_user (user_id),
    INDEX idx_chat_session_summary_record (last_summarized_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
