# 上下文模块优化实现说明

## 1. 现在的整体效果

本次改造后，上下文不再只依赖 `app.chatbot.max-history=5` 的固定滑动窗口，而是统一走 `ChatContextService` 构建“分层上下文”。

最终 prompt 的顺序是：

1. 系统提示词：定义 AI Studio 助手或 Agent 的行为边界。
2. RAG 知识库引用：普通聊天启用 RAG 时拼入知识库检索结果。
3. 会话摘要：把更早历史压缩成一段长期上下文。
4. 相关旧历史：从较早历史里按当前问题关键词匹配 Top-K。
5. 最近原文历史：保留最近 `recent-window-size` 条原始问答。
6. 当前用户输入：本轮问题。

对应代码在 `chatbot-service/src/main/java/com/example/chatbot/service/ChatContextService.java`：

```java
messages.add(new SystemMessage(systemPrompt));
List<RagReference> references = resolveRagReferences(...);
if (!references.isEmpty()) {
    messages.add(new SystemMessage(ragService.buildKnowledgePrompt(references)));
}

appendLayeredHistory(messages, sessionId, userInput);
messages.add(new UserMessage(userInput));
return new ConversationContext(trimToBudget(messages), references);
```

Agent 也复用同一个服务，只是不拼 RAG：

```java
messages.add(new SystemMessage(systemPrompt));
appendLayeredHistory(messages, sessionId, userInput);
messages.add(new UserMessage(userInput));
return trimToBudget(messages);
```

## 2. 普通聊天入口如何接入

`ChatbotService` 原来自己读取 Redis/MySQL 并拼接历史，现在只负责调用统一入口。

代码位置：`chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java`

```java
private ConversationContext buildConversationContext(
        String sessionId,
        String userInput,
        String userId,
        Boolean useRag,
        Integer ragTopK
) {
    return chatContextService.buildConversationContext(
            sessionId,
            userInput,
            userId,
            systemPrompt,
            useRag,
            ragTopK,
            ragEnabledDefault,
            ragTopKDefault
    );
}
```

普通流式聊天、multipart 图文聊天、fileKey 图文聊天都继续调用这个私有方法，所以接口不用改，前端也不用联动。

## 3. Agent 如何接入

`AgentService` 之前单独查最近历史，现在改成：

代码位置：`chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java`

```java
private List<Message> buildMessages(ChatRequest request, String userId) {
    return chatContextService.buildConversationMessages(
            request.getSessionId(),
            request.getMessage(),
            buildSystemPrompt()
    );
}
```

这样 Agent 默认 prompt 和普通聊天共享同一套 Redis 缓存、摘要、相关历史和最近窗口逻辑，避免两条链路上下文行为不一致。

## 4. 候选历史从哪里来

候选历史入口是 `loadCandidateHistory(sessionId)`。

第一步先读 Redis：

```java
List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);
if (rawHistory != null && !rawHistory.isEmpty()) {
    List<ChatRecord> history = rawHistory.stream()
            .map(this::toChatRecord)
            .sorted(Comparator.comparing(ChatRecord::getCreatedTime, ...))
            .toList();
    return history;
}
```

Redis key 仍然是：

```text
chat:history:{sessionId}
```

但语义变了：以前它基本等同于“prompt 最近 5 条”，现在它是“上下文候选历史缓存”，默认最多缓存 30 条。

Redis 未命中或异常时，降级查 MySQL：

```java
List<ChatRecord> history = new ArrayList<>(chatRecordMapper.selectList(
        new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getSessionId, sessionId)
                .orderByDesc(ChatRecord::getCreatedTime)
                .last("LIMIT " + limit)));
Collections.reverse(history);
```

查回来的历史会异步写回 Redis：

```java
redisTemplate.delete(key);
redisTemplate.opsForList().rightPushAll(key, cacheRecords.toArray());
redisTemplate.opsForList().trim(key, -positive(properties.getRedisCacheSize(), 30), -1);
redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
```

也就是说，读路径是典型 Cache-Aside：Redis 命中直接用，未命中查库并回填。

## 5. 分层上下文如何拼装

核心方法是 `appendLayeredHistory(...)`：

