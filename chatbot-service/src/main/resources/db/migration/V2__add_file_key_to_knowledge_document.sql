ALTER TABLE knowledge_document ADD COLUMN file_key VARCHAR(255) NULL COMMENT '关联文件服务的文件键' AFTER content;
