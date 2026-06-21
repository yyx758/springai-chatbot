# ChatbotService 代码逐方法详解

> 本文档从最底层的工具方法开始讲解，逐层向上追溯调用链。
> 原则：**如果一个方法调用了其他方法，先讲被调用的方法，再讲调用者。**
>
> 源文件：`src/main/java/com/example/chatbot/service/ChatbotService.java`

---

## 一、类概览

```java
@Service
@Slf4j
public class ChatbotService {

    // ── 模型 Bean（通过 ObjectProvider 安全注入，可能为 null）──
    private final OpenAiChatModel openAiChatModel;       // DeepSeek（通过 OpenAI 兼容协议）
    private final OllamaChatModel ollamaChatModel;        // Ollama（本地部署）

    // ── 基础设施 ──
    private final ChatRecordMapper chatRecordMapper;      // MyBatis-Plus，操作 chat_record 表
    private final RedisTemplate<String, Object> redisTemplate;  // Redis 操作
    private final ObjectMapper objectMapper;              // Jackson JSON 序列化
    private final RagService ragService;                  // RAG 知识检索服务

    // ── 并发控制 ──
    private final Semaphore ollamaSemaphore = new Semaphore(1);  // Ollama 单线程推理，同一时间只允许 1 个请求

    // ── 配置项（从 application.yml 读取）──
    @Value("${app.chatbot.system-prompt}")
    private String systemPrompt;           // 系统提示词（角色设定、行为约束）
    @Value("${app.chatbot.max-history:5}")
    private int maxHistory;                // 最大历史消息数（默认 5 条）
    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;           // DeepSeek API Key
    @Value("${app.chatbot.rag-enabled:true}")
    private boolean ragEnabledDefault;     // RAG 默认是否启用（默认 true）
    @Value("${app.chatbot.rag-top-k:3}")
    private int ragTopKDefault;            // RAG 默认返回文档数（默认 3 篇）
}
```

**依赖关系总览**：

```
ChatbotService
├── OpenAiChatModel       ← Spring AI 自动装配（连接 DeepSeek API）
├── OllamaChatModel       ← Spring AI 自动装配（连接本地 Ollama 服务）
├── ChatRecordMapper      ← MyBatis-Plus Mapper，操作 chat_record 表
├── RedisTemplate         ← 操作 Redis 缓存（聊天历史、刷新令牌等）
├── ObjectMapper          ← Jackson 的 JSON 序列化/反序列化工具
├── RagService            ← 自定义的 RAG 知识检索服务（关键词匹配）
└── Semaphore             ← Java 并发工具，控制 Ollama 的并发数为 1
```

---

## 二、构造函数

```java
@Autowired
public ChatbotService(
        ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
        ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
        ChatRecordMapper chatRecordMapper,
        RedisTemplate<String, Object> redisTemplate,
        ObjectMapper objectMapper,
        RagService ragService
) {
    // 赋值基础组件
    this.chatRecordMapper = chatRecordMapper;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.ragService = ragService;

    // 安全获取 OpenAI/DeepSeek 模型 Bean
    OpenAiChatModel openAi = null;
    try {
        openAi = openAiChatModelProvider.getIfAvailable();
    } catch (Exception e) {
        log.warn("OpenAI/DeepSeek 模型不可用: {}", e.getMessage());
    }
    this.openAiChatModel = openAi;

    // 安全获取 Ollama 模型 Bean
    OllamaChatModel ollama = null;
    try {
        ollama = ollamaChatModelProvider.getIfAvailable();
    } catch (Exception e) {
        log.warn("Ollama 模型不可用: {}", e.getMessage());
    }
    this.ollamaChatModel = ollama;

    log.info("Chat models available - OpenAI: {}, Ollama: {}",
            openAiChatModel != null, ollamaChatModel != null);
}
```

### 为什么用 `ObjectProvider` 而不是直接 `@Autowired`？

Spring AI 的 starter 会在应用启动时自动创建 `OpenAiChatModel` 和 `OllamaChatModel` Bean。但如果创建过程失败（比如 API Key 没配、Ollama 服务不可达），不同注入方式的表现截然不同：

| 注入方式 | Bean 创建失败时的行为 |
|---|---|
| `@Autowired OpenAiChatModel` | **整个应用启动失败**，无法访问任何功能 |
| `ObjectProvider<OpenAiChatModel>` + `getIfAvailable()` | Bean 不可用时返回 `null`，**应用正常启动** |

`ObjectProvider` 是 Spring 提供的**延迟注入**工具。`getIfAvailable()` 的语义是：如果 Bean 已经创建好了就返回，没创建好就返回 `null`，**不会抛异常**。外层再套一层 `try-catch` 是为了防御 Bean 创建过程中抛出的意料之外的异常（如网络超时）。

**最终效果**：两个模型互不影响。DeepSeek 挂了，Ollama 还能用；Ollama 挂了，DeepSeek 还能用。启动日志会明确显示哪个模型可用。

---

## 三、第一层：零依赖的工具方法

这些方法**不调用本类中的任何其他方法**，是最底层的"原子操作"。

---

### 3.1 `buildSessionPrefix()` — 构造会话 ID 前缀

```java
private String buildSessionPrefix(String userId) {
    return userId + "_";
}
```

**作用**：拼接出 `"{userId}_"` 字符串。

**使用场景**：
1. **生成新会话 ID**：`ChatbotController` 中调用 `userId + "_" + UUID.randomUUID()` 生成如 `"5_a3b7c-d4e5f-..."`
2. **查询用户的聊天记录**：用 SQL 的 `LIKE '5_%'` 匹配以该前缀开头的所有 sessionId

**为什么用 `_` 作为分隔符？** UUID 的格式是 `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`，只包含十六进制字符和连字符 `-`，不会出现下划线 `_`。所以 `"{userId}_"` 能唯一标识某个用户的会话前缀，不会和 UUID 部分混淆。

**示例**：用户 ID 为 `5`，则前缀为 `"5_"`。该用户的所有会话 ID 都以 `"5_"` 开头：`"5_a3b7c-..."`、`"5_f6g7h-..."` 等。

---

### 3.2 `shouldUseRag()` — 决定是否启用 RAG

