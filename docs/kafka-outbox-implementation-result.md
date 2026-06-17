# Kafka Outbox 与聊天上下文一致性修正 - 实施结果

## 1. 实施概述

根据 `docs/kafka-outbox-context-consistency-plan.md` 计划，已完成 Kafka、Outbox 与聊天上下文一致性的修正。

**实施时间**：2026-06-17

**核心目标**：
- 解决用户下一轮对话可能读不到上一轮内容的问题
- 解决 outbox 不是严格 transactional outbox 的问题
- Kafka 从"聊天记录主落库链路"调整为"异步事件总线"

## 2. 修改文件清单

### 2.1 新增文件

| 文件 | 说明 |
|------|------|
| `chatbot-service/src/main/java/com/example/chatbot/service/ChatRecordPersistenceService.java` | 事务内同时写入 chat_record 和 chat_event_outbox |
| `chatbot-service/src/main/resources/db/migration/V9__add_unique_index_event_id.sql` | 数据库迁移：添加 event_id 唯一索引 |
| `chatbot-service/src/test/java/com/example/chatbot/service/ChatRecordPersistenceServiceTest.java` | ChatRecordPersistenceService 单元测试 |
| `docs/kafka-outbox-implementation-result.md` | 本文档 |

### 2.2 修改文件

| 文件 | 修改内容 |
|------|----------|
| `chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java` | 改用 ChatRecordPersistenceService，移除 ChatEventProducer 依赖 |
| `chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventConsumer.java` | 不再写聊天记录，改为验证 chat_record 是否存在 |

## 3. 架构变更

### 3.1 修正前的链路

```
构建上下文：Redis 优先，MySQL fallback
-> 调用 AI 模型流式输出
-> 模型输出结束后构造 ChatEvent
-> ChatEventProducer 同步发送 Kafka
-> Kafka Consumer 消费事件
-> Consumer 写入 chat_record
-> Consumer 追加 Redis 上下文缓存
```

**问题**：
1. 用户下一轮对话可能读不到上一轮内容（Kafka 消费延迟）
2. outbox 不是严格的 transactional outbox（宕机可能丢事件）

### 3.2 修正后的链路

```
SSE 对话开始
-> 构建上下文：Redis 优先，MySQL fallback
-> 调用 AI 模型流式输出
-> 模型输出完成
-> 同一数据库事务：
     1. 写 chat_record
     2. 写 chat_event_outbox
-> 事务提交成功后：
     1. append Redis 上下文缓存
     2. 完成 SSE
-> 后台 outbox dispatcher 扫描 PENDING/FAILED_RETRY
-> 发送 Kafka
-> Kafka consumers 处理摘要、统计、索引、通知、审计等副作用
```

**优势**：
- Kafka 未消费时，下一轮上下文仍然不缺上一轮内容
- Kafka 不可用时，聊天记录仍然已写入 MySQL
- 服务在写库后、发 Kafka 前宕机时，outbox 仍能在服务恢复后继续补发
- Redis 不可用时，不影响聊天记录落库；下次可从 MySQL 回填

## 4. 详细修改说明

### 4.1 ChatRecordPersistenceService（新增）

**职责**：在事务内同时写入 chat_record 和 chat_event_outbox，确保原子性。

**核心方法**：

```java
@Transactional
public ChatRecord saveChatAndOutbox(ChatEvent event) {
    ensureEventId(event);

    // 幂等检查：如果 eventId 已存在，直接返回
    ChatRecord existed = chatRecordMapper.selectOne(...);
    if (existed != null) {
        return existed;
    }

    // 1. 写入 chat_record
    ChatRecord chatRecord = ChatRecord.builder()...build();
    chatRecordMapper.insert(chatRecord);

    // 2. 写入 chat_event_outbox
    ChatEventOutbox outbox = ChatEventOutbox.builder()...build();
    outboxMapper.insert(outbox);

    return chatRecord;
}
```