```java
List<ChatRecord> history = loadCandidateHistory(sessionId);
String summary = readSummary(sessionId);
if (summary != null && !summary.isBlank()) {
    messages.add(new SystemMessage("Earlier conversation summary for long-term context:\n" + summary));
}

int recentSize = positive(properties.getRecentWindowSize(), 6);
int split = Math.max(0, history.size() - recentSize);
List<ChatRecord> olderHistory = history.subList(0, split);
List<ChatRecord> recentHistory = history.subList(split, history.size());
```

这里先把候选历史拆成两段：

- `olderHistory`：超过最近窗口的旧历史。
- `recentHistory`：最近 `recent-window-size` 条原文历史，默认 6 条。

然后从旧历史里挑相关片段：

```java
List<ChatRecord> relevantHistory = selectRelevantHistory(olderHistory, recentIds, userInput);
if (!relevantHistory.isEmpty()) {
    messages.add(new SystemMessage(buildHistoryBlock("Relevant earlier conversation snippets:", relevantHistory)));
}
```

最后最近历史按原始问答加入：

```java
for (ChatRecord record : recentHistory) {
    addRecordMessages(messages, record);
}
```

所以它不是“摘要替代全部历史”，而是：

- 摘要负责长期目标、偏好、事实、约束。
- 相关历史负责补回和当前问题有关的旧细节。
- 最近历史负责连续对话的上下文准确性。

## 6. 摘要压缩具体怎么做

摘要压缩不是简单截断，也不是 embedding 聚类。目前采用的是“LLM 语义摘要压缩”：

1. 先把最近候选历史转成纯文本 transcript。
2. 给现有 `ChatModel` 一个明确的摘要指令。
3. 要求模型只保留目标、偏好、事实、约束、待办，不允许编造。
4. 生成后按 `summary-max-chars` 截断并写入 Redis。

触发逻辑在 `refreshSummaryIfNeeded(...)`：

```java
Long size = redisTemplate.opsForList().size(historyKey(sessionId));
long historySize = size == null ? 0L : size;
int trigger = positive(properties.getSummaryTriggerRecords(), 12);
int refreshEvery = positive(properties.getSummaryRefreshEveryRecords(), 6);
if (historySize < trigger || (historySize - trigger) % refreshEvery != 0) {
    return;
}
```

默认含义：

- Redis 历史少于 12 条时不生成摘要。
- 到 12 条时生成一次。
- 之后理论上每增加 6 条刷新一次。

需要注意一个实现边界：Redis List 达到 `redis-cache-size=30` 后，长度会长期保持 30。当前触发条件基于 List 长度，所以达到 30 后可能每次追加都满足刷新条件。后续可以增加 `chat:summary:meta:{sessionId}`，记录上次摘要的最大 `recordId` 或已处理条数，做到严格“每 6 条新增记录刷新一次”。

摘要输入由 `toSummaryLine(...)` 生成：

```java
private String toSummaryLine(ChatRecord record) {
    String imageHint = record.getImageData() == null || record.getImageData().isBlank() ? "" : " [image]";
    return "User" + imageHint + ": " + limit(nullToEmpty(record.getUserMessage()), 600)
            + "\nAssistant: " + limit(nullToEmpty(record.getBotResponse()), 600);
}
```

这里的压缩依据是每条 `ChatRecord` 的：

- `userMessage`
- `botResponse`
- 是否带图片的提示标记 `[image]`

图片本体不会进入摘要，避免 base64/fileKey 把摘要缓存撑大。

实际摘要 prompt 在 `generateSummary(...)`：

```java
String transcript = history.stream()
        .map(this::toSummaryLine)
        .collect(Collectors.joining("\n"));
List<Message> messages = List.of(
        new SystemMessage("Summarize customer-service chat history. Keep only goals, user preferences, facts, constraints, and open follow-ups."),
        new UserMessage("Summarize the following chat history in Chinese within "
                + positive(properties.getSummaryMaxChars(), 1200)
                + " characters. Do not invent facts:\n\n" + transcript)
);
String text = model.call(new Prompt(messages)).getResult().getOutput().getText();
```

所以摘要压缩的判断标准来自系统指令：

- 用户目标：用户最终想解决什么。
- 用户偏好：比如语言、格式、方案倾向。
- 明确事实：用户已经提供过的业务信息、错误信息、环境信息。
- 约束条件：不要改接口、不要加数据库表、只能走 Gateway 等。
- 未完成事项：当前还需要继续处理的任务或开放问题。

