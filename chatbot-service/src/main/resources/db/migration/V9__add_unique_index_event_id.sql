-- Add unique indexes for idempotent chat event persistence.
-- MySQL does not support ADD INDEX IF NOT EXISTS on all target versions, so
-- guard each ALTER with INFORMATION_SCHEMA checks.

SET @chat_record_event_idx_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_record'
      AND INDEX_NAME = 'uk_chat_record_event_id'
);

SET @chat_record_event_idx_sql = IF(
    @chat_record_event_idx_exists = 0,
    'ALTER TABLE chat_record ADD UNIQUE KEY uk_chat_record_event_id (event_id)',
    'SELECT 1'
);

PREPARE chat_record_event_idx_stmt FROM @chat_record_event_idx_sql;
EXECUTE chat_record_event_idx_stmt;
DEALLOCATE PREPARE chat_record_event_idx_stmt;

SET @outbox_event_idx_exists = (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'chat_event_outbox'
      AND INDEX_NAME = 'uk_chat_event_outbox_event_id'
);

SET @outbox_event_idx_sql = IF(
    @outbox_event_idx_exists = 0,
    'ALTER TABLE chat_event_outbox ADD UNIQUE KEY uk_chat_event_outbox_event_id (event_id)',
    'SELECT 1'
);

PREPARE outbox_event_idx_stmt FROM @outbox_event_idx_sql;
EXECUTE outbox_event_idx_stmt;
DEALLOCATE PREPARE outbox_event_idx_stmt;
