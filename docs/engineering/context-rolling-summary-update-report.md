# 上下文窗口与滚动摘要改造报告

## 改造背景

原上下文配置偏保守：Redis 每个 `sessionId` 默认只保留 30 条候选历史，最终 prompt 预算为 12000 字符。按当前主力文本模型 `deepseek-chat` 的上下文能力评估，这没有充分利用云端模型窗口；同时如果 Redis 候选池扩大到 100 条，原摘要逻辑会每次把候选历史整体重新总结，成本和信息抖动都会增加。

## 本次修改

1. 扩大默认上下文预算，面向 DeepSeek 主路径：
   - `recent-window-size`: 6 -> 10
   - `redis-cache-size`: 30 -> 100
   - `relevant-history-candidate-size`: 50 -> 80
   - `relevant-history-top-k`: 3 -> 5
   - `summary-max-chars`: 1200 -> 2000
   - `max-context-chars`: 12000 -> 30000

2. 摘要逻辑改为滚动增量摘要：
   - 首次摘要：总结当前候选历史。
   - 后续摘要：读取已有 `chat:summary:{sessionId}`，只合并 `chat:summary:meta:{sessionId}` 之后新增的记录。
   - 摘要成功后更新 `chat:summary:meta:{sessionId}` 为当前最新 `recordId`。

3. 摘要从 Redis-only 改为 MySQL 持久化 + Redis 缓存：
   - 新增 `chat_session_summary` 表，按 `session_id` 唯一保存当前滚动摘要。
   - `chat:summary:{sessionId}` 和 `chat:summary:meta:{sessionId}` 只作为 Redis 缓存，过期后可从 MySQL 回填。
   - 删除会话时同步删除持久摘要；普通缓存驱逐不会删除 MySQL 摘要。

4. 保持原降级边界：
   - 摘要生成失败只跳过，不影响主聊天。
   - Redis 读取失败仍回退 MySQL。
   - Redis 写入失败仍清理当前 session 上下文，避免脏缓存继续被使用。

5. Redis 最近历史改为幂等追加：
   - 新增 `chat:history:ids:{sessionId}` 作为最近窗口 recordId 索引。
   - 主链路在 `chat_record + chat_event_outbox` 事务成功后，同步幂等追加 Redis，保证用户下一轮对话尽快看到上一轮原文。
   - Kafka consumer 消费 outbox 事件后，再同步幂等追加一次 Redis 作为补偿；如果补偿失败，consumer 抛异常交给 Kafka 重试/DLT。
   - 摘要刷新不在主链路触发，改为 Kafka consumer 补偿 Redis 后异步触发。

## 当前上下文层级

```text
+ 系统提示词
+ RAG 引用
+ MySQL 持久摘要（Redis 缓存，最多 2000 字）
+ 相关旧历史 Top 5
+ 最近 10 条原文问答
+ 当前用户输入
+ <= 30000 字符预算
```

## 为什么不每次压缩最近 100 条

如果每新增 6 条就重新总结最近 100 条，摘要成本会随着候选池扩大而上升，而且模型每次重写摘要可能遗漏或改写已有稳定信息。滚动摘要使用“旧摘要 + 新增记录”的方式，输入更小、语义更稳定，也更接近长期记忆的维护方式。

## Redis 100 条窗口和摘要的关系

Redis 的 `chat:history:{sessionId}` 是最近原文候选窗口，严格按条数滚动：新增记录进入 list 后执行 `trim(key, -100, -1)`，所以第 101-106 条进入时，第 1-6 条原文会从 Redis list 中出去。

摘要不是 100 条历史的一一对应压缩副本，而是一份长期语义记忆。第 1-6 条原文离开 Redis 后，其中仍长期有效的信息（例如用户偏好、部署约束、未完成事项）应该继续保留在摘要里；闲聊、重复、已解决的细节会在滚动摘要更新时被丢弃。

当前摘要刷新策略是：

```text
已有摘要
+ 当前 session 中上次摘要之后新增的记录
+-> 新摘要
+-> 写入 MySQL
+-> 回填 Redis 缓存
```

刷新判断也改为按当前 session 实际新增记录数量计算，而不是用全局 `recordId` 差值估算，避免多用户多会话并发时误判。

## 当前事件链路

```text
聊天请求完成
  -> MySQL 事务写 chat_record + chat_event_outbox
  -> 主链路同步幂等追加 Redis 最近 100 条
  -> 请求结束

outbox dispatcher
  -> 发送 Kafka topic

Kafka consumer
  -> 根据 eventId 查询 chat_record
  -> 同步幂等追加 Redis 最近 100 条作为补偿
  -> 异步触发滚动摘要刷新
  -> ACK
```

## 后续建议

- 为 DeepSeek、Ollama、LLaVA 做按模型区分的上下文预算；当前全局 30000 字符适合 DeepSeek 主路径，但不适合未配置 `num_ctx` 的本地 Ollama。
- 增加 token 估算或 tokenizer，避免仅用 Java 字符数裁剪 prompt。