这类信息保留在 `chat:summary:{sessionId}` 中：

```java
redisTemplate.opsForValue().set(
        summaryKey(sessionId),
        limit(summary, properties.getSummaryMaxChars()),
        CACHE_TTL_HOURS,
        TimeUnit.HOURS
);
```

模型选择逻辑是：

```java
if (openAiChatModel != null && openAiApiKey != null
        && !openAiApiKey.isBlank()
        && !"sk-placeholder".equals(openAiApiKey)) {
    return openAiChatModel;
}
return ollamaChatModel;
```

也就是优先使用已配置的 DeepSeek/OpenAI 兼容模型；没有有效 key 时降级 Ollama。生成失败时返回空字符串，主聊天流程继续执行。

## 7. 相关旧历史如何筛选

相关历史不是向量检索，当前是轻量关键词评分。

先从当前问题提取词项：

```java
for (String token : normalized.split("[\\s\\p{Punct}]+")) {
    if (token.length() >= 2) {
        terms.add(token);
    }
}
String compact = normalized.replaceAll("[\\s\\p{Punct}]+", "");
for (int i = 0; i < compact.length() - 1; i++) {
    terms.add(compact.substring(i, i + 2));
}
```

这里同时保留：

- 空格/标点切分出来的 token。
- 连续 2-gram，增强中文短语匹配。

然后对旧历史计分：

```java
private int relevanceScore(ChatRecord record, Set<String> queryTerms) {
    String text = normalize(record.getUserMessage() + " " + record.getBotResponse());
    int score = 0;
    for (String term : queryTerms) {
        if (text.contains(term)) {
            score += Math.max(1, term.length());
        }
    }
    return score;
}
```

筛选逻辑：

```java
return olderHistory.subList(start, olderHistory.size()).stream()
        .filter(record -> record.getId() == null || !excludedIds.contains(record.getId()))
        .map(record -> new ScoredRecord(record, relevanceScore(record, queryTerms)))
        .filter(scored -> scored.score() > 0)
        .sorted(Comparator.comparingInt(ScoredRecord::score).reversed()
                .thenComparing(scored -> scored.record().getCreatedTime(), ...))
        .limit(topK)
        .map(ScoredRecord::record)
        .sorted(Comparator.comparing(ChatRecord::getCreatedTime, ...))
        .toList();
```

关键点：

- 只从 `olderHistory` 里选，避免和最近历史重复。
- 默认只看较新的 50 条候选旧历史。
- 默认取 Top 3。
- 选出后按时间升序放入 prompt，保持阅读顺序。

## 8. 新记录写入后如何维护 Redis

聊天记录仍然通过 Kafka 异步落库。

代码位置：`chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventConsumer.java`

```java
chatRecordMapper.insert(chatRecord);

try {
    chatContextService.appendPersistedRecordToCache(chatRecord);
} catch (Exception e) {
    log.warn("上下文缓存更新失败，SessionId: {}, Error: {}", event.getSessionId(), e.getMessage());
}

ack.acknowledge();
```

也就是：

1. Kafka 消费到聊天事件。
2. 先写 MySQL。
3. 写库成功后追加 Redis 历史。
4. Redis 更新失败只记录日志，不影响 ACK 前的主流程。

`appendPersistedRecordToCache(...)` 内部做三件事：

```java
redisTemplate.opsForList().rightPush(key, chatRecord);
redisTemplate.opsForList().trim(key, -cacheSize, -1);
redisTemplate.expire(key, CACHE_TTL_HOURS, TimeUnit.HOURS);
refreshSummaryIfNeededAsync(chatRecord.getSessionId());
```

这里的 `trim(key, -cacheSize, -1)` 表示只保留 List 末尾最新的 `redis-cache-size` 条，默认 30 条。

## 9. 删除会话时如何清理

`ChatbotService.deleteSession(...)` 删除会话后调用：

```java
chatContextService.evictSessionContext(sessionId);
```

`ChatContextService` 会同步删除两个 key：

```java
redisTemplate.delete(historyKey(sessionId));
redisTemplate.delete(summaryKey(sessionId));
```

对应 key：

```text
chat:history:{sessionId}
chat:summary:{sessionId}
```

