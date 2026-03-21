-- 初始化数据
-- 作者: yyvb

-- 插入示例聊天记录
INSERT INTO chat_record (user_message, bot_response, session_id) VALUES 
('你好', '你好！我是智能客服小AI，很高兴为您服务！有什么可以帮助您的吗？', 'demo-session-001'),
('你们的营业时间是什么时候？', '我们的营业时间是周一到周日 9:00-18:00，全年无休。如有紧急问题，也可以随时联系我们的在线客服。', 'demo-session-001'),
('如何联系人工客服？', '您可以通过以下方式联系人工客服：\n1. 拨打客服热线：400-123-4567\n2. 发送邮件到：service@example.com\n3. 在本页面点击"转人工客服"按钮', 'demo-session-002'); 