```java
private boolean shouldUseRag(Boolean requestUseRag) {
    return requestUseRag == null ? ragEnabledDefault : requestUseRag;
}
```

**作用**：根据用户请求和系统配置，决定本次对话是否启用 RAG 检索。

**逻辑**：

| `requestUseRag`（用户传的值） | 返回值 | 含义 |
|---|---|---|
| `null`（没传） | `ragEnabledDefault`（默认 `true`） | 用系统默认值 |
| `true` | `true` | 用户明确要求启用 |
| `false` | `false` | 用户明确要求关闭 |

**为什么要这样设计？** 普通用户不需要知道 RAG 是什么，系统默认启用即可。高级用户或调试时可以通过前端开关手动关闭。

---

### 3.3 `resolveTopK()` — 确定 RAG 返回文档数

```java
private Integer resolveTopK(Integer requestTopK) {
    if (requestTopK == null || requestTopK <= 0) {
        return ragTopKDefault;
    }
    return requestTopK;
}
```

**作用**：确定 RAG 检索返回多少篇知识文档。

**逻辑**：
- 没传（`null`）或传了 `0`、负数 → 用默认值 `3`（来自 `app.chatbot.rag-top-k` 配置）
- 传了正数 → 直接使用用户的值

**注意**：这里没有上限校验。如果用户传 `1000`，理论上会检索 1000 篇文档。`RagService` 中有 `MAX_TOP_K = 10` 的硬限制作为第二道防线，所以实际不会出问题。

---

### 3.4 `resolveErrorMessage()` — 技术错误翻译为用户友好提示

```java
private String resolveErrorMessage(Throwable err) {
    String msg = err.getMessage();
    if (msg == null) msg = "未知错误";

    // 401 鉴权失败
    if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("Incorrect API key"))
        return "API 密钥错误，请检查 DEEPSEEK_API_KEY 配置";

    // 429 请求限流
    if (msg.contains("429") || msg.contains("Rate limit") || msg.contains("Too Many Requests"))
        return "请求过于频繁，请稍后重试";

    // 超时
    if (msg.contains("timeout") || msg.contains("Timeout") || msg.contains("timed out"))
        return "AI 服务响应超时，请重试";

    // 连接失败
    if (msg.contains("Connection refused") || msg.contains("connect") || msg.contains("refused"))
        return "AI 服务连接失败，请检查网络或模型是否运行中";

    // 服务器内部错误
    if (msg.contains("500") || msg.contains("Internal Server Error"))
        return "AI 服务内部错误，请稍后重试";

    // 内容审查
    if (msg.contains("content_filter") || msg.contains("safety") || msg.contains("moderation"))
        return "内容被安全策略拦截，请修改提问内容";

    return "服务异常，请重试";
}
```

**作用**：把底层的 HTTP 错误码、英文异常信息翻译成用户能看懂的中文提示。

**匹配策略**：用 `String.contains()` 做关键词匹配，从最具体的错误开始匹配，匹配不到则返回通用提示。

**为什么不用 HTTP Status Code 数字判断？** 因为不同模型提供商抛出的异常格式不同。DeepSeek 可能返回 `"401 Unauthorized"`，Ollama 可能返回 `"Connection refused"`。用字符串匹配更通用，能覆盖各种异常来源。

**覆盖的错误场景**：

| 错误类型 | 异常信息中的关键词 | 用户看到的提示 |
|---|---|---|
| API Key 错误 | `401`, `Unauthorized`, `Incorrect API key` | "API 密钥错误，请检查 DEEPSEEK_API_KEY 配置" |
| 请求限流 | `429`, `Rate limit`, `Too Many Requests` | "请求过于频繁，请稍后重试" |
| 响应超时 | `timeout`, `Timeout`, `timed out` | "AI 服务响应超时，请重试" |
| 连接失败 | `Connection refused`, `connect`, `refused` | "AI 服务连接失败，请检查网络或模型是否运行中" |
| 服务器错误 | `500`, `Internal Server Error` | "AI 服务内部错误，请稍后重试" |
| 内容审查 | `content_filter`, `safety`, `moderation` | "内容被安全策略拦截，请修改提问内容" |
| 其他 | 未匹配到任何关键词 | "服务异常，请重试" |

---

## 四、第二层：调用第一层方法的方法

---

### 4.1 `sendStreamError()` — 通过 SSE 发送错误事件并关闭连接

```java
private void sendStreamError(SseEmitter emitter, String message) {
    try {
        // 发送一个名为 "error" 的 SSE 事件
        emitter.send(SseEmitter.event().name("error").data(Map.of("error", message)));
    } catch (Exception sendEx) {
        log.warn("SSE 错误发送失败: {}", sendEx.getMessage());
    }
    // 无论发送是否成功，都关闭连接
    emitter.complete();
}
```

**作用**：给前端发送一个错误事件，然后**立即关闭 SSE 连接**。

**详细流程**：
1. `SseEmitter.event().name("error")` → 创建一个 SSE 事件，事件名为 `"error"`
2. `.data(Map.of("error", message))` → 事件数据是 JSON：`{"error": "具体错误信息"}`
3. `emitter.send()` → 通过 HTTP 长连接发送给前端
4. `emitter.complete()` → 关闭连接

**为什么无论成功失败都要 `complete()`？** 走到这个方法说明对话已经无法继续了（模型不可用、校验失败等）。如果不关闭连接，前端会一直等待，直到 180 秒超时。

**前端收到的 SSE 数据格式**：

```
event:error
data:{"error":"AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY"}
```

---

### 4.2 `releaseIfOllama()` — 条件释放信号量

```java
private void releaseIfOllama(boolean acquired) {
    if (acquired) {
        ollamaSemaphore.release();
    }
}
```

**作用**：如果之前成功获取了信号量（`acquired == true`），就释放它，让排队中的下一个请求可以进入。

**为什么需要 `acquired` 参数？** 只有成功获取了信号量的请求才应该释放。如果获取失败（超时）或者根本没尝试获取（用的是 DeepSeek），就不应该释放，否则会破坏信号量的计数，导致并发控制失效。

**信号量的完整生命周期**：

