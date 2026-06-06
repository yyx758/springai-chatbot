CREATE TABLE IF NOT EXISTS agent_tool_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_level VARCHAR(32) NOT NULL,
    arguments_json TEXT NULL,
    result_summary TEXT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT NULL,
    started_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_time TIMESTAMP NULL,
    INDEX idx_agent_tool_user_time (user_id, started_time),
    INDEX idx_agent_tool_session (session_id),
    INDEX idx_agent_tool_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_pending_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    arguments_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expire_time TIMESTAMP NOT NULL,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed_time TIMESTAMP NULL,
    result_summary TEXT NULL,
    error_message TEXT NULL,
    INDEX idx_agent_pending_user_status (user_id, status),
    INDEX idx_agent_pending_session (session_id),
    INDEX idx_agent_pending_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