**特点**：
- 使用 `@Transactional` 确保原子性
- eventId 作为幂等键，防止重复保存
- 包含 SSE 协议残留清洗、图片数据处理等逻辑

### 4.2 ChatbotService（修改）

**修改内容**：
1. 移除 `ChatEventProducer` 依赖
2. 注入 `ChatRecordPersistenceService`
3. 修改 `asyncSaveChatRecord` 和 `asyncSaveChatRecordWithImage` 方法
4. 修改 `asyncSaveChatRecordWithFileKey` 方法

**核心变化**：

```java
// 修正前
public void asyncSaveChatRecordWithImage(...) {
    ChatEvent event = ChatEvent.builder()...build();
    chatEventProducer.sendChatEvent(event);
}

// 修正后
public void asyncSaveChatRecordWithImage(...) {
    ChatEvent event = ChatEvent.builder()...build();
    try {
        ChatRecord chatRecord = chatRecordPersistenceService.saveChatAndOutbox(event);
        chatContextService.appendPersistedRecordToCache(chatRecord);
        log.info("【ChatbotService】聊天记录保存成功，RecordId: {}, SessionId: {}",
                chatRecord.getId(), sessionId);
    } catch (Exception e) {
        log.error("【ChatbotService】聊天记录保存失败，SessionId: {}", sessionId, e);
    }
}
```

**异常策略**：
- MySQL/outbox 事务失败：记录 error 日志；不写 Redis
- Redis append 失败：不影响主结果（已在外层 catch）

### 4.3 ChatEventConsumer（修改）

**修改内容**：
1. 移除直接写 chat_record 的逻辑
2. 移除 Redis 上下文缓存更新逻辑
3. 改为验证 chat_record 是否存在
4. 移除不再需要的方法（cleanBotResponse、resolveImageData、resolveUserId）

**核心变化**：

```java
// 修正前
public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
    // 写入 chat_record
    chatRecordMapper.insert(chatRecord);
    // 更新 Redis 缓存
    chatContextService.appendPersistedRecordToCache(chatRecord);
    ack.acknowledge();
}

// 修正后
public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
    // 验证 chat_record 是否存在
    ChatRecord existed = chatRecordMapper.selectOne(...);
    if (existed == null) {
        throw new RuntimeException("chat_record 不存在: " + event.getEventId());
    }

    // TODO: 处理异步副作用（摘要刷新、统计、审计等）
    ack.acknowledge();
}
```

**异常处理**：
- 如果 chat_record 不存在，抛出异常让 Kafka error handler 重试
- 第一版最小实现：仅验证记录存在性

### 4.4 数据库迁移（新增）

**文件**：`chatbot-service/src/main/resources/db/migration/V9__add_unique_index_event_id.sql`

**内容**：

```sql
-- 为 chat_record 表添加 event_id 唯一索引
ALTER TABLE chat_record
ADD UNIQUE KEY uk_chat_record_event_id (event_id);

-- 为 chat_event_outbox 表添加 event_id 唯一索引
ALTER TABLE chat_event_outbox
ADD UNIQUE KEY uk_chat_event_outbox_event_id (event_id);
```

**注意事项**：
- MySQL 唯一索引允许多个 NULL，因此新链路必须保证所有新聊天记录都有非空 event_id
- 如果线上已有重复或空 event_id 数据，迁移前需要先清理

## 5. 测试说明

### 5.1 单元测试

**文件**：`chatbot-service/src/test/java/com/example/chatbot/service/ChatRecordPersistenceServiceTest.java`

**测试用例**：
1. `saveChatAndOutbox_success` - 正常保存：chat_record 和 outbox 都写入成功
2. `saveChatAndOutbox_idempotent` - 幂等检查：eventId 已存在时跳过保存
3. `saveChatAndOutbox_autoGenerateEventId` - 自动生成 eventId：当 eventId 为空时
4. `saveChatAndOutbox_cleanBotResponse` - 清理 botResponse：SSE 协议残留清洗
5. `saveChatAndOutbox_imageData_fileKeyMode` - 图片数据：fileKey 模式
6. `saveChatAndOutbox_imageData_base64Mode` - 图片数据：imageBytes 不写入 Base64