```
请求进入 → tryAcquire() 获取许可（acquired = true，可用许可从 1 变为 0）
    → 流式调用完成或出错
    → releaseIfOllama(acquired) 释放许可（可用许可从 0 变为 1）
    → 下一个排队的请求可以进入
```

---

### 4.3 `ensureSessionOwnedByUser()` — 会话归属校验

```java
private void ensureSessionOwnedByUser(String sessionId, String userId) {
    if (sessionId == null || sessionId.isBlank()) {
        throw new IllegalArgumentException("会话ID不能为空");
    }
    if (userId == null || userId.isBlank()) {
        throw new IllegalArgumentException("无效的用户信息");
    }
    if (!sessionId.startsWith(buildSessionPrefix(userId))) {  // ← 调用了 3.1 buildSessionPrefix()
        throw new IllegalArgumentException("无权访问该会话");
    }
}
```

**作用**：验证当前用户是否有权访问指定的会话。这是一个**安全方法**，防止用户 A 窃取用户 B 的聊天记录。

**校验逻辑**（按顺序）：
1. `sessionId` 不能为空
2. `userId` 不能为空
3. `sessionId` 必须以 `"{userId}_"` 开头

**为什么用前缀匹配就够了？** 会话 ID 的格式是 `"{userId}_{UUID}"`，UUID 是随机生成的。只要检查 sessionId 是否以当前用户的 `"{userId}_"` 前缀开头，就能确定这个会话是否属于该用户。

**示例**：

| userId | sessionId | `startsWith("5_")` | 结果 |
|---|---|---|---|
| `"5"` | `"5_a3b7c-d4e5f-..."` | `true` | 通过 |
| `"7"` | `"5_a3b7c-d4e5f-..."` | `false` | 抛异常 "无权访问该会话" |
| `"5"` | `null` | - | 抛异常 "会话ID不能为空" |
| `null` | `"5_a3b7c-..."` | - | 抛异常 "无效的用户信息" |

---

### 4.4 `getChatModel()` — 模型选择与自动降级

```java
private ChatModel getChatModel(String preferredModel) {
    if ("ollama".equalsIgnoreCase(preferredModel)) {
        return ollamaChatModel != null ? ollamaChatModel : openAiChatModel;
    }
    return openAiChatModel != null ? openAiChatModel : ollamaChatModel;
}
```

**作用**：根据用户选择返回对应的 `ChatModel` 实例，如果首选模型不可用则自动降级到另一个。

**选择策略**：

| 用户选择 | Ollama 可用 | DeepSeek 可用 | 返回 |
|---|---|---|---|
| `"ollama"` | 是 | - | `ollamaChatModel` |
| `"ollama"` | 否 | 是 | `openAiChatModel`（降级） |
| `"ollama"` | 否 | 否 | `null` |
| 其他值 / 不传 | - | 是 | `openAiChatModel` |
| 其他值 / 不传 | 是 | 否 | `ollamaChatModel`（降级） |
| 其他值 / 不传 | 否 | 否 | `null` |

**为什么用 `==` 比较而不是 `equals`？** `openAiChatModel` 和 `ollamaChatModel` 是 Spring 容器管理的单例 Bean。同一个 Bean 在整个应用中只有一个实例，用 `==` 比较对象引用比 `equals()` 更高效。

**为什么 `equalsIgnoreCase`？** 用户可能传 `"Ollama"`、`"OLLAMA"`、`"ollama"` 等各种大小写形式，`equalsIgnoreCase` 全部兼容。

---

## 五、第三层：调用第二层 + 外部服务的方法

---

### 5.1 `asyncSaveChatRecord()` — 异步保存聊天记录

```java
@Async
public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
    // ── 第 1 步：清洗 SSE 协议特征（兜底安全网）──
    String cleanBotRes = botRes;
    if (botRes.contains("data:{\"content\":")) {
        cleanBotRes = botRes.replaceAll("data:\\{\"content\":\"|\"\\}", "");
    }

    // ── 第 2 步：写入 MySQL ──
    ChatRecord record = ChatRecord.builder()
            .sessionId(sessionId)
            .userMessage(userMsg)
            .botResponse(cleanBotRes)
            .createdTime(LocalDateTime.now())
            .build();
    chatRecordMapper.insert(record);

    // ── 第 3 步：写入 Redis 缓存 ──
    try {
        String key = "chat:history:" + sessionId;
        redisTemplate.opsForList().rightPush(key, record);        // 追加到列表末尾
        redisTemplate.opsForList().trim(key, -maxHistory, -1);    // 只保留最后 maxHistory 条
        redisTemplate.expire(key, 2, TimeUnit.HOURS);             // 2 小时过期
    } catch (Exception e) {
        log.warn("Redis同步失败: {}", e.getMessage());
    }
}
```

**作用**：将一条完整的聊天记录（用户消息 + AI 回复）持久化到 MySQL，并同步更新 Redis 缓存。

#### `@Async` 注解详解

`@Async` 是 Spring 提供的异步执行注解。被标记的方法会在**独立的线程**中执行，调用者不会等待它完成。

**为什么需要异步？** 在流式对话中，最后一个 chunk 到达后需要立即关闭 SSE 连接（`emitter.complete()`）。如果同步保存聊天记录，数据库写入可能需要几十毫秒甚至更长，前端会感觉到"最后一个字到结束"之间有明显延迟。

**执行流程对比**：

```
同步（不用 @Async）：
  最后一个 chunk → 写 MySQL（~50ms）→ 写 Redis（~10ms）→ emitter.complete() → 前端收到结束
  前端等待时间：~60ms

异步（用 @Async）：
  最后一个 chunk → emitter.complete() → 前端立即收到结束
                    └→ 异步线程：写 MySQL → 写 Redis（不阻塞主线程）
  前端等待时间：~0ms
```

#### 第 1 步：清洗 SSE 协议特征

```java
if (botRes.contains("data:{\"content\":")) {
    cleanBotRes = botRes.replaceAll("data:\\{\"content\":\"|\"\\}", "");
}
```

这是一个**兜底安全网**。正常情况下 `botRes` 应该是纯文本（由 `streamChat()` 中的 `StringBuilder` 累积而成），但如果出现意外情况（如代码 bug 导致 SSE 协议格式混入），这里会尝试清洗后再存入数据库。

