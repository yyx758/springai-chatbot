-- 智能客服系统数据库表结构
-- 作者: yyvb
-- 数据库: H2

-- 聊天记录表
CREATE TABLE IF NOT EXISTS chat_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_message TEXT NOT NULL,
    bot_response TEXT NOT NULL,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    session_id VARCHAR(100) NOT NULL
);
