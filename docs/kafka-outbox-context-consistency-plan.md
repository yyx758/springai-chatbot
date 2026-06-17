# Kafka、Outbox 与聊天上下文一致性修正计划

## 1. 背景问题

当前聊天记录链路大致是：

~~~text
构建上下文：Redis 优先，MySQL fallback
-> 调用 AI 模型流式输出
-> 模型输出结束后构造 ChatEvent
-> ChatEventProducer 同步发送 Kafka
-> Kafka Consumer 消费事件
-> Consumer 写入 chat_record
-> Consumer 追加 Redis 上下文缓存
~~~

这个链路存在两个核心问题。

第一，用户下一轮对话可能读不到上一轮内容。上下文构建只读取 Redis/MySQL 中已经存在的历史，如果上一轮事件还在 Kafka 中等待消费，或者 Kafka 发送失败进入 outbox 重试窗口，下一轮请求就可能缺少上一轮问答。

第二，当前 outbox 不是严格的 transactional outbox。现有逻辑是“先发 Kafka，Kafka 失败后再写 outbox”。如果服务在准备发 Kafka 或发送过程中宕机，事件可能既没有进入 Kafka，也没有进入 outbox。

因此需要调整职责边界：

~~~text
MySQL：保存聊天事实
Redis：保证下一轮上下文快速可见
Outbox：保证事实落库后事件最终能发送
Kafka：广播已落库事实，驱动摘要、统计、索引、通知、审计等异步副作用
~~~

## 2. 修正目标

目标链路调整为：

~~~text
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
~~~

修正后需要满足：

- Kafka 未消费时，下一轮上下文仍然不缺上一轮内容。
- Kafka 不可用时，聊天记录仍然已写入 MySQL。
- 服务在写库后、发 Kafka 前宕机时，outbox 仍能在服务恢复后继续补发。
- Redis 不可用时，不影响聊天记录落库；下次可从 MySQL 回填。
- Kafka Consumer 不再作为聊天记录的唯一落库入口，避免把用户上下文可见性绑定到 Kafka 消费延迟。

## 3. Kafka 在修正后的定位

Kafka 不是删除，而是从“聊天记录主落库链路”调整为“异步事件总线”。

适合 Kafka 处理的任务：

- 会话摘要刷新。
- 对话统计、模型调用统计、RAG 命中统计。
- 管理员通知、异常告警、邮件事件。
- RAG 知识库索引更新，例如 PGVector、Elasticsearch 索引维护。
- 审计日志，例如登录、文件访问、管理操作、权限拒绝。
- 后续事件重放，例如重建统计、重建索引、重新生成摘要。

不适合 Kafka 独立承担的任务：

- 用户下一轮对话必须立即读取到的聊天记录事实。
- 用户可见的强一致状态变更。

## 4. 代码修正范围

### 4.1 新增 ChatRecordPersistenceService

建议新增文件：

~~~text
chatbot-service/src/main/java/com/example/chatbot/service/ChatRecordPersistenceService.java
~~~

职责：

~~~java
@Transactional
public ChatRecord saveChatAndOutbox(ChatEvent event) {
    // 1. ensureEventId(event)
    // 2. 根据 eventId 幂等检查 chat_record
    // 3. insert chat_record
    // 4. insert chat_event_outbox，status = PENDING
    // 5. return chatRecord
}
~~~

要求：

- chat_record 和 chat_event_outbox 在同一个数据库事务中写入。
- eventId 作为幂等键。
- 如果 outbox 写入失败，chat_record 也应回滚。
- Redis 更新不放入数据库事务。

### 4.2 调整 ChatbotService

当前 ChatbotService.asyncSaveChatRecordWithImage(...) 构造 ChatEvent 后直接调用 ChatEventProducer.sendChatEvent(event)。

修正为：

~~~text
构造 ChatEvent
-> chatRecordPersistenceService.saveChatAndOutbox(event)
-> chatContextService.appendPersistedRecordToCache(chatRecord)
~~~

建议将方法名从 asyncSaveChatRecord... 改为更准确的：

~~~text
saveCompletedChatRecord(...)
saveCompletedChatRecordWithImage(...)
~~~

异常策略：

- MySQL/outbox 事务失败：记录 error 日志；SSE 场景尽量发送 save_failed 事件；不写 Redis。
- Redis append 失败：不影响主结果；由 ChatContextService 内部删除该 session 缓存，下次从 MySQL 回填。

### 4.3 调整 ChatEventOutboxService

现有 savePending(ChatEvent event, String errorMessage) 是 Kafka 失败后的补救入口。

修正后，outbox 的创建由事务内保存逻辑完成。ChatEventOutboxService 主要保留 dispatcher 职责：

~~~text
扫描 PENDING / FAILED_RETRY
-> 发送 Kafka
-> 成功标记 SENT
-> 失败更新 retry_count、last_error、next_retry_time
~~~

保留指数退避：

