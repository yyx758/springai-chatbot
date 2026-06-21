# AI Studio — Agent 工具调用系统完整指南

## 1. 整体架构

```
用户: "帮我创建一篇关于 RAG 的知识文档"
                │
                ▼
┌──────────────────────────────────────────────────────────┐
│                   AgentService.streamAgent()              │
│                                                          │
│  ① 构建消息列表                                           │
│     System Prompt + 历史对话 + 自动RAG注入 + 用户消息       │
│                                                          │
│  ② 注册 7 类工具到 ChatClient                              │
│     .tools(knowledgeReadTools, knowledgeWriteTools, ...)  │
│                                                          │
│  ③ 调用 LLM（流式）                                       │
│     LLM 收到工具列表 + 用户消息 → 决定调用哪个工具          │
│                                                          │
│  ④ Spring AI 执行 @Tool 方法                              │
│     自动反序列化参数 → 调用业务逻辑 → 返回结果给 LLM         │
│                                                          │
│  ⑤ LLM 基于工具结果生成最终回复                             │
│     流式推送给前端                                         │
└──────────────────────────────────────────────────────────┘
```

---

## 2. 工具是怎么注册给模型的

### 2.1 Function Calling 协议

OpenAI 定义的协议：模型可以输出 JSON 描述要调用哪个函数、传什么参数。

### 2.2 Spring AI 自动转换

当你调用 `.tools(...)` 时，Spring AI 自动将 `@Tool` 注解转换为 Function Calling 格式：

```java
// AgentService.java:135-139
ChatClient.create(model)
    .prompt()
    .messages(messages)
    .tools(knowledgeReadTools, knowledgeWriteTools, fileReadTools,
           chatHistoryTools, timeTools, workspaceTools, webTools)
    .toolContext(toolContext)
    .stream()
    .content()
    .subscribe(...);
```

Spring AI 读取每个 `@Tool` 方法的注解，自动生成 JSON Schema：

```json
{
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "createKnowledgeDocument",
        "description": "Create a knowledge base document for the current user.",
        "parameters": {
          "type": "object",
          "properties": {
            "title": {"type": "string", "description": "Document title"},
            "content": {"type": "string", "description": "Document content"},
            "tags": {"type": "string", "description": "Optional tags"}
          },
          "required": ["title", "content"]
        }
      }
    }
  ]
}
```

### 2.3 LLM 决定调用哪个工具

LLM 收到工具列表 + 用户消息后，输出 `tool_calls`：

```json
{
  "tool_calls": [{
    "function": {
      "name": "createKnowledgeDocument",
      "arguments": "{\"title\":\"RAG 详解\",\"content\":\"...\",\"tags\":\"AI\"}"
    }
  }]
}
```

### 2.4 Spring AI 自动执行

Spring AI 收到 `tool_calls` → 匹配 `@Tool` 方法 → 反序列化参数 → 调用 → 返回结果给 LLM。

**整个过程：你只写 `@Tool` 注解，Spring AI 帮你处理协议转换、参数反序列化、结果序列化。**

---

## 3. 工具风险分级

### 3.1 三级分类

```java
// AgentToolLevel.java
public static final String READ_ONLY = "READ_ONLY";              // 只读，无风险
public static final String LOW_RISK_WRITE = "LOW_RISK_WRITE";   // 低风险写，有审计
public static final String REQUIRE_CONFIRMATION = "REQUIRE_CONFIRMATION"; // 需确认
```

### 3.2 每个工具的风险等级

| 工具 | 风险等级 | 说明 |
|------|---------|------|
| `listAllKnowledgeDocuments` | READ_ONLY | 列出所有文档 |
| `searchKnowledge` | READ_ONLY | 语义搜索 |
| `getFileInfo` / `listUserFiles` | READ_ONLY | 文件操作 |
| `getCurrentChatHistory` / `getChatHistoryBySession` | READ_ONLY | 聊天历史 |
| `getCurrentTime` | READ_ONLY | 时间查询 |
| `searchWeb` / `fetchWebPage` | READ_ONLY | 网页抓取 |
| `readWorkspaceFile` / `listWorkspaceFiles` | READ_ONLY | 工作区读取 |
| `createKnowledgeDocument` | LOW_RISK_WRITE | 创建知识文档 |
| `createWorkspaceFile` / `updateWorkspaceFile` / `appendWorkspaceFile` | LOW_RISK_WRITE | 工作区写入 |
| `saveWorkspaceFileToKnowledge` / `createWorkspaceFileFromWebPage` | LOW_RISK_WRITE | 保存到知识库 |
| `requestDeleteKnowledgeDocument` | **REQUIRE_CONFIRMATION** | **删除，需用户确认** |

