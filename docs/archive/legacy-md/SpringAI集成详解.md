# Spring AI 在本项目中的集成与应用详解

## 一、Spring AI 是什么

Spring AI 是 Spring 官方推出的 AI 应用开发框架，核心理念是 **将 AI 模型能力抽象为 Spring Bean**，让开发者像使用普通 Service 一样调用大语言模型（LLM）。它提供：

- 统一的 `ChatModel` 接口，屏蔽不同 LLM 提供商的 API 差异
- 自动装配（Auto-Configuration），通过 `application.yml` 配置即可使用
- 支持同步调用、流式调用（Streaming）、Function Calling、RAG 等能力

本项目使用的版本是 **`1.0.0-M4`**（Milestone 4，早期预览版），同时集成了两个模型后端：**DeepSeek**（通过 OpenAI 兼容 API）和 **Ollama**（本地部署）。

---

## 二、依赖引入（pom.xml）

```xml
<!-- Spring AI 版本统一管理 -->
<spring-ai.version>1.0.0-M4</spring-ai.version>

<!-- 1. OpenAI 兼容客户端 —— 用于连接 DeepSeek -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
    <version>${spring-ai.version}</version>
</dependency>

<!-- 2. Ollama 客户端 —— 用于连接本地 Ollama 服务 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-ollama-spring-boot-starter</artifactId>
    <version>${spring-ai.version}</version>
</dependency>
```

**为什么要引入两个 starter？**

Spring AI 的设计是每个 LLM 提供商一个独立的 starter。本项目需要同时支持 DeepSeek（远程 API）和 Ollama（本地推理），所以引入了两个。它们各自自动装配出不同的 `ChatModel` Bean：

| Starter | 自动装配的 Bean | 用途 |
|---|---|---|
| `spring-ai-openai-spring-boot-starter` | `OpenAiChatModel` | 调用 DeepSeek API（OpenAI 兼容协议） |
| `spring-ai-ollama-spring-boot-starter` | `OllamaChatModel` | 调用本地 Ollama 模型 |

> 注意：因为使用的是 Spring Milestone 版本，所以 pom.xml 中需要额外配置 Spring Milestones 仓库：
> ```xml
> <repositories>
>     <repository>
>         <id>spring-milestones</id>
>         <url>https://repo.spring.io/milestone</url>
>     </repository>
> </repositories>
> ```

---

## 三、配置详解（application.yml）

### 3.1 DeepSeek 配置（通过 OpenAI 兼容协议）

```yaml
spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY:}          # DeepSeek 的 API Key，通过环境变量注入
      base-url: https://api.deepseek.com      # 关键：不是 OpenAI 的地址，而是 DeepSeek 的
      chat:
        enabled: ${SPRING_AI_OPENAI_ENABLED:false}  # 是否启用，默认关闭
        options:
          model: deepseek-chat                # DeepSeek 的模型名称
          temperature: 0.7                    # 温度参数，控制回复随机性（0~1）
      embedding:
        enabled: false                        # 关闭 Embedding（本项目不使用向量检索）
      image:
        enabled: false                        # 关闭图像生成
      audio:
        speech:
          enabled: false                      # 关闭语音合成
        transcription:
          enabled: false                      # 关闭语音转文字
```

**核心要点：**

- `base-url` 指向 DeepSeek 而非 OpenAI —— 这是 Spring AI OpenAI starter 的一大优势：**任何兼容 OpenAI 协议的服务都可以通过修改 base-url 接入**（DeepSeek、Moonshot、通义千问等均支持）
- `enabled: false` 是为了避免 Spring AI 启动时尝试连接尚未配置的服务，导致启动失败
- 通过 `${DEEPSEEK_API_KEY:}` 环境变量注入密钥，`: ` 后为空表示默认值为空字符串（不填则模型不可用）

### 3.2 Ollama 配置

```yaml
spring:
  ai:
    ollama:
      base-url: https://ollama.yyyyx.top     # Ollama 服务地址（内网穿透）
      chat:
        options:
          model: qwen2.5:0.5b                # 使用的本地模型
          temperature: 0.7
```

**Ollama 的特点：**

- Ollama 是一个本地 LLM 运行框架，可以在自己的电脑上运行模型
- 不需要 API Key，但需要先 `ollama pull qwen2.5:0.5b` 下载模型
- 本项目通过内网穿透（`ollama.yyyyx.top`）暴露 Ollama 服务，使其可被远程访问
- 模型较小（0.5B 参数），适合演示和测试，生产环境建议使用更大的模型