**正则解释**：
- `data:\{"content":"` → 匹配 SSE 数据格式的开头部分
- `"\}` → 匹配结尾部分
- `replaceAll` 把这些格式字符全部去掉，只保留纯内容

#### 第 2 步：写入 MySQL

```java
ChatRecord record = ChatRecord.builder()
        .sessionId(sessionId)
        .userMessage(userMsg)
        .botResponse(cleanBotRes)
        .createdTime(LocalDateTime.now())
        .build();
chatRecordMapper.insert(record);
```

用 MyBatis-Plus 的 `insert()` 方法将记录写入 `chat_record` 表。`createdTime` 在插入时自动填充当前时间。

#### 第 3 步：写入 Redis 缓存

```java
String key = "chat:history:" + sessionId;
redisTemplate.opsForList().rightPush(key, record);        // 追加到列表末尾
redisTemplate.opsForList().trim(key, -maxHistory, -1);    // 只保留最后 maxHistory 条
redisTemplate.expire(key, 2, TimeUnit.HOURS);             // 2 小时过期
```

**Redis 数据结构**：使用 List 类型，key 为 `"chat:history:{sessionId}"`，每个元素是一条聊天记录。

| 操作 | Redis 命令 | 作用 |
|---|---|---|
| `rightPush` | `RPUSH` | 将新记录追加到列表末尾（右侧） |
| `trim(key, -maxHistory, -1)` | `LTRIM -N -1` | 只保留列表的最后 N 条，丢弃更早的记录 |
| `expire(key, 2, HOURS)` | `EXPIRE key 7200` | 设置 2 小时过期，过期后自动删除 |

**为什么 Redis 写入失败只是 warn 日志？** 因为 MySQL 已经写入成功了。Redis 只是缓存层，缓存写入失败不影响数据完整性。下次访问时会自动从 MySQL 重新加载到 Redis。

---

### 5.2 `getChatHistory()` — 获取会话的全部历史记录

```java
public List<ChatRecord> getChatHistory(String sessionId, String userId) {
    ensureSessionOwnedByUser(sessionId, userId);  // ← 调用 4.3，先校验归属
    return chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
            .eq(ChatRecord::getSessionId, sessionId)
            .orderByAsc(ChatRecord::getCreatedTime));
}
```

**作用**：获取指定会话的**全部**聊天记录，按时间正序排列（最早的在前）。

**调用链**：
1. `ensureSessionOwnedByUser(sessionId, userId)` → 验证会话属于当前用户（内部调用 `buildSessionPrefix()`）
2. `chatRecordMapper.selectList()` → 从 MySQL 查询

**生成的 SQL**：

```sql
SELECT * FROM chat_record
WHERE session_id = '5_a3b7c-...'
ORDER BY created_time ASC
```

**注意**：这里没有 `LIMIT` 限制。如果一个会话有几百条记录，会全部返回。这主要用于"查看完整历史"场景。

---

### 5.3 `deleteSession()` — 删除会话及其所有记录

```java
public boolean deleteSession(String sessionId, String userId) {
    ensureSessionOwnedByUser(sessionId, userId);  // ← 调用 4.3，校验归属
    LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(ChatRecord::getSessionId, sessionId);
    chatRecordMapper.delete(wrapper);                           // 删除 MySQL 中的记录
    redisTemplate.delete("chat:history:" + sessionId);          // 删除 Redis 中的缓存
    log.info("会话已成功删除: {}", sessionId);
    return true;
}
```

**作用**：删除指定会话的所有聊天记录，同时清除 MySQL 和 Redis 中的数据。

**为什么同时删 MySQL 和 Redis？**

| 场景 | 后果 |
|---|---|
| 只删 MySQL，不删 Redis | 下次访问时 Redis 缓存还在，会返回已删除的数据（脏读） |
| 只删 Redis，不删 MySQL | 下次访问会从 MySQL 重新加载到 Redis，删除无效 |
| 两者都删 | 数据彻底清除，保持一致性 |

**生成的 SQL**：

```sql
DELETE FROM chat_record WHERE session_id = '5_a3b7c-...'
```

---

### 5.4 `getSystemStats()` — 获取用户的聊天统计

```java
public Map<String, Object> getSystemStats(String userId) {
    LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
    wrapper.likeRight(ChatRecord::getSessionId, buildSessionPrefix(userId));  // ← 调用 3.1
    return Map.of("totalChats", chatRecordMapper.selectCount(wrapper), "status", "RUNNING");
}
```

**作用**：统计当前用户的总聊天记录数，返回统计信息。

**调用链**：
1. `buildSessionPrefix(userId)` → 返回 `"{userId}_"`
2. `chatRecordMapper.selectCount()` → 统计匹配的记录数

**生成的 SQL**：

```sql
SELECT COUNT(*) FROM chat_record WHERE session_id LIKE '5_%'
```

`likeRight` 是 MyBatis-Plus 的方法，生成 `LIKE '{value}%'` 条件，匹配所有以 `{userId}_` 开头的 `session_id`。

**返回值**：

```json
{"totalChats": 42, "status": "RUNNING"}
```

---

### 5.5 `getChatRecordsPage()` — 分页查询聊天记录

```java
public IPage<ChatRecord> getChatRecordsPage(int page, int size, String userId) {
    LambdaQueryWrapper<ChatRecord> wrapper = new LambdaQueryWrapper<>();
    wrapper.likeRight(ChatRecord::getSessionId, buildSessionPrefix(userId));  // ← 调用 3.1
    wrapper.orderByDesc(ChatRecord::getCreatedTime);
    return chatRecordMapper.selectPage(new Page<>(page, size), wrapper);
}
```

**作用**：分页查询当前用户的所有聊天记录，按时间倒序（最新的在前）。

**调用链**：
1. `buildSessionPrefix(userId)` → 返回 `"{userId}_"`
2. `chatRecordMapper.selectPage()` → 分页查询

**生成的 SQL**（以 `page=1, size=10` 为例）：

```sql
SELECT * FROM chat_record
WHERE session_id LIKE '5_%'
ORDER BY created_time DESC
LIMIT 10 OFFSET 0
```

