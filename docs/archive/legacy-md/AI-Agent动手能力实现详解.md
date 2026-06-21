# AI Agent 动手能力实现详解

生成时间：2026-06-04

## 1. 当前已经完成了什么

当前项目已经从普通智能客服，升级成了一个具备基础动手能力的应用内 AI Agent。

它现在不只是生成文本回答，而是可以在用户授权和后端安全约束内调用工具，读取系统数据、创建知识库文档、生成可打开的文档卡片，并对高风险动作走二次确认。

已经完成的核心能力：

- Agent 聊天入口：`POST /api/chat/agent/stream`
- 前端模型选择：聊天页可以选择 `AI Agent`
- 工具调用事件展示：前端会显示 `使用 createKnowledgeDocument` 这类真实工具函数名
- 知识库读取工具：检索当前用户知识库
- 文件读取工具：读取当前用户上传文件列表和文件元数据
- 聊天历史工具：读取当前会话或指定会话历史
- 时间工具：读取当前服务端时间
- 知识库写入工具：创建知识库文档
- 删除知识库文档：先创建 pending action，用户确认后才执行
- 工具调用审计：每次工具调用都会写审计日志
- 文档卡片：Agent 创建知识文档后，聊天页面出现可点击文档卡片
- 文件管理同步：Agent 生成的知识文档会保存成 `.md` 文件，出现在文件管理页
- RAG 升级：保留关键词 RAG，同时预留可选 PGVector/embedding/hybrid RAG
- 4G 服务器适配：生产默认不开向量库和本地 embedding，避免内存压力

## 2. 为什么它现在算 Agent，而不是普通 Chatbot

普通 Chatbot 的典型行为是：

```text
用户输入 -> 模型生成文本 -> 返回文本
```

现在的 Agent 行为是：

```text
用户输入
  -> Agent 判断是否需要使用工具
  -> 调用后端工具读取或写入真实数据
  -> 工具返回结构化结果
  -> Agent 基于工具结果继续回答
  -> 前端展示工具调用、文档卡片、确认按钮等真实操作结果
```

关键区别在于：
现在模型不只是“说”，它可以通过后端暴露的工具“做”。

例如用户说：

```text
给我生成一份学习 AI 的计划文档放到知识库
```

现在理想链路是：

```text
AI Agent
  -> 调用 createKnowledgeDocument
  -> RagService 写入 knowledge_document
  -> FileService 生成 .md 文件
  -> Kafka 发布知识库事件
  -> 前端收到 knowledge_document_created 事件
  -> 聊天页显示可点击文档卡片
```

这就不是单纯回答“我已经帮你保存了”，而是真的在数据库和文件服务中产生了可查看、可下载、可管理的数据。

## 3. 总体架构

核心入口在：

```text
chatbot-service/src/main/java/com/example/chatbot/controller/AgentController.java
```

Agent 主逻辑在：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java
```

工具类在：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/tool/
```

主要工具：

```text
KnowledgeReadTools
KnowledgeWriteTools
FileReadTools
ChatHistoryTools
TimeTools
```

工具事件通知：

```text
AgentToolNotifier
```

工具审计：

```text
AgentToolAuditService
agent_tool_execution_log
```

高风险动作确认：

```text
AgentPendingActionService
agent_pending_action
AgentActionController
```

## 4. Agent 请求链路

用户在前端选择 `AI Agent` 后，普通文本消息会调用：

```text
POST /api/chat/agent/stream
```

前端代码在：

```text
chatbot-service/src/main/resources/templates/chat.html
```

判断逻辑大致是：

```javascript
const agentMode = modelVal === 'agent' && !hasImage;
const endpoint = hasImage
  ? '/api/chat/stream/filekey'
  : (agentMode ? '/api/chat/agent/stream' : '/api/chat/stream');
```

所以：

- 文本 Agent 请求走 `/api/chat/agent/stream`
- 图片请求仍走原来的多模态接口 `/api/chat/stream/filekey`

这样做是为了避免视觉模型和文本工具 Agent 混在一起，导致链路不清晰。

## 5. AgentService 是怎么工作的

`AgentService.streamAgent()` 做了几件关键事：

1. 校验 Agent 是否启用
2. 校验 sessionId 是否属于当前用户
3. 根据用户选择获取模型
4. 构造系统提示词和历史上下文
5. 绑定工具上下文
6. 把工具注册给 Spring AI ChatClient
7. 使用 SSE 流式返回模型输出和工具事件

核心逻辑类似：