---

## 四、Bean 的获取与容错设计

Spring AI 的 starter 会通过 `@ConditionalOnProperty` 等条件注解自动装配 `ChatModel` Bean。但本项目有一个关键设计：**允许部分模型不可用**。

在 [ChatbotService.java](src/main/java/com/example/chatbot/service/ChatbotService.java) 的构造函数中：

```java
@Autowired
public ChatbotService(
        ObjectProvider<OpenAiChatModel> openAiChatModelProvider,    // 注意：不是直接注入 Bean
        ObjectProvider<OllamaChatModel> ollamaChatModelProvider,    // 而是用 ObjectProvider 延迟获取
        // ...
) {
    // 安全获取 OpenAI 模型
    OpenAiChatModel openAi = null;
    try {
        openAi = openAiChatModelProvider.getIfAvailable();
    } catch (Exception e) {
        log.warn("OpenAI/DeepSeek 模型不可用: {}", e.getMessage());
    }
    this.openAiChatModel = openAi;

    // 安全获取 Ollama 模型
    OllamaChatModel ollama = null;
    try {
        ollama = ollamaChatModelProvider.getIfAvailable();
    } catch (Exception e) {
        log.warn("Ollama 模型不可用: {}", e.getMessage());
    }
    this.ollamaChatModel = ollama;
}
```

**为什么用 `ObjectProvider` 而不是直接 `@Autowired`？**

| 方式 | 行为 |
|---|---|
| 直接 `@Autowired OllamaChatModel` | 如果 Bean 创建失败（如 Ollama 服务不可达），**整个应用启动失败** |
| `ObjectProvider<OllamaChatModel>` + `getIfAvailable()` | Bean 不可用时返回 null，**应用正常启动**，只是该模型不可用 |

这是本项目的一个优秀设计：DeepSeek 和 Ollama 互不影响，一个挂了另一个照常工作。

---

## 五、模型选择策略