**MyBatis-Plus 分页原理**：
- `new Page<>(page, size)`：创建分页参数，`page` 是页码（从 1 开始），`size` 是每页条数
- `selectPage()`：自动在 SQL 末尾添加 `LIMIT` 和 `OFFSET`
- 返回的 `IPage<ChatRecord>` 包含完整的分页信息

**返回值结构**：

```json
{
    "records": [...],    // 当前页的记录列表
    "total": 42,         // 总记录数
    "size": 10,          // 每页条数
    "current": 1,        // 当前页码
    "pages": 5           // 总页数
}
```

---

## 六、第四层：核心方法 `buildConversationContext()`

这是整个服务**最核心的方法**，负责组装发送给 AI 模型的完整对话上下文。它决定了模型"看到"什么信息，直接影响回复质量。

```java
private ConversationContext buildConversationContext(
        String sessionId,    // 会话 ID，用于查询历史记录
        String userInput,    // 用户当前输入的消息
        String userId,       // 用户 ID，用于 RAG 检索该用户的知识文档
        Boolean useRag,      // 是否启用 RAG（可为 null）
        Integer ragTopK      // RAG 返回文档数（可为 null）
)
```

**返回值**：`ConversationContext`，一个 record 类型：

```java
private record ConversationContext(List<Message> messages, List<RagReference> references) {}
```

- `messages`：完整的消息列表（系统提示 + RAG 知识 + 历史 + 当前输入），直接传给 AI 模型
- `references`：RAG 检索到的知识引用，用于前端展示"参考了哪些知识文档"

---

### 第 1 步：初始化消息列表，加入系统提示

```java
List<Message> messages = new ArrayList<>();
messages.add(new SystemMessage(systemPrompt));       // application.yml 中配置的角色设定
List<RagReference> references = Collections.emptyList();
```

`systemPrompt` 是在 `application.yml` 中配置的系统提示词，告诉模型"你是谁、怎么回答"。它永远是消息列表的**第一条**。

Spring AI 定义了三种消息类型：

| 类型 | 对应角色 | 用途 |
|---|---|---|
| `SystemMessage` | `system` | 系统指令：角色设定、行为约束、输出格式要求 |
| `UserMessage` | `user` | 用户输入：用户说的话 |
| `AssistantMessage` | `assistant` | AI 回复：模型之前说的话（历史记录） |

---

### 第 2 步：RAG 检索（如果启用）

```java
if (shouldUseRag(useRag)) {                              // ← 调用 3.2，决定是否启用 RAG
    try {
        references = ragService.retrieveReferences(      // ← 调用 RagService 的检索方法
                Long.valueOf(userId),                     //     只检索该用户的知识文档
                userInput,                                //     用用户输入作为查询
                resolveTopK(ragTopK)                      // ← 调用 3.3，确定返回几篇
        );
        if (!references.isEmpty()) {
            messages.add(new SystemMessage(               // 把检索到的知识作为第二条系统消息注入
                    ragService.buildKnowledgePrompt(references)  // ← 调用 RagService 的格式化方法
            ));
        }
    } catch (Exception e) {
        log.warn("RAG 检索失败，已降级为普通对话: {}", e.getMessage());
    }
}
```

**详细流程**：

1. `shouldUseRag(useRag)` → 决定是否启用 RAG（见 3.2）
2. `resolveTopK(ragTopK)` → 确定返回几篇文档（见 3.3）
3. `ragService.retrieveReferences(userId, userInput, topK)` → 在用户的知识库中搜索与输入最相关的文档。内部使用关键词匹配算法（不是向量检索），对每篇文档计算相关性分数，返回分数最高的 topK 篇
4. `ragService.buildKnowledgePrompt(references)` → 把检索到的文档格式化为一段提示文本，格式如下：

```
以下是可用知识片段，请优先基于这些内容回答，并在答案中尽量保持事实一致：
[1] 产品介绍
这是我们的核心产品，具有以下特点...
[2] 常见问题
关于退款政策...
如果知识片段无法覆盖问题，请明确说明并给出保守回答。
```

5. 将这段文本作为**第二条 SystemMessage** 加入消息列表

**RAG 注入后的消息结构**：

```
[SystemMessage 1] 你是 AI Studio 的智能助手...（角色设定）
[SystemMessage 2] 以下是可用知识片段...（RAG 知识）
[UserMessage]     用户当前输入
```

**为什么用 try-catch 包裹？** RAG 检索失败不应该阻断对话。如果 Redis 不可用、数据库查询超时等，捕获异常后降级为普通对话（没有知识补充），用户仍然能正常聊天。

---

### 第 3 步：从 Redis 读取聊天历史

```java
String key = "chat:history:" + sessionId;
List<ChatRecord> history = Collections.emptyList();
boolean cacheHit = false;

try {
    // 从 Redis List 中读取全部元素（0 到 -1 表示从第一个到最后一个）
    long redisStart = System.nanoTime();
    List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);
    long redisEnd = System.nanoTime();
    long redisTimeUs = (redisEnd - redisStart) / 1000;  // 转换为微秒

    if (rawHistory != null && !rawHistory.isEmpty()) {
        // 缓存命中：将 JSON 对象转换为 ChatRecord 列表
        history = rawHistory.stream()
                .map(obj -> objectMapper.convertValue(obj, ChatRecord.class))
                .collect(Collectors.toList());
        cacheHit = true;
        log.info("【性能监控】Redis 命中！读取 {} 条记录，耗时: {} 微秒 (μs)",
                history.size(), redisTimeUs);
    } else {
        log.warn("【性能监控】Redis 未命中，正在查询 MySQL...");
    }
} catch (Exception e) {
    log.warn("Redis 不可用，已降级到 MySQL 查询: {}", e.getMessage());
}
```

**详细流程**：

1. `redisTemplate.opsForList().range(key, 0, -1)` → 从 Redis List 中读取**全部元素**。`0` 是起始索引，`-1` 表示最后一个元素（Redis 的负索引从末尾开始计数）
2. 如果返回非空列表，说明缓存命中（`cacheHit = true`）
3. `objectMapper.convertValue(obj, ChatRecord.class)` → 将 Redis 中存储的 JSON 对象反序列化为 `ChatRecord` Java 对象
4. 记录性能日志（Redis 读取耗时，单位微秒）