~~~text
1s -> 2s -> 4s -> 8s -> ... -> 最大 300s
~~~

### 4.4 调整 ChatEventProducer

当前 ChatEventProducer.sendChatEvent(...) 会同步等待 Kafka ack，最多 3 次、每次 5 秒，最坏可能阻塞 15 秒。

修正方案：

- 主链路不再调用 ChatEventProducer。
- outbox dispatcher 可以直接使用 KafkaTemplate 发送。
- 如果保留 ChatEventProducer，也只允许由 outbox dispatcher 调用，不允许由 ChatbotService 直接调用。

推荐做法：简化为 ChatEventOutboxService 内部直接使用 KafkaTemplate。

### 4.5 调整 ChatEventConsumer

当前 Consumer 职责是：

~~~text
Kafka -> insert chat_record -> append Redis
~~~

修正后不再由 Consumer 写聊天记录。它应改为处理异步副作用：

~~~text
Kafka -> 摘要刷新 / 统计 / 审计 / 通知 / 索引维护
~~~

第一版最小实现可以先做：

~~~text
收到 CHAT_COMPLETED
-> 根据 eventId 检查 chat_record 是否存在
-> 存在则 ack
-> 不存在则抛异常，让 Kafka error handler 重试
~~~

这样可以防止异常状态下“事件已经广播，但事实记录不存在”。

## 5. 数据库迁移建议

需要确认以下字段和唯一约束。

建议新增迁移：

~~~sql
ALTER TABLE chat_record
ADD UNIQUE KEY uk_chat_record_event_id (event_id);

ALTER TABLE chat_event_outbox
ADD UNIQUE KEY uk_chat_event_outbox_event_id (event_id);
~~~

注意：

- MySQL 唯一索引允许多个 NULL，因此新链路必须保证所有新聊天记录都有非空 event_id。
- 如果线上已有重复或空 event_id 数据，迁移前需要先清理或采用更谨慎的分步迁移。

## 6. 测试计划

### 6.1 主链路保存成功

验证：

~~~text
调用 saveChatAndOutbox
-> chat_record 写入成功
-> chat_event_outbox 写入 PENDING
-> 返回 ChatRecord
~~~

### 6.2 事务一致性

模拟 outbox insert 失败：

~~~text
chat_record 不应残留
chat_event_outbox 不应残留
~~~

### 6.3 Kafka 不可用不影响上下文

验证：

~~~text
Kafka 发送失败
-> chat_record 已存在
-> outbox 保持 PENDING/FAILED_RETRY
-> 下一轮上下文能从 Redis/MySQL 读到上一轮
~~~

### 6.4 Consumer 不再重复写聊天记录

验证：

~~~text
收到 CHAT_COMPLETED
-> 不 insert chat_record
-> 检查 chat_record 存在后 ack
~~~

### 6.5 Redis 失败不影响主链路

验证：

~~~text
chat_record + outbox 事务成功
Redis append 抛异常
-> 主链路不失败
-> 缓存被删除或记录 warn
-> 下次可从 MySQL 回填
~~~

## 7. 推荐实施顺序

~~~text
1. 新增 ChatRecordPersistenceService
2. 修改 ChatbotService：模型完成后同步写 chat_record + outbox，再 append Redis
3. 从主链路移除 ChatEventProducer
4. 保留 ChatEventOutboxService dispatcher 发 Kafka
5. 修改 ChatEventConsumer：不再 insert chat_record
6. 新增唯一索引 migration
7. 补充单元测试
8. 运行相关测试和构建
~~~

建议验证命令：

~~~bash
mvn -q -pl chatbot-service -Dtest=KafkaReliabilityTest test
mvn -q -pl chatbot-service -Dtest=ChatContextServiceTest test
mvn -q -pl chatbot-service test
mvn -q -DskipTests package
~~~

## 8. 第一版最小交付边界

第一版只解决上下文一致性和宕机不丢事件问题。

必须做：

- 聊天完成后同步写 chat_record。
- 同一事务写 chat_event_outbox。
- 事务成功后 append Redis。
- outbox 定时发送 Kafka。
- Consumer 不再重复 insert chat_record。

暂缓：

- 摘要刷新完全 Kafka 化。
- 统计消费者。
- 审计消费者。
- RAG 问答沉淀消费者。
- 通知消费者重构。

## 9. 面试表达

可以这样解释：

> 我没有把 Kafka 当成数据库用。聊天记录是用户下一轮对话必须立即读到的事实，所以先写 MySQL，并在事务里同时写 outbox。事务提交后更新 Redis，保证下一轮上下文马上可见。Kafka 负责把“聊天已完成”这个已落库事实异步广播给摘要、统计、索引、通知和审计等下游任务。这样既避免 Kafka 消费延迟导致上下文缺失，也避免服务在发 Kafka 前宕机造成事件丢失。

更简短的版本：

> MySQL 保存事实，Redis 加速上下文，Outbox 保证事件不丢，Kafka 解耦异步副作用。