在 [ChatbotService.java:90-95](src/main/java/com/example/chatbot/service/ChatbotService.java#L90-L95)：

```java
private ChatModel getChatModel(String preferredModel) {
    if ("ollama".equalsIgnoreCase(preferredModel)) {
        return ollamaChatModel != null ? ollamaChatModel : openAiChatModel;  // 优先 Ollama，降级到 DeepSeek
    }
    return openAiChatModel != null ? openAiChatModel : ollamaChatModel;      // 优先 DeepSeek，降级到 Ollama
}
```

**逻辑：**
- 用户请求中指定 `model: "ollama"` → 优先使用 Ollama，不可用则降级到 DeepSeek
- 默认（不指定或指定其他值）→ 优先使用 DeepSeek，不可用则降级到 Ollama

**Spring AI 的统一接口优势：**

`OpenAiChatModel` 和 `OllamaChatModel` 都实现了 `ChatModel` 接口，所以业务代码只需要面向 `ChatModel` 编程，不需要关心底层是哪个模型在提供服务。

```
         ┌──────────────┐
         │  ChatModel   │  ← Spring AI 统一接口
         │  (interface) │
         └──────┬───────┘
                │
       ┌────────┴────────┐
       │                 │
┌──────┴──────┐   ┌──────┴──────┐
│OpenAiChatModel│ │OllamaChatModel│
│  (DeepSeek)  │   │  (本地模型)   │
└─────────────┘   └──────────────┘
```

---

## 六、流式对话（SSE Streaming）的完整链路

这是 Spring AI 在本项目中最核心的应用场景。整个链路如下：

### 6.1 请求链路

```
浏览器 EventSource
    ↓ POST /api/chat/stream
ChatbotController.streamChat()
    ↓ 创建 SseEmitter（超时 180 秒）
ChatbotService.streamChat()
    ↓ 获取 ChatModel
    ↓ 构建对话上下文（系统提示 + RAG 知识 + 历史消息 + 用户输入）
    ↓ 调用 model.stream() 获取 Flux
    ↓ 逐个 chunk 通过 SseEmitter 发送给浏览器
```

### 6.2 核心代码解析

在 [ChatbotService.java:294-319](src/main/java/com/example/chatbot/service/ChatbotService.java#L294-L319)：

```java
model.stream(new Prompt(conversationContext.messages()))   // ① 发起流式请求
    .map(res -> res.getResult().getOutput().getContent())  // ② 从响应中提取文本
    .subscribe(
        chunk -> {                                          // ③ 每收到一个 chunk
            fullRes.append(chunk);                          //    累积完整回复
            emitter.send(Map.of("content", chunk));         //    通过 SSE 发送给前端
        },
        err -> {                                            // ④ 发生错误
            asyncSaveChatRecord(...);                       //    保存已生成的部分内容
            sendStreamError(emitter, resolveErrorMessage(err));
            releaseIfOllama(finalAcquired);                 //    释放信号量
        },
        () -> {                                             // ⑤ 流式完成
            asyncSaveChatRecord(...);                       //    保存完整回复
            emitter.complete();                             //    关闭 SSE 连接
            releaseIfOllama(finalAcquired);                 //    释放信号量
        }
    );
```

**Spring AI 在这里的角色：**

`model.stream(Prompt)` 是 Spring AI 提供的流式调用接口。它返回一个 **Project Reactor 的 `Flux<ChatResponse>`**，每个元素是模型生成的一个文本片段（chunk）。Spring AI 内部处理了：

- HTTP 长连接的建立和维护
- SSE（Server-Sent Events）协议的解析
- 不同提供商（OpenAI/Ollama）的流式响应格式差异
- 将原始字节流转换为结构化的 `ChatResponse` 对象

### 6.3 Spring AI 的 Prompt/Message 体系

Spring AI 定义了一套消息抽象体系：

```
Message (interface)
├── SystemMessage    ← 系统提示（角色设定、行为约束）
├── UserMessage      ← 用户输入
└── AssistantMessage ← AI 回复（历史记录）
```

本项目中构建对话上下文的方式：

```java
List<Message> messages = new ArrayList<>();
messages.add(new SystemMessage(systemPrompt));           // 第 1 层：系统提示
messages.add(new SystemMessage(ragService.buildKnowledgePrompt(references))); // 第 2 层：RAG 知识注入

// 第 3 层：历史对话（从 Redis/MySQL 加载）
history.forEach(r -> {
    messages.add(new UserMessage(r.getUserMessage()));
    messages.add(new AssistantMessage(r.getBotResponse()));
});

messages.add(new UserMessage(userInput));                // 第 4 层：当前用户输入
```

最终将完整的 `messages` 列表包装为 `Prompt` 对象传给模型。**Prompt 是 Spring AI 中与模型交互的核心抽象**，它封装了所有上下文信息。

---

## 七、RAG（检索增强生成）与 Spring AI 的关系

本项目的 RAG 实现 **没有使用 Spring AI 的 RAG 模块**（如 `spring-ai-rag`），而是自己实现了基于关键词匹配的检索逻辑。

### 7.1 为什么不直接用 Spring AI 的 RAG？

Spring AI 的 RAG 模块依赖 **Embedding Model**（向量嵌入模型）将文档和查询转换为向量，然后通过向量相似度检索。这需要：

- 一个 Embedding 服务（如 OpenAI Embedding、Ollama Embedding）
- 一个向量数据库（如 Pinecone、Milvus、PgVector）

本项目为了简化部署，选择了自行实现关键词匹配算法：

```yaml
# application.yml 中明确关闭了 Embedding
spring.ai.openai.embedding.enabled: false
```

### 7.2 本项目的 RAG 实现方式

在 [RagService.java](src/main/java/com/example/chatbot/service/RagService.java) 中：

```java
public List<RagReference> retrieveReferences(Long userId, String query, Integer topK) {
    // 1. 从 MySQL 加载用户的所有启用文档
    List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(...);

    // 2. 对查询进行分词和 n-gram 扩展
    List<String> keywords = buildKeywords(safeQuery);

    // 3. 对每个文档计算关键词匹配分数
    for (KnowledgeDocument document : documents) {
        int score = calculateScore(document, safeQuery, keywords);
        // 标题命中 +40，内容命中 +30，标签命中 +20
    }

    // 4. 返回 Top-K 结果
}
```

检索到的知识通过 **SystemMessage 注入到对话上下文**中，让模型"看到"相关知识后再回答：

```java
references = ragService.retrieveReferences(userId, userInput, topK);
if (!references.isEmpty()) {
    messages.add(new SystemMessage(ragService.buildKnowledgePrompt(references)));
}
```

---

## 八、并发控制：Ollama 的信号量机制

本地 Ollama 模型（尤其是小模型如 0.5B）通常只能同时处理一个推理请求。本项目通过 `Semaphore(1)` 实现了排队机制：

```java
private final Semaphore ollamaSemaphore = new Semaphore(1);

// 在 streamChat 方法中：
if (isOllama) {
    // 通知前端"正在排队"
    emitter.send(SseEmitter.event().name("status")
            .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));

    // 最多等待 120 秒获取许可
    acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
    if (!acquired) {
        sendStreamError(emitter, "当前排队人数过多，请稍后重试");
        return;
    }
}
```

**注意：DeepSeek 是远程 API，有自己的并发和限流机制（返回 429 时本项目会翻译为友好提示），所以不需要本地信号量控制。**

---

## 九、整体架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (Thymeleaf + JS)                     │
│   EventSource → POST /api/chat/stream → 接收 SSE chunk          │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ChatbotController                             │
│   创建 SseEmitter → 调用 ChatbotService.streamChat()             │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ChatbotService                                │
│                                                                 │
│   1. 获取 ChatModel（DeepSeek / Ollama）                         │
│   2. 构建 ConversationContext                                    │
│      ├── SystemMessage（系统提示）                                │
│      ├── SystemMessage（RAG 知识，来自 RagService）              │
│      ├── UserMessage + AssistantMessage（历史，来自 Redis/MySQL）│
│      └── UserMessage（当前输入）                                  │
│   3. model.stream(Prompt) → Flux<ChatResponse>                  │
│   4. subscribe → chunk → emitter.send()                         │
└──────┬──────────────────────────────┬───────────────────────────┘
       │                              │
       ▼                              ▼
┌──────────────┐           ┌─────────────────────┐
│  RagService  │           │    Spring AI 框架     │
│  关键词匹配   │           │                     │
│  从 MySQL 检索│           │  ChatModel.stream() │
└──────────────┘           │                     │
                           │  ┌───────────────┐  │
                           │  │ OpenAiChatModel│──┼──→ DeepSeek API (HTTPS)
                           │  └───────────────┘  │
                           │  ┌───────────────┐  │
                           │  │OllamaChatModel │──┼──→ Ollama 服务 (本地/内网)
                           │  └───────────────┘  │
                           └─────────────────────┘
```

---

## 十、Spring AI 在本项目中承担的职责总结

| 职责 | Spring AI 的作用 | 本项目的实现 |
|---|---|---|
| **模型抽象** | 提供统一的 `ChatModel` 接口 | `getChatModel()` 方法根据用户选择返回不同实现 |
| **自动装配** | 通过 starter 自动创建 `OpenAiChatModel` / `OllamaChatModel` Bean | `application.yml` 中配置即可，无需手动创建 Bean |
| **流式调用** | `model.stream()` 返回 `Flux<ChatResponse>` | 在 `subscribe` 回调中逐 chunk 发送给 SseEmitter |
| **消息抽象** | `SystemMessage` / `UserMessage` / `AssistantMessage` | 构建多层对话上下文（系统提示 + RAG + 历史 + 输入） |
| **协议适配** | 内部处理 OpenAI/Ollama 不同的 API 格式 | 业务代码无需关心底层协议差异 |
| **RAG** | 提供 Embedding 和向量检索模块（本项目未使用） | 自行实现关键词匹配检索 |

---

## 十一、关键配置项速查表

| 配置项 | 作用 | 默认值 |
|---|---|---|
| `spring.ai.openai.api-key` | DeepSeek API 密钥 | 空（通过 `DEEPSEEK_API_KEY` 环境变量注入） |
| `spring.ai.openai.base-url` | API 地址 | `https://api.deepseek.com` |
| `spring.ai.openai.chat.options.model` | 模型名称 | `deepseek-chat` |
| `spring.ai.openai.chat.options.temperature` | 温度参数 | `0.7` |
| `spring.ai.openai.chat.enabled` | 是否启用 OpenAI 模型 | `false`（通过环境变量开启） |
| `spring.ai.ollama.base-url` | Ollama 服务地址 | `https://ollama.yyyyx.top` |
| `spring.ai.ollama.chat.options.model` | Ollama 模型 | `qwen2.5:0.5b` |
| `app.chatbot.system-prompt` | 系统提示词 | 见 application.yml |
| `app.chatbot.rag-enabled` | 是否默认启用 RAG | `true` |
| `app.chatbot.rag-top-k` | RAG 返回文档数 | `3` |
| `app.chatbot.max-history` | 最大历史消息数 | `5` |