### 3.3 风险等级如何生效

每个 `@Tool` 方法在执行时声明自己的风险等级：

```java
// KnowledgeWriteTools.java
@Tool(description = "Request deletion of a knowledge base document...")
public Map<String, Object> requestDeleteKnowledgeDocument(...) {
    Long auditId = auditService.start(toolContext, toolName,
        AgentToolLevel.REQUIRE_CONFIRMATION,  // ← 声明风险等级
        Map.of("documentId", documentId));
    // ...
}
```

`REQUIRE_CONFIRMATION` 级别的工具不直接执行，而是创建 Pending Action：

```java
AgentPendingAction action = pendingActionService
    .requestDeleteKnowledgeDocument(userId, sessionId, documentId, reason);
// 返回: { requiresConfirmation: true, actionId: 456 }
// 前端渲染 Confirm 按钮，用户点击后才真正执行
```

---

## 4. 工具执行生命周期

每个 `@Tool` 方法遵循统一的执行模式：

```
toolNotifier.toolStarted(toolName)              → SSE: tool_call_started
    │
auditService.start(toolContext, toolName,       → 写入审计日志 (status=RUNNING)
    toolLevel, arguments)
    │
contextResolver.requireUserId(toolContext)      → 提取 userId
contextResolver.requireSessionId(toolContext)   → 提取 sessionId
contextResolver.ensureSessionOwnedByUser()      → 校验会话归属
    │
执行业务逻辑...
    │
    ├── 成功 ──→ auditService.success(logId, result)   → 更新日志 (SUCCESS)
    │           toolNotifier.toolCompleted(toolName)   → SSE: tool_call_result
    │
    └── 失败 ──→ auditService.failure(logId, error)    → 更新日志 (FAILED)
                toolNotifier.toolFailed(toolName)      → SSE: tool_call_error
```

### 审计日志写入的字段

```sql
-- agent_tool_execution_log 表
INSERT INTO agent_tool_execution_log (
    user_id,           -- 谁调用的
    session_id,        -- 哪个会话
    tool_name,         -- 调了哪个工具
    tool_level,        -- 风险等级
    arguments_json,    -- 调用参数（JSON）
    status,            -- RUNNING → SUCCESS / FAILED
    started_time,      -- 开始时间
    finished_time,     -- 结束时间
    result_summary,    -- 结果摘要（最多 2000 字符）
    error_message      -- 错误信息
) VALUES (...)
```

### SSE 事件推送

```java
// AgentToolNotifier.java
private void emit(ToolContext toolContext, String eventName, Map<String, Object> payload) {
    SseEmitter emitter = getEmitter(toolContext);  // 从 ToolContext 获取 emitter
    emitter.send(SseEmitter.event().name(eventName).data(payload));
}
```

前端收到的事件流：

```
event: tool_call_started
data: {"toolName":"requestDeleteKnowledgeDocument"}

event: tool_call_result
data: {"toolName":"requestDeleteKnowledgeDocument","success":true,
       "requiresConfirmation":true,"actionId":456}

event: message
data: {"content":"已发起删除请求，请点击确认按钮..."}
```

---

## 5. 7 类工具详解

### 5.1 KnowledgeReadTools（知识库读取）

```java
// 列出所有文档（给 LLM 用，不是搜索）
@Tool(description = "List ALL knowledge documents for the current user.
      Use this when the user asks to see all documents, manage documents,
      delete documents, or perform any batch operation on the knowledge base.")
public List<Map<String, Object>> listAllKnowledgeDocuments(ToolContext toolContext)

// 语义搜索知识库
@Tool(description = "Search the current user's knowledge base by semantic similarity.
      Use this when the user asks a QUESTION about their knowledge content.")
public List<RagReference> searchKnowledge(String query, Integer topK, ToolContext toolContext)
```

**关键设计**：`listAllKnowledgeDocuments` 和 `searchKnowledge` 是两个独立工具。LLM 需要根据用户意图选择——"列出所有"用前者，"问某个问题"用后者。

### 5.2 KnowledgeWriteTools（知识库写入）

```java
// 创建知识文档（LOW_RISK_WRITE，直接执行）
@Tool(description = "Create a knowledge base document for the current user.")
public Map<String, Object> createKnowledgeDocument(String title, String content,
        String tags, Boolean enabled, ToolContext toolContext)

// 请求删除（REQUIRE_CONFIRMATION，不直接执行）
@Tool(description = "Request deletion of a knowledge base document.
      This does not delete immediately; it creates a pending action
      that the user must confirm.")
public Map<String, Object> requestDeleteKnowledgeDocument(
        Long documentId, String reason, ToolContext toolContext)
```