**为什么用 `System.nanoTime()` 而不是 `System.currentTimeMillis()`？** `nanoTime()` 是单调递增的，不受系统时间调整（如 NTP 同步）影响，更适合测量短时间间隔。`currentTimeMillis()` 可能因为系统时间回拨而出现负数。

**为什么 Redis 异常只是 warn？** Redis 只是缓存层。不可用时降级到 MySQL 查询，不影响核心功能。

---

### 第 4 步：Redis 未命中时查 MySQL，并异步回写 Redis

```java
if (!cacheHit) {
    // 从 MySQL 查询最近 maxHistory 条记录
    long dbStart = System.nanoTime();
    history = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
            .eq(ChatRecord::getSessionId, sessionId)          // 只查当前会话
            .orderByAsc(ChatRecord::getCreatedTime)            // 按时间正序
            .last("LIMIT " + maxHistory));                     // 只取最近 N 条
    long dbEnd = System.nanoTime();
    long dbTimeUs = (dbEnd - dbStart) / 1000;

    log.info("【性能监控】MySQL 查询完成，获取 {} 条记录，耗时: {} 微秒 (μs)",
            history.size(), dbTimeUs);

    // 异步补偿：把 MySQL 查到的数据写回 Redis，下次就快了
    if (!history.isEmpty()) {
        final List<ChatRecord> finalHistory = history;
        CompletableFuture.runAsync(() -> {
            try {
                redisTemplate.opsForList().rightPushAll(key, finalHistory.toArray());
                redisTemplate.expire(key, 2, java.util.concurrent.TimeUnit.HOURS);
            } catch (Exception e) {
                log.warn("Redis 写回失败: {}", e.getMessage());
            }
        });
    }
}
```

**MySQL 查询**：
- `eq(ChatRecord::getSessionId, sessionId)` → 只查当前会话的记录
- `orderByAsc(ChatRecord::getCreatedTime)` → 按时间正序（最早的在前，这样模型先看到旧对话再看到新对话）
- `.last("LIMIT " + maxHistory)` → 只取最近 N 条（`maxHistory` 默认 5）。`.last()` 是 MyBatis-Plus 的方法，直接在 SQL 末尾追加内容

**生成的 SQL**：

```sql
SELECT * FROM chat_record
WHERE session_id = '5_a3b7c-...'
ORDER BY created_time ASC
LIMIT 5
```

**异步回写 Redis**：

- `CompletableFuture.runAsync()` → 在默认线程池（`ForkJoinPool.commonPool`）中异步执行，不阻塞当前请求
- `rightPushAll` → 将所有记录一次性批量写入 Redis List（比逐条 `rightPush` 更高效）
- `expire(key, 2, TimeUnit.HOURS)` → 设置 2 小时过期

**为什么用 `CompletableFuture.runAsync()` 而不是 `@Async`？** `@Async` 只能用在 Spring Bean 的 `public` 方法上。这里的回写逻辑是 `buildConversationContext()` 方法内部的局部代码，不方便抽成独立的 public 方法，所以用 `CompletableFuture` 更简洁。

**为什么只取 `maxHistory` 条历史？** 发送给模型的消息越多，消耗的 Token 越多，API 费用越高，响应也越慢。只取最近几条作为上下文通常就够了（模型不需要记住所有历史对话）。

---

### 第 5 步：将历史记录转换为 Message 对象，加入当前输入

```java
// 遍历历史记录，将每条记录拆成 UserMessage + AssistantMessage
history.forEach(r -> {
    messages.add(new UserMessage(r.getUserMessage()));       // 用户当时说的话
    messages.add(new AssistantMessage(r.getBotResponse()));  // AI 当时的回复
});

// 最后加入用户当前的输入
messages.add(new UserMessage(userInput));

return new ConversationContext(messages, references);
```

**最终消息结构**：

```
[SystemMessage 1] 系统提示（角色设定、行为约束）
[SystemMessage 2] RAG 知识（如果有，来自 RagService）
[UserMessage]     历史消息 1 - 用户说的话
[AssistantMessage] 历史消息 1 - AI 的回复
[UserMessage]     历史消息 2 - 用户说的话
[AssistantMessage] 历史消息 2 - AI 的回复
[UserMessage]     历史消息 3 - 用户说的话
[AssistantMessage] 历史消息 3 - AI 的回复
[UserMessage]     当前用户输入  ← 最后一条
```

**为什么历史消息在 RAG 知识之后？** 这是 Prompt Engineering 的经验：
- 系统级指令（角色设定、知识补充）放在前面，作为"背景知识"
- 对话历史放在中间，作为"上下文"
- 用户的最新输入放在最后，因为模型对最后的内容关注度最高

---

## 七、第五层：顶层方法 `streamChat()`

这是整个服务的**入口方法**，串联了所有逻辑，实现了完整的流式对话功能。

```java
public void streamChat(ChatRequest request, SseEmitter emitter, String userId) {
```

**参数说明**：

| 参数 | 类型 | 含义 |
|---|---|---|
| `request` | `ChatRequest` | 聊天请求，包含 `message`、`sessionId`、`model`、`useRag`、`ragTopK` |
| `emitter` | `SseEmitter` | SSE 发射器，用于向前端推送实时数据流 |
| `userId` | `String` | 当前登录用户的 ID（从 JWT Token 中解析） |

---

### 第 1 步：安全校验

```java
ensureSessionOwnedByUser(request.getSessionId(), userId);  // ← 调用 4.3
```

验证请求的 `sessionId` 是否属于当前用户。如果不属于，直接抛 `IllegalArgumentException`，由全局异常处理器返回 400 错误。

---

### 第 2 步：获取模型，检查可用性

```java
ChatModel model = getChatModel(request.getModel());        // ← 调用 4.4
if (model == null) {
    sendStreamError(emitter, "未找到可用的聊天模型");        // ← 调用 4.1
    return;
}
if (model == openAiChatModel && (openAiApiKey == null || openAiApiKey.isBlank())) {
    sendStreamError(emitter, "AI 接口鉴权失败，请检查 DEEPSEEK_API_KEY");
    return;
}
```