```java
ChatClient.create(model)
    .prompt()
    .messages(messages)
    .tools(knowledgeReadTools, knowledgeWriteTools, fileReadTools, chatHistoryTools, timeTools)
    .toolContext(toolContext)
    .stream()
    .content()
```

这里最重要的是：

```java
.tools(...)
```

这一步把后端 Java 方法暴露给模型，让模型可以在对话过程中选择调用这些工具。

## 6. 工具是怎么定义的

工具方法使用 Spring AI 的 `@Tool` 注解。

例如创建知识库文档：

```java
@Tool(description = "Create a knowledge base document for the current user. This is a low-risk write operation and records an audit log.")
public Map<String, Object> createKnowledgeDocument(
        @ToolParam(description = "Document title") String title,
        @ToolParam(description = "Document content") String content,
        @ToolParam(description = "Optional tags", required = false) String tags,
        @ToolParam(description = "Whether the document should be enabled", required = false) Boolean enabled,
        ToolContext toolContext
)
```

这个方法暴露给模型后，模型能看到：

- 工具名：`createKnowledgeDocument`
- 工具描述：创建当前用户的知识库文档
- 参数：title、content、tags、enabled

模型会根据用户意图和工具描述决定是否调用它。

## 7. Agent 是怎么判断该调用什么工具的

Agent 并不是写了大量 if/else 去硬编码判断。

主要判断来自模型本身的 tool calling 能力。

流程是：

```text
系统提示词
  + 用户消息
  + 历史上下文
  + 工具列表
  + 每个工具的 description
  + 每个参数的 description
  -> 模型判断是否需要调用工具
```

例如用户说：

```text
帮我生成一份学习 AI 的计划文档放到知识库
```

模型看到：

- 用户说“生成文档”
- 用户说“放到知识库”
- 工具列表里有 `createKnowledgeDocument`
- 该工具描述是创建知识库文档

所以模型应该调用：

```text
createKnowledgeDocument
```

再比如用户说：

```text
帮我查一下知识库里有没有 Gateway 的内容
```

模型应该选择：

```text
searchKnowledgeBase
```

再比如：

```text
帮我列出我上传过的文件
```

模型应该选择：

```text
listUploadedFiles
```

## 8. 系统提示词如何约束工具调用

`AgentService.buildSystemPrompt()` 中对模型做了约束。

关键规则包括：

```text
当用户要求 create/save/store/put document into knowledge base 时，必须调用 createKnowledgeDocument。
不能在工具成功前声称文档已保存。
删除动作不能直接执行，必须走 requestDeleteKnowledgeDocument。
不要猜测真实项目或用户数据，应该调用工具读取。
```

这能减少幻觉，但不能 100% 保证模型每次都正确调用工具。

所以后面又做了服务端兜底。

## 9. 为什么还需要服务端兜底

实际测试中出现过这种情况：

```text
模型说：我已经帮你存入知识库
数据库里确实有文档
但是前端没有显示文档卡片
```

这说明：

- 写入动作可能成功了
- 但工具事件没有稳定透传到前端
- 或者模型输出和工具事件顺序不稳定

因此新增了一个后端兜底机制：

```text
Agent 请求开始前：
  记录当前用户最新 knowledge_document.id

Agent 回复结束前：
  查询当前用户是否新增了 id 更大的知识文档

如果有：
  主动发送 knowledge_document_created SSE 事件
```

这样前端文档卡片不再只依赖模型是否正确返回工具事件，而是以后端数据库实际新增结果为准。

## 10. 文档卡片是怎么出现的

前端监听 SSE 事件：

```text
knowledge_document_created
```

收到事件后，把文档信息加入：

```javascript
state.documentCards
```

然后渲染成聊天气泡中的文档卡片：

```text
标题
Document · MD · saved to file manager
Open
Download
```

点击卡片或 `Open` 会调用：

```text
GET /api/knowledge/documents/{documentId}
```

下载按钮会调用：

```text
/api/files/download/{fileKey}
```

## 11. 为什么文件管理里也能看到 Agent 生成的文档

之前 Agent 创建的是知识库数据：

```text
knowledge_document
```

但文件管理页展示的是：

```text
file_record
```

所以只写知识库，文件管理页天然看不到。

现在补了 file-service 接口：

```text
POST /api/files/generated/knowledge
```

Agent 创建知识库文档后，`RagService` 会把内容同步生成一份 Markdown 文件：

```text
xxx.md
```

并写入：

```text
file_record
```

业务类型：

```text
KNOWLEDGE_DOC
```

然后把生成的 `fileKey` 回填到：

```text
knowledge_document.file_key
```

这样同一份内容同时具备：

- 知识库检索能力
- 文件管理能力
- 下载能力
- 聊天页文档卡片打开能力

