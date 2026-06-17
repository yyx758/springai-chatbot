-- 为 chat_record 表添加 event_id 唯一索引
ALTER TABLE chat_record
ADD UNIQUE KEY uk_chat_record_event_id (event_id);

-- 为 chat_event_outbox 表添加 event_id 唯一索引
ALTER TABLE chat_event_outbox
ADD UNIQUE KEY uk_chat_event_outbox_event_id (event_id);