**两层检查**：
1. 模型是否可用（`model == null` 说明两个模型都不可用）
2. 如果选的是 DeepSeek，API Key 是否已配置（Ollama 是本地部署，不需要 Key）

---

### 第 3 步：Ollama 排队机制

```java
boolean isOllama = model == ollamaChatModel;
boolean acquired = false;

if (isOllama) {
    try {
        // 检查当前是否有可用许可（0 = 被占用，1 = 空闲）
        int available = ollamaSemaphore.availablePermits();
        if (available == 0) {
            // 没有可用许可，告诉前端"正在排队"
            emitter.send(SseEmitter.event().name("status")
                    .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));
        }

        // 尝试获取许可，最多等待 120 秒
        acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
        if (!acquired) {
            // 120 秒内没获取到许可，排队超时
            sendStreamError(emitter, "当前排队人数过多，请稍后重试");
            return;
        }

        // 获取到许可，告诉前端"开始处理"
        emitter.send(SseEmitter.event().name("status")
                .data(Map.of("status", "processing", "message", "")));

    } catch (InterruptedException e) {
        // 等待过程中线程被中断（如应用关闭）
        Thread.currentThread().interrupt();  // 恢复中断状态（Java 多线程最佳实践）
        sendStreamError(emitter, "请求被中断，请重试");
        return;
    } catch (Exception e) {
        log.warn("SSE 状态发送失败: {}", e.getMessage());
    }
}
```

**详细流程**：

1. `isOllama = model == ollamaChatModel` → 判断当前使用的是否是 Ollama 模型
2. `ollamaSemaphore.availablePermits()` → 检查信号量的可用许可数（`Semaphore(1)` 最多 1 个许可）
3. 如果可用许可为 0（被其他请求占用），发送 `"status"` 事件告诉前端"正在排队"
4. `tryAcquire(120, TimeUnit.SECONDS)` → 尝试获取许可，**阻塞等待最多 120 秒**
5. 如果 120 秒内获取到了（其他请求完成了），发送 `"status"` 事件告诉前端"开始处理"
6. 如果超时，发送错误并返回

**为什么需要排队？** Ollama 本地模型（尤其是小模型如 0.5B 参数）通常是**单线程推理**，同一时间只能处理一个请求。如果多个请求同时到达，会导致模型过载、响应极慢甚至崩溃。`Semaphore(1)` 保证同一时间只有一个请求在使用 Ollama。

**为什么等 120 秒？** Ollama 本地模型推理较慢，一个请求可能需要 30-60 秒。120 秒的等待时间能覆盖大部分场景，同时避免无限等待。

**DeepSeek 为什么不需要排队？** DeepSeek 是云端 API，有自己的并发处理能力和限流机制（返回 429 时本项目会翻译为友好提示），不需要本地控制。

---

### 第 4 步：构建对话上下文

```java
boolean finalAcquired = isOllama && acquired;
ConversationContext conversationContext = buildConversationContext(  // ← 调用第四层
        request.getSessionId(), request.getMessage(), userId,
        request.getUseRag(), request.getRagTopK()
);
```

`finalAcquired` 变量用于在 `subscribe` 回调中判断是否需要释放信号量。因为回调是在另一个线程中执行的，需要**捕获**当前的 `acquired` 状态（Java 中 lambda 表达式只能捕获 effectively final 变量）。

---

### 第 5 步：发送 RAG 引用给前端

```java
if (!conversationContext.references().isEmpty()) {
    try {
        emitter.send(SseEmitter.event().name("references")
                .data(Map.of("references", conversationContext.references())));
    } catch (Exception e) {
        log.warn("SSE 发送知识引用失败: {}", e.getMessage());
    }
}
```

在流式回复开始之前，先把 RAG 检索到的知识引用发给前端。前端可以展示"参考了哪些知识文档"，让用户知道 AI 是基于什么知识回答的。

**前端收到的 SSE 数据**：

```
event:references
data:{"references":[{"documentId":1,"title":"产品介绍","snippet":"...","score":85}]}
```

---

### 第 6 步：发起流式调用（核心中的核心）

```java
StringBuilder fullRes = new StringBuilder();  // 用于累积完整回复

try {
    model.stream(new Prompt(conversationContext.messages()))          // ① 发起流式请求
            .map(res -> res.getResult().getOutput().getContent())     // ② 提取文本内容
            .subscribe(
                    chunk -> {                                         // ③ onNext：每收到一个 chunk
                        try {
                            if (chunk != null) {
                                fullRes.append(chunk);                 //    累积到完整回复
                                emitter.send(Map.of("content", chunk)); //    立即推送给前端
                            }
                        } catch (Exception e) {
                            log.warn("SSE 发送 chunk 失败: {}", e.getMessage());
                        }
                    },
                    err -> {                                           // ④ onError：出错
                        log.error("流式对话失败: {}", resolveErrorMessage(err), err);
                        asyncSaveChatRecord(request.getSessionId(), request.getMessage(),
                                fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "");
                        sendStreamError(emitter, resolveErrorMessage(err));
                        releaseIfOllama(finalAcquired);
                    },
                    () -> {                                            // ⑤ onComplete：正常完成
                        asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullRes.toString());
                        emitter.complete();
                        releaseIfOllama(finalAcquired);
                    }
            );
} catch (Exception e) {                                                // ⑥ 初始化失败
    log.error("流式对话初始化失败: {}", resolveErrorMessage(e), e);
    sendStreamError(emitter, resolveErrorMessage(e));
    releaseIfOllama(finalAcquired);
}
```

**逐行解析**：

#### ① `model.stream(new Prompt(conversationContext.messages()))`

- `new Prompt(messages)`：将消息列表包装为 Spring AI 的 `Prompt` 对象
- `model.stream()`：发起流式调用，返回 Project Reactor 的 `Flux<ChatResponse>`
- Spring AI 内部会：将消息列表序列化为 JSON → 发 HTTP POST 到模型 API（请求体中 `stream: true`）→ 解析 SSE 响应流 → 逐个封装为 `ChatResponse` 对象

#### ② `.map(res -> res.getResult().getOutput().getContent())`

