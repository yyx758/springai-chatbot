CREATE TABLE IF NOT EXISTS agent_long_term_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_key VARCHAR(255) NOT NULL,
    memory_type VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    content TEXT NOT NULL,
    load_hint VARCHAR(512) NULL,
    source_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    last_used_time DATETIME NULL,
    use_count BIGINT NOT NULL DEFAULT 0,
    content_hash VARCHAR(64) NULL
);

CREATE INDEX idx_memory_user_scope_status
    ON agent_long_term_memory(user_id, scope_type, scope_key, status);

CREATE INDEX idx_memory_user_type_status
    ON agent_long_term_memory(user_id, memory_type, status);

CREATE INDEX idx_memory_hash
    ON agent_long_term_memory(user_id, content_hash);

CREATE INDEX idx_memory_updated_time
    ON agent_long_term_memory(updated_time);

CREATE TABLE IF NOT EXISTS agent_memory_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    memory_id BIGINT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    source_session_id VARCHAR(255) NULL,
    source_record_id BIGINT NULL,
    payload_json JSON NULL,
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_memory_event_user_time
    ON agent_memory_event(user_id, created_time);

CREATE INDEX idx_memory_event_memory
    ON agent_memory_event(memory_id);