## 10. 配置项

配置位置：`chatbot-service/src/main/resources/application.yml`

```yaml
app:
  chatbot:
    context:
      recent-window-size: ${APP_CHATBOT_CONTEXT_RECENT_WINDOW_SIZE:6}
      redis-cache-size: ${APP_CHATBOT_CONTEXT_REDIS_CACHE_SIZE:30}
      relevant-history-enabled: ${APP_CHATBOT_CONTEXT_RELEVANT_HISTORY_ENABLED:true}
      relevant-history-candidate-size: ${APP_CHATBOT_CONTEXT_RELEVANT_HISTORY_CANDIDATE_SIZE:50}
      relevant-history-top-k: ${APP_CHATBOT_CONTEXT_RELEVANT_HISTORY_TOP_K:3}
      summary-enabled: ${APP_CHATBOT_CONTEXT_SUMMARY_ENABLED:true}
      summary-trigger-records: ${APP_CHATBOT_CONTEXT_SUMMARY_TRIGGER_RECORDS:12}
      summary-refresh-every-records: ${APP_CHATBOT_CONTEXT_SUMMARY_REFRESH_EVERY_RECORDS:6}
      summary-max-chars: ${APP_CHATBOT_CONTEXT_SUMMARY_MAX_CHARS:1200}
      max-context-chars: ${APP_CHATBOT_CONTEXT_MAX_CONTEXT_CHARS:12000}
```

含义：

- `recent-window-size`：最近原文历史窗口大小。
- `redis-cache-size`：Redis 候选历史缓存大小。
- `relevant-history-enabled`：是否启用相关旧历史。
- `relevant-history-candidate-size`：相关性筛选时最多看多少条旧历史。
- `relevant-history-top-k`：最终放入 prompt 的相关旧历史条数。
- `summary-enabled`：是否启用摘要。
- `summary-trigger-records`：累计到多少条候选历史后开始摘要。
- `summary-refresh-every-records`：理论上每新增多少条刷新摘要。
- `summary-max-chars`：摘要最大字符数。
- `max-context-chars`：最终上下文字符预算。

`app.chatbot.max-history` 保留兼容，但新上下文逻辑优先读取 `app.chatbot.context.recent-window-size`。

## 11. 上下文预算如何兜底

最后会调用 `trimToBudget(...)`：

```java
while (totalChars(result) > budget && result.size() > 2) {
    result.remove(1);
}
```

当前策略是按消息顺序从第 2 条开始删除，保证系统提示词和当前输入尽量保留。因为 prompt 顺序是系统提示词、RAG、摘要、相关历史、最近历史、当前输入，所以极端超长时会优先删掉靠前的补充上下文。

后续如果要更精细，可以改成按层级优先级裁剪：先裁相关历史，再裁摘要，最后裁最近历史。

## 12. 当前降级边界

- Redis 读失败：降级 MySQL，并尝试清理坏缓存。
- Redis 写失败：删除当前 session 的历史缓存和摘要缓存，避免脏数据继续被使用。
- 摘要生成失败：跳过摘要，不影响主对话。
- 没有可用摘要模型：跳过摘要，不影响主对话。
- 相关历史无命中：只使用摘要和最近原文历史。
- 图片不会进入摘要正文，只保留 `[image]` 提示。
- 摘要当前只存 Redis，TTL 默认 2 小时，不新增数据库表。

## 13. 已验证内容

新增和更新的测试覆盖：

- `ChatContextServiceTest`
  - Redis 命中时按摘要、相关历史、最近历史组装。
  - Redis 未命中时从 MySQL 查候选历史并回填 Redis。
  - 最近历史不超过 `recent-window-size`。
  - 摘要生成失败时主流程降级成功。
  - 相关历史不会重复加入最近历史。
- `KafkaReliabilityTest`
  - 消费成功后验证会调用 `ChatContextService.appendPersistedRecordToCache(...)`。

已执行过的构建命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,KafkaReliabilityTest" test
mvn -q -pl chatbot-service -DskipTests package
```

说明：完整 `mvn -q -pl chatbot-service test` 会触发已有端到端/集成测试，这些测试依赖本机 8080/9000 服务和 Kafka `localhost:9092`，在未启动完整环境时不适合作为本次代码改造的快速验证命令。
