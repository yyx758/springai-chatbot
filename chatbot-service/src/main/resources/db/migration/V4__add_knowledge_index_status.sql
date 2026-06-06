ALTER TABLE knowledge_document
    ADD COLUMN index_status VARCHAR(32) NULL COMMENT 'RAG index status' AFTER enabled,
    ADD COLUMN index_error TEXT NULL COMMENT 'RAG index error' AFTER index_status,
    ADD COLUMN indexed_time TIMESTAMP NULL COMMENT 'RAG indexed time' AFTER index_error;