**删除流程**：
```
LLM 调用 requestDeleteKnowledgeDocument(documentId=13)
    → 不直接删除
    → 创建 PendingAction (status=PENDING, expireTime=+10min)
    → 返回 { requiresConfirmation: true, actionId: 456 }
    → 前端渲染 Confirm 按钮
    → 用户点击 → POST /api/chat/agent/actions/456/confirm
    → 后端校验 → 执行删除
```

### 5.3 FileReadTools（文件读取）

```java
@Tool(description = "Get file metadata without downloading content.")
public Map<String, Object> getFileInfo(String fileKey, ToolContext toolContext)

@Tool(description = "List uploaded files with pagination.")
public List<Map<String, Object>> listUserFiles(Integer page, Integer size, ToolContext toolContext)
```

### 5.4 ChatHistoryTools（聊天历史）

```java
@Tool(description = "Read recent messages from the CURRENT session.")
public List<Map<String, Object>> getCurrentChatHistory(Integer limit, ToolContext toolContext)

@Tool(description = "Read from a SPECIFIC session.")
public List<Map<String, Object>> getChatHistoryBySession(String sessionId, Integer limit, ToolContext toolContext)
```

**安全设计**：`ensureSessionOwnedByUser()` 校验 sessionId 必须以 `userId_` 开头。

### 5.5 TimeTools（时间）

```java
@Tool(description = "Get current server time.")
public Map<String, Object> getCurrentTime(ToolContext toolContext)
```

### 5.6 WebTools（网页抓取）

```java
@Tool(description = "Search the public web.")
public Map<String, Object> searchWeb(String query, Integer limit, ToolContext toolContext)

@Tool(description = "Fetch readable markdown from a public web page.")
public Map<String, Object> fetchWebPage(String url, ToolContext toolContext)

@Tool(description = "Fetch a web page and save as markdown in workspace.")
public Map<String, Object> createWorkspaceFileFromWebPage(String url, String relativePath, ToolContext toolContext)
```

**安全设计**：`WebToolService.validateExternalUrl()` 阻止 localhost、loopback、link-local 等内网地址。

### 5.7 WorkspaceTools（工作区）

```java
@Tool(description = "Create a file in the workspace.")
public Map<String, Object> createWorkspaceFile(String relativePath, String content, ...)

@Tool(description = "Read a workspace file.")
public Map<String, Object> readWorkspaceFile(String relativePath, ToolContext toolContext)

@Tool(description = "Replace an existing workspace file.")
public Map<String, Object> updateWorkspaceFile(String relativePath, String content, Integer expectedVersion, ...)

@Tool(description = "Append content to a workspace file.")
public Map<String, Object> appendWorkspaceFile(String relativePath, String content, ...)

@Tool(description = "List all files in the workspace.")
public List<Map<String, Object>> listWorkspaceFiles(ToolContext toolContext)

@Tool(description = "Save a workspace file into the knowledge base.")
public Map<String, Object> saveWorkspaceFileToKnowledge(String relativePath, String title, ...)
```

**安全设计**：`normalizePath()` 阻止 `..` 遍历、保留目录名、文件扩展名白名单。

---

## 6. System Prompt 工具引导