### 5.2 运行测试

```bash
# 运行 ChatRecordPersistenceService 单元测试
mvn -q -pl chatbot-service -Dtest=ChatRecordPersistenceServiceTest test

# 运行 Kafka 相关测试
mvn -q -pl chatbot-service -Dtest=KafkaReliabilityTest test

# 运行所有测试
mvn -q -pl chatbot-service test

# 打包
mvn -q -DskipTests package
```

## 6. 部署说明

### 6.1 数据库迁移

服务启动时 Flyway 会自动执行 V9 迁移脚本。

**手动执行**（如需）：

```bash
# 进入 MySQL 容器
docker exec -it springaI-chatbot-mysql-1 mysql -u root -p

# 执行迁移
USE chatbot;
SOURCE /path/to/V9__add_unique_index_event_id.sql;
```

### 6.2 服务部署

```bash
# 本地开发
docker compose up -d --build chatbot-service

# 生产部署
ssh -i ~/.ssh/id_rsa ubuntu@<server-ip>
cd /opt/springai-chatbot
git fetch origin && git reset --hard origin/main
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
```

## 7. 验证清单

### 7.1 主链路保存成功

- [ ] 调用 saveChatAndOutbox
- [ ] chat_record 写入成功
- [ ] chat_event_outbox 写入 PENDING
- [ ] 返回 ChatRecord

### 7.2 事务一致性

- [ ] 模拟 outbox insert 失败
- [ ] chat_record 不应残留
- [ ] chat_event_outbox 不应残留

### 7.3 Kafka 不可用不影响上下文

- [ ] Kafka 发送失败
- [ ] chat_record 已存在
- [ ] outbox 保持 PENDING/FAILED_RETRY
- [ ] 下一轮上下文能从 Redis/MySQL 读到上一轮

### 7.4 Consumer 不再重复写聊天记录

- [ ] 收到 CHAT_COMPLETED
- [ ] 不 insert chat_record
- [ ] 检查 chat_record 存在后 ack

### 7.5 Redis 失败不影响主链路

- [ ] chat_record + outbox 事务成功
- [ ] Redis append 抛异常
- [ ] 主链路不失败
- [ ] 缓存被删除或记录 warn
- [ ] 下次可从 MySQL 回填

## 8. 后续优化建议

### 8.1 第二版扩展

- [ ] 摘要刷新完全 Kafka 化
- [ ] 统计消费者
- [ ] 审计消费者
- [ ] RAG 问答沉淀消费者
- [ ] 通知消费者重构

### 8.2 监控告警

- [ ] outbox 积压监控
- [ ] Kafka 发送失败告警
- [ ] 消费延迟告警

### 8.3 性能优化

- [ ] 批量写入 outbox
- [ ] 异步 Redis 更新
- [ ] Kafka 发送异步化

## 9. 面试表达

### 9.1 完整版本

> 我没有把 Kafka 当成数据库用。聊天记录是用户下一轮对话必须立即读到的事实，所以先写 MySQL，并在事务里同时写 outbox。事务提交后更新 Redis，保证下一轮上下文马上可见。Kafka 负责把"聊天已完成"这个已落库事实异步广播给摘要、统计、索引、通知和审计等下游任务。这样既避免 Kafka 消费延迟导致上下文缺失，也避免服务在发 Kafka 前宕机造成事件丢失。

### 9.2 简短版本

> MySQL 保存事实，Redis 加速上下文，Outbox 保证事件不丢，Kafka 解耦异步副作用。

## 10. 相关文档

- 原始计划：`docs/kafka-outbox-context-consistency-plan.md`
- 本文档：`docs/kafka-outbox-implementation-result.md`
- CLAUDE.md：项目上下文和开发规范