从 `ChatResponse` 中提取纯文本内容：
- `res.getResult()` → 获取 `ChatGeneration` 对象
- `.getOutput()` → 获取 `AssistantMessage` 对象
- `.getContent()` → 获取文本字符串（即模型生成的一个 token 或几个 token）

#### ③ `onNext`：每收到一个 chunk

```java
chunk -> {
    if (chunk != null) {
        fullRes.append(chunk);                        // 追加到 StringBuilder，累积完整回复
        emitter.send(Map.of("content", chunk));       // 通过 SSE 立即推送给前端
    }
}
```

每个 chunk 通常是几个字符到几十个字符。前端收到后追加到显示区域，实现"打字机效果"。

#### ④ `onError`：流式调用出错

```java
err -> {
    log.error("流式对话失败: {}", resolveErrorMessage(err), err);           // 记录错误日志
    asyncSaveChatRecord(request.getSessionId(), request.getMessage(),
            fullRes.length() > 0 ? fullRes.toString() + "\n\n[回答中断]" : "");  // 保存已生成的部分内容
    sendStreamError(emitter, resolveErrorMessage(err));                     // 发送错误事件给前端
    releaseIfOllama(finalAcquired);                                         // 释放信号量
}
```

如果流式调用中途出错（如网络中断、模型超时），会保存已经生成的部分内容（并标记 `[回答中断]`），然后通知前端错误信息。

#### ⑤ `onComplete`：流式调用正常完成

```java
() -> {
    asyncSaveChatRecord(request.getSessionId(), request.getMessage(), fullRes.toString());  // 保存完整回复
    emitter.complete();                                                                     // 关闭 SSE 连接
    releaseIfOllama(finalAcquired);                                                         // 释放信号量
}
```

所有 chunk 都收到后，保存完整回复到数据库和缓存，关闭 SSE 连接，释放信号量。

#### ⑥ 外层 try-catch：初始化失败

```java
} catch (Exception e) {
    log.error("流式对话初始化失败: {}", resolveErrorMessage(e), e);
    sendStreamError(emitter, resolveErrorMessage(e));
    releaseIfOllama(finalAcquired);
}
```

捕获 `model.stream()` 调用本身可能抛出的异常（如网络不通、模型服务不可达）。注意：流式调用**开始后**的错误由 `onError` 回调处理，这里的 try-catch 只处理**初始化阶段**的错误。

---

## 八、内部类 `ConversationContext`

```java
private record ConversationContext(List<Message> messages, List<RagReference> references) {}
```

Java 16 引入的 `record` 类型，是一种不可变数据类。编译器会自动生成：
- 构造函数
- `messages()` 和 `references()` 访问器方法
- `equals()`、`hashCode()`、`toString()`

**作用**：封装 `buildConversationContext()` 的返回值，将两个相关联的数据（消息列表和知识引用）打包成一个对象传递。

---

## 九、完整调用链总览

```
streamChat() ← 顶层入口
│
├── [第 1 步：安全校验]
│   └── ensureSessionOwnedByUser()                    ← 4.3
│       └── buildSessionPrefix()                      ← 3.1
│
├── [第 2 步：模型选择]
│   └── getChatModel()                                ← 4.4
│
├── [第 3 步：Ollama 排队]
│   ├── ollamaSemaphore.tryAcquire()
│   └── sendStreamError()                             ← 4.1（排队超时）
│
├── [第 4 步：构建上下文]
│   └── buildConversationContext()                     ← 第四层
│       ├── shouldUseRag()                            ← 3.2
│       ├── resolveTopK()                             ← 3.3
│       ├── ragService.retrieveReferences()           ← RagService（外部）
│       ├── ragService.buildKnowledgePrompt()         ← RagService（外部）
│       ├── redisTemplate.opsForList().range()        ← Redis 读取
│       ├── chatRecordMapper.selectList()             ← MySQL 读取
│       └── CompletableFuture.runAsync()              ← 异步回写 Redis
│
├── [第 5 步：发送 RAG 引用]
│   └── emitter.send("references")
│
├── [第 6 步：流式调用]
│   └── model.stream(Prompt).subscribe(
│       ├── onNext  → fullRes.append() + emitter.send("content")
│       ├── onError → asyncSaveChatRecord() + sendStreamError() + releaseIfOllama()
│       └── onComplete → asyncSaveChatRecord() + emitter.complete() + releaseIfOllama()
│   )
│
│
├── getChatHistory()                                  ← 5.2
│   └── ensureSessionOwnedByUser()                    ← 4.3
│
├── deleteSession()                                   ← 5.3
│   └── ensureSessionOwnedByUser()                    ← 4.3
│
├── getSystemStats()                                  ← 5.4
│   └── buildSessionPrefix()                          ← 3.1
│
└── getChatRecordsPage()                              ← 5.5
    └── buildSessionPrefix()                          ← 3.1
```

---

## 十、SSE 事件类型汇总

前端通过 `EventSource` 监听以下事件：

| 事件名 | 数据格式 | 触发时机 | 用途 |
|---|---|---|---|
| `status` | `{"status":"queued","message":"AI 正在处理其他请求，请稍候…"}` | Ollama 请求排队时 | 前端显示"排队中"提示 |
| `status` | `{"status":"processing","message":""}` | Ollama 请求开始处理时 | 前端隐藏排队提示 |
| `references` | `{"references":[...]}` | RAG 检索完成后、流式回复开始前 | 前端展示"参考了哪些知识" |
| （默认事件） | `{"content":"你"}` | 每收到一个 token chunk | 打字机效果，逐字显示 |
| `error` | `{"error":"错误信息"}` | 发生错误时 | 前端显示错误提示 |

**完整的 SSE 数据流示例**：

```
event:status
data:{"status":"queued","message":"AI 正在处理其他请求，请稍候…"}

event:status
data:{"status":"processing","message":""}

event:references
data:{"references":[{"documentId":1,"title":"产品介绍","snippet":"...","score":85}]}

data:{"content":"你"}
data:{"content":"好"}
data:{"content":"！"}
data:{"content":"有"}
data:{"content":"什么"}
data:{"content":"可以"}
data:{"content":"帮"}
data:{"content":"你"}
data:{"content":"的"}
data:{"content":"？"}
```