[AgentService.java:199-228](chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java#L199-L228) 的系统提示词告诉 LLM 什么时候该调什么工具：

```
KNOWLEDGE BASE TOOLS — STRICT rules:
1. "列出/查看/搜索/管理/所有/全部" → ALWAYS call listAllKnowledgeDocuments
2. "XXX是什么/怎么用/原理" → call searchKnowledge
3. "创建/保存/写入" → call createKnowledgeDocument
4. "删除" → call listAllKnowledgeDocuments first, then requestDeleteKnowledgeDocument

CRITICAL RULE: NEVER generate markdown links for confirmation.
When a tool returns requiresConfirmation=true, the Confirm button is ALREADY rendered.
You MUST simply say "请点击上方确认按钮来完成操作".
```

**System Prompt + 工具描述 = LLM 的"操作手册"。**

---

## 7. Pending Action 两阶段确认

### 7.1 为什么要两阶段

```
LLM 可能误判用户意图
    → 调用删除工具
    → 如果直接执行 → 数据丢失，无法撤销

两阶段确认：
    LLM 调用删除工具 → 不直接执行 → 创建 PendingAction
    → 前端渲染 Confirm 按钮 → 用户点确认 → 才执行
```

### 7.2 数据库保存的字段

`agent_pending_action` 表：

| 字段 | 示例值 | 说明 |
|------|--------|------|
| id | 456 | 自增主键 |
| user_id | 2 | 谁发起的 |
| session_id | "2_abc123" | 哪个会话 |
| action_type | "DELETE_KNOWLEDGE_DOCUMENT" | 操作类型 |
| tool_name | "requestDeleteKnowledgeDocument" | 调用的工具 |
| arguments_json | `{"documentId":13,"title":"测试专用"}` | 操作参数 |
| status | **PENDING** | 当前状态 |
| expire_time | 2026-06-09 17:16:00 | 过期时间（+10min） |
| created_time | 2026-06-09 17:06:00 | 创建时间 |
| confirmed_time | NULL | 确认时间 |
| result_summary | NULL | 执行结果 |
| error_message | NULL | 错误信息 |

### 7.3 状态流转

```
LLM 调用 requestDeleteKnowledgeDocument
            │
            ▼
    ┌──────────────────┐
    │ status: PENDING  │
    │ expire: +10min   │
    └────────┬─────────┘
             │
    ┌────────┼────────┐
    │        │        │
    ▼        ▼        ▼
 用户点    过期     页面关闭
 Confirm   自动失效   等待
    │        │        │
    ▼        ▼        ▼
 CONFIRMED  FAILED   PENDING
```

### 7.4 确认接口的安全防护

```java
// AgentPendingActionService.confirm()
public AgentPendingAction confirm(Long userId, Long actionId) {
    // 1. userId 校验：必须是创建者
    AgentPendingAction action = pendingActionMapper.selectOne(
        WHERE id=? AND userId=?);

    // 2. 状态校验：必须是 PENDING
    if (!"PENDING".equals(action.getStatus())) throw ...;

    // 3. 过期校验
    if (action.getExpireTime().isBefore(LocalDateTime.now())) throw ...;

    // 4. 执行删除
    ragService.deleteDocument(userId, documentId);
}
```

---

## 8. 工具上下文传递（ToolContext）

### 8.1 ToolContext 的构建

```java
// AgentService.java:120-123
Map<String, Object> toolContext = new LinkedHashMap<>();
toolContext.put(AgentToolContextKeys.USER_ID, userId);      // 用户 ID
toolContext.put(AgentToolContextKeys.SESSION_ID, sessionId); // 会话 ID
toolContext.put(AgentToolContextKeys.EMITTER, emitter);      // SSE 连接
```

### 8.2 为什么需要 ToolContext

```
没有 ToolContext：
  工具被调用时不知道是谁在调用
  → 无法校验权限
  → 无法知道操作哪个用户的数据
  → 无法向前端推送状态

有了 ToolContext：
  工具从 context 取 userId → 校验权限
  工具从 context 取 sessionId → 校验会话归属
  工具从 context 取 emitter → 推送 SSE 事件
```

### 8.3 会话归属校验

```java
// AgentToolContextResolver.java
public void ensureSessionOwnedByUser(String sessionId, Long userId) {
    if (!sessionId.startsWith(userId + "_")) {
        throw new IllegalArgumentException("session does not belong to current user");
    }
}
```

---

## 9. MCP 服务

### 9.1 MCP vs Agent 工具调用

| | Agent 工具调用 | MCP 工具调用 |
|---|---|---|
| 入口 | LLM Function Calling | REST API POST |
| 调用方 | 你的 Agent（LLM） | 外部 AI 客户端 |
| 审计日志 | ✅ 有 | ❌ 没有 |
| SSE 实时推送 | ✅ 有 | ❌ 没有 |
| 工具范围 | 全部 7 类 18 个工具 | 白名单子集 9 个 |
| 协议 | Spring AI @Tool 注解 | REST /api/mcp/invoke |

### 9.2 MCP 暴露的工具

```java
// McpToolGateway.java 的 toolCatalog()
"knowledge.search"      → READ_ONLY   → searchKnowledge()
"files.info"            → READ_ONLY   → getFileInfo()
"files.list"            → READ_ONLY   → listUserFiles()
"chat.history"          → READ_ONLY   → getCurrentChatHistory()
"workspace.files.list"  → READ_ONLY   → listFiles()
"workspace.files.read"  → READ_ONLY   → readFileContent()
"workspace.files.create" → LOW_RISK_WRITE → createFile()
"workspace.files.update" → LOW_RISK_WRITE → updateFile()
"knowledge.create"      → LOW_RISK_WRITE → createKnowledgeDocument()
```

**默认只暴露 READ_ONLY 工具**，写操作需要手动配置白名单。

---

## 10. 安全防御全景

### 10.1 四层防护

```
第 1 层：System Prompt 引导
  → 告诉 LLM 什么时候该调什么工具
  → 减少误判概率

第 2 层：工具风险分级
  → READ_ONLY / LOW_RISK_WRITE / REQUIRE_CONFIRMATION
  → 不同级别不同处理

第 3 层：会话归属校验
  → ensureSessionOwnedByUser()
  → 每个工具调用前校验

第 4 层：Pending Action 确认
  → 删除操作必须用户确认
  → LLM 误判也不会真正执行
```

### 10.2 Prompt Injection 防护

```
恶意文档注入 → LLM 可能被诱导调用工具
    ↓
第 4 层兜底：REQUIRE_CONFIRMATION
    → 删除操作需要用户确认
    → 用户不点确认 → 不执行

弱点：CREATE 类操作（创建文档、写工作区文件）没有确认机制
```

### 10.3 网页抓取安全

`WebToolService.validateExternalUrl()` 阻止：
- localhost、127.0.0.1、0.0.0.0
- 169.254.169.254（云服务元数据）
- link-local、site-local、multicast
- 可配置的 host 黑名单

### 10.4 工作区安全

`normalizePath()` 阻止：
- `..` 路径遍历
- `/`、`~`、盘符开头的绝对路径
- `.git`、`node_modules`、`target` 等保留目录
- 超长路径（>512 字符）
- 不在白名单中的文件扩展名

---

## 11. 完整调用链路图

```
用户: "帮我创建一篇关于 RAG 的知识文档"
                │
                ▼
前端 → SSE 连接 → AgentService.streamAgent()
                │
                ├── buildSystemPrompt()  → System Prompt
                ├── buildMessages()      → 历史对话 + 用户消息
                ├── enrichWithAutoRag()  → 自动搜索知识库注入上下文
                ├── enrichWithMandatoryWebFetch() → 如果有 URL 则抓取
                │
                ▼
ChatClient.create(model)
    .tools(7类工具)
    .toolContext({userId, sessionId, emitter})
    .stream()
                │
                ▼
LLM 收到: System Prompt + 工具列表 + 用户消息
    │
    LLM 判断: 用户要创建文档
    │
    ▼
输出 tool_calls: [{function: {name: "createKnowledgeDocument", arguments: "{...}"}}]
                │
                ▼
Spring AI 反序列化参数 → 调用 @Tool 方法
                │
                ▼
KnowledgeWriteTools.createKnowledgeDocument()
    ├── toolNotifier.toolStarted()          → SSE: tool_call_started
    ├── auditService.start()                → 审计日志 RUNNING
    ├── contextResolver.requireUserId()     → 提取 userId
    ├── ragService.createDocument()         → 执行业务逻辑
    ├── auditService.success()              → 审计日志 SUCCESS
    ├── toolNotifier.toolCompleted()        → SSE: tool_call_result
    └── return result
                │
                ▼
Spring AI 把结果返回给 LLM
    │
    LLM 基于结果生成回复:
    "已成功创建知识文档《RAG 详解》..."
    │
    ▼
流式推送给前端
```

---

## 12. 文件结构

```
agent/
├── AgentService.java                 # Agent 主服务（入口）
├── AgentToolLevel.java               # 工具风险分级常量
├── AgentToolAuditService.java        # 审计日志服务
├── AgentToolNotifier.java            # SSE 实时推送
├── AgentToolContextResolver.java     # 上下文解析（userId/sessionId）
├── AgentToolContextKeys.java         # 上下文 Key 常量
├── AgentPendingActionService.java    # Pending Action 两阶段确认
└── tool/
    ├── KnowledgeReadTools.java       # 知识库读取（2 个工具）
    ├── KnowledgeWriteTools.java      # 知识库写入（2 个工具）
    ├── FileReadTools.java            # 文件读取（2 个工具）
    ├── ChatHistoryTools.java         # 聊天历史（2 个工具）
    ├── TimeTools.java                # 时间查询（1 个工具）
    ├── WebTools.java                 # 网页抓取（3 个工具）
    └── WorkspaceTools.java           # 工作区操作（6 个工具）

mcp/
├── McpProperties.java                # MCP 配置
├── McpToolGateway.java               # MCP 工具网关
├── McpToolSpec.java                  # 工具元数据 DTO
└── McpToolInvocationRequest.java     # 调用请求 DTO

workspace/
├── AgentWorkspaceService.java        # 工作区业务逻辑
├── AgentWorkspaceProperties.java     # 工作区配置
├── WorkspaceFileCreateRequest.java
├── WorkspaceFileUpdateRequest.java
└── WorkspaceFileSaveToKnowledgeRequest.java
```