## 12. 工具调用前端展示

前端现在会显示真实工具函数名，而不是中文翻译名。

例如：

```text
使用 createKnowledgeDocument    执行中
使用 createKnowledgeDocument    完成
```

这样用户能明确看到 Agent 调用了哪个工具。

前端主要监听：

```text
tool_call_started
tool_call_result
tool_call_error
knowledge_document_created
```

其中：

- `tool_call_started`：工具开始
- `tool_call_result`：工具完成
- `tool_call_error`：工具失败
- `knowledge_document_created`：文档创建结果，用于显示卡片

## 13. 工具审计是怎么实现的

每个工具调用都会通过：

```text
AgentToolAuditService
```

写入：

```text
agent_tool_execution_log
```

记录内容包括：

- userId
- sessionId
- toolName
- toolLevel
- arguments
- result
- status
- errorMessage
- startedTime
- finishedTime

这让 Agent 的动手行为可以追踪。

面试时可以说：

> 我没有让 Agent 黑盒执行操作，而是给每个工具调用加了审计日志。这样用户能看到工具调用状态，后端也能追踪工具调用参数、结果和失败原因。

## 14. 高风险动作为什么要 pending action

创建知识库文档属于低风险写操作，可以直接执行。

删除知识库文档属于高风险操作，所以不能让模型直接删。

删除流程是：

```text
用户要求删除
  -> Agent 调用 requestDeleteKnowledgeDocument
  -> 后端创建 agent_pending_action
  -> 前端显示 Confirm 按钮
  -> 用户点击确认
  -> POST /api/chat/agent/actions/{actionId}/confirm
  -> 真正删除
```

这样可以避免模型误删数据。

## 15. RAG 当前状态

当前生产默认仍是关键词 RAG：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

原因是远程服务器只有 4G 内存，不适合同时跑：

- MySQL
- Redis
- Kafka
- Nacos
- Gateway
- chatbot-service
- file-service
- PGVector
- embedding 模型

所以向量库和 embedding 已经预留，但生产默认关闭。

预留能力包括：

- `EmbeddingClient`
- `PgVectorClient`
- `VectorIndexingService`
- `VectorRagService`
- `DocumentChunker`
- `docker compose --profile vector`

后续资源足够时可切换：

```text
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
```

## 16. 当前限制

当前 Agent 不是无限制服务器执行 Agent。

它只能调用后端显式暴露的安全工具。

当前不能做：

- 任意执行 shell 命令
- 任意修改数据库
- 任意删除文件
- 自动操作外部网站
- 直接控制服务器

这是有意设计的。

原因是智能客服系统面向真实用户，必须把 Agent 能力收敛在安全边界内。

## 17. 后续可以继续增强的方向

后续可以继续做：

- 增加更多业务工具，例如订单查询、工单创建、用户资料更新
- 增加工具调用参数展示
- 增加工具调用耗时展示
- 增加工具失败重试
- 增加管理员审计页面
- 增加 MCP Server，把部分工具标准化为 MCP 能力
- 接入外部 embedding API 和 PGVector，启用 hybrid RAG
- 增加文档编辑工具，让 Agent 能修改已有知识文档
- 增加文件内容读取工具，让 Agent 能读取上传文档全文

## 18. 面试表达版本

可以这样介绍：

> 我把原来的智能客服从普通 Chatbot 升级成了一个应用内 AI Agent。普通 Chatbot 只能基于 prompt 生成文本，而现在的 Agent 可以通过 Spring AI 的 Tool Calling 调用后端工具，读取知识库、读取文件列表、读取聊天历史，也可以创建知识库文档。
>
> 我没有让模型直接操作数据库，而是把每个能力封装成受控工具，并且通过 ToolContext 注入 userId 和 sessionId，保证工具只能操作当前用户的数据。每次工具调用都会写审计日志。低风险操作，比如创建知识库文档，可以直接执行；高风险操作，比如删除文档，会先创建 pending action，前端让用户二次确认后才真正执行。
>
> 为了解决模型可能声称保存但前端没有卡片的问题，我还做了服务端兜底：Agent 请求开始时记录当前用户最新知识文档 ID，请求结束前扫描本轮新增文档，只要数据库实际新增了文档，就主动发送 SSE 事件给前端生成可点击文档卡片。这样前端展示以后端真实结果为准，而不是完全依赖模型输出。
>
> RAG 方面，当前生产环境考虑到 4G 服务器资源限制，默认仍使用关键词 RAG；但我已经预留了 embedding、PGVector 和 hybrid RAG 的实现，后续资源足够时可以切换到向量检索。
