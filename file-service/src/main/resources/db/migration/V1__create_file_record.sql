CREATE TABLE IF NOT EXISTS file_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    file_key        VARCHAR(255) NOT NULL UNIQUE COMMENT '文件唯一标识（存储路径键）',
    original_name   VARCHAR(500) NOT NULL COMMENT '原始文件名',
    content_type    VARCHAR(100) NOT NULL COMMENT 'MIME 类型',
    file_size       BIGINT NOT NULL COMMENT '文件大小（字节）',
    storage_type    VARCHAR(20) NOT NULL DEFAULT 'LOCAL' COMMENT '存储类型：LOCAL/MINIO',
    storage_path    VARCHAR(1000) NOT NULL COMMENT '存储路径',
    thumbnail_key   VARCHAR(255) NULL COMMENT '缩略图文件键（仅图片）',
    uploader_id     BIGINT NOT NULL COMMENT '上传者用户 ID',
    biz_type        VARCHAR(50) NOT NULL COMMENT '业务类型：CHAT_IMAGE/KNOWLEDGE_DOC/AVATAR',
    biz_id          VARCHAR(100) NULL COMMENT '关联业务 ID',
    download_count  INT DEFAULT 0 COMMENT '下载次数',
    created_time    DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_time    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_uploader (uploader_id),
    INDEX idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件元数据表';
