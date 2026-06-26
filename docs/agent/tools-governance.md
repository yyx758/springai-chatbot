# Agent 工具治理框架设计文档

## 一、概述

Agent 工具治理框架是 AI Studio 平台的安全基础设施，解决 LLM 自主调用工具时的三个核心风险：

1. **误操作风险** — LLM 幻觉导致错误调用
2. **权限风险** — 工具越权访问其他用户数据
3. **不可逆风险** — 删除操作一旦执行无法回滚

---

## 二、架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                        前端 (chat.html)                         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────────┐ │
│  │ 工具面板    │ │ 文档卡片    │ │ 文件卡片    │ │ 确认按钮  │ │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ▲ SSE 事件流
                              │
┌─────────────────────────────────────────────────────────────────┐
│                     AgentService (入口)                         │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │ ChatClient.prompt().messages().tools().toolContext().stream() │
│  └───────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Spring AI Function Calling                    │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐               │
│  │ @Tool 注解  │ │ @ToolParam  │ │ ToolContext │               │
│  └─────────────┘ └─────────────┘ └─────────────┘               │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      治理框架 (核心)                             │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐   │
│  │ AgentToolLevel  │ │ AgentToolAudit  │ │ PendingAction   │   │
│  │ 三级风险分级    │ │ 审计日志        │ │ 两阶段确认      │   │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘   │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐   │
│  │ ToolNotifier    │ │ ContextResolver │ │ ToolContextKeys │   │
│  │ SSE 实时推送    │ │ 权限校验        │ │ 上下文常量      │   │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      工具层 (9 个工具类)                         │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ Knowledge    │ │ Workspace    │ │ Web          │            │
│  │ Read/Write   │ │ CRUD         │ │ Search/Fetch │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │ File         │ │ ChatHistory  │ │ Time/Git     │            │
│  │ Read         │ │ Query        │ │ Query        │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 三、核心模块详解

### 3.1 三级风险分级 (AgentToolLevel)

**最核心的设计决策：** 将工具按风险分为三级，决定工具的执行策略。

```java
public final class AgentToolLevel {
    public static final String READ_ONLY = "READ_ONLY";                    // 只读
    public static final String LOW_RISK_WRITE = "LOW_RISK_WRITE";          // 低风险写入
    public static final String REQUIRE_CONFIRMATION = "REQUIRE_CONFIRMATION"; // 需人工确认
}
```

| 级别 | 语义 | 执行策略 | 典型工具 |
|------|------|----------|----------|
| `READ_ONLY` | 只读，无副作用 | 直接执行 | `listAllKnowledgeDocuments`<br>`searchKnowledge`<br>`getCurrentTime`<br>`getGitStatus` |
| `LOW_RISK_WRITE` | 有写入，但可补救 | 直接执行 + 审计 | `createKnowledgeDocument`<br>`createWorkspaceFile`<br>`updateWorkspaceFile` |
| `REQUIRE_CONFIRMATION` | 不可逆操作 | 创建 PendingAction<br>等用户确认 | `requestDeleteKnowledgeDocument`<br>`requestApplyWorkspacePatch` |

**设计原则：宁可误报，不可漏判。**

---

### 3.2 审计日志 (AgentToolAuditService)

**核心价值：** 全链路追踪，支持事后追溯、安全审计、调试定位。

#### 数据库设计

```sql
CREATE TABLE agent_tool_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,                    -- 谁调用的
    session_id VARCHAR(255) NOT NULL,           -- 哪个会话
    tool_name VARCHAR(128) NOT NULL,            -- 工具名
    tool_level VARCHAR(32) NOT NULL,            -- 风险级别
    arguments_json TEXT NULL,                   -- 参数（JSON）
    result_summary TEXT NULL,                   -- 结果摘要（截断 2000 字符）
    status VARCHAR(32) NOT NULL,                -- 状态（RUNNING/SUCCESS/FAILED）
    error_message TEXT NULL,                    -- 错误信息
    started_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    finished_time TIMESTAMP NULL,
    INDEX idx_agent_tool_user_time (user_id, started_time),
    INDEX idx_agent_tool_session (session_id),
    INDEX idx_agent_tool_status (status)
);
```

#### 审计生命周期

```java
// ① 开始 — 记录调用意图
Long auditId = auditService.start(toolContext, toolName, level, arguments);

// ② 成功 — 记录结果
auditService.success(auditId, result);

// ③ 失败 — 记录错误
auditService.failure(auditId, exception);
```

**设计决策：**
- 审计失败不影响工具执行（`try-catch` 包裹，`log.warn` 记录）
- 结果摘要截断到 2000 字符，避免大结果撑爆数据库
- 支持按用户、会话、状态、时间范围查询

---

### 3.3 Pending Action 两阶段确认机制 ⭐ 最核心

**核心价值：** 对不可逆操作实现"调用 → 确认 → 执行"的三步流程，防止 LLM 误操作。

#### 流程图

```
用户: "删除Redis入门这篇文档"
    │
    ▼
LLM: tool_calls: [requestDeleteKnowledgeDocument(documentId=123)]
    │
    ▼
KnowledgeWriteTools.requestDeleteKnowledgeDocument()
    ├─ 校验文档存在且属于当前用户
    ├─ 创建 PendingAction (status=PENDING, 10分钟后过期)
    └─ 返回 { requiresConfirmation: true, actionId: 456 }
    │
    ▼
LLM (看到返回): "请点击上方确认按钮来完成操作"
    │
    ▼
前端: 渲染确认按钮
    │
    ▼
用户点击确认 → POST /api/chat/agent/actions/456/confirm
    │
    ▼
AgentPendingActionService.confirm()
    ├─ 校验 action 属于当前用户
    ├─ 校验 status == PENDING
    ├─ 校验未过期
    ├─ 执行真正的删除: ragService.deleteDocument(userId, 123)
    └─ 更新 status = CONFIRMED
```

#### 数据库设计

```sql
CREATE TABLE agent_pending_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    action_type VARCHAR(64) NOT NULL,           -- 操作类型
    tool_name VARCHAR(128) NOT NULL,            -- 工具名
    arguments_json TEXT NOT NULL,               -- 操作参数
    status VARCHAR(32) NOT NULL,                -- PENDING/CONFIRMED/CANCELLED/FAILED
    expire_time TIMESTAMP NOT NULL,             -- 过期时间（10分钟）
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    confirmed_time TIMESTAMP NULL,
    result_summary TEXT NULL,
    error_message TEXT NULL
);
```

#### 状态机

```
PENDING ──→ CONFIRMED   (用户确认，执行成功)
    │
    ├──→ CANCELLED     (用户取消)
    │
    └──→ FAILED         (执行失败或过期)
```

#### 工具返回格式

```java
@Tool(description = "Request deletion of a knowledge base document...")
public Map<String, Object> requestDeleteKnowledgeDocument(Long documentId, ...) {
    AgentPendingAction action = pendingActionService.requestDeleteKnowledgeDocument(...);
    
    return Map.of(
        "success", true,
        "requiresConfirmation", true,                    // 告诉 LLM 需要确认
        "actionId", action.getId(),                      // action ID
        "actionType", action.getActionType(),            // 操作类型
        "expireTime", action.getExpireTime(),            // 过期时间
        "confirmPath", "/api/chat/agent/actions/" + action.getId() + "/confirm"
    );
}
```

**LLM 看到 `requiresConfirmation: true` 后，会告诉用户"请点击确认按钮"，而不会声称已完成操作。**

---

### 3.4 SSE 实时推送 (AgentToolNotifier)

**核心价值：** 工具执行状态实时推送到前端，用户能看到完整过程。

#### 通用事件（所有工具都有）

```java
toolNotifier.toolStarted(toolContext, toolName);       // 工具开始
toolNotifier.toolCompleted(toolContext, toolName);      // 工具完成
toolNotifier.toolFailed(toolContext, toolName, error);  // 工具失败
```

#### 业务特定事件（特定工具才有）

```java
toolNotifier.knowledgeDocumentCreated(toolContext, result);     // 知识库文档创建
toolNotifier.workspaceFileCreated(toolContext, result);         // 工作区文件创建
toolNotifier.workspaceFileUpdated(toolContext, result);         // 工作区文件更新
toolNotifier.workspaceFileSavedToKnowledge(toolContext, result);// 文件保存到知识库
toolNotifier.webSearchStarted(toolContext, payload);            // 网页搜索开始
toolNotifier.webSearchCompleted(toolContext, payload);          // 网页搜索完成
```

**为什么需要业务特定事件？** 前端需要知道**发生了什么事**，而不仅仅是"工具执行完了"。`knowledge_document_created` 告诉前端"渲染文档卡片"，`tool_call_result` 只说"工具执行完了"。

---

### 3.5 权限校验 (AgentToolContextResolver)

**核心价值：** 防止用户 A 访问用户 B 的数据。

```java
@Component
public class AgentToolContextResolver {
    
    // 从 ToolContext 提取 userId
    public Long requireUserId(ToolContext context) { ... }
    
    // 从 ToolContext 提取 sessionId
    public String requireSessionId(ToolContext context) { ... }
    
    // 校验 session 属于当前用户
    public void ensureSessionOwnedByUser(String sessionId, Long userId) {
        if (!sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
    }
}
```

**每个工具方法都必须调用：**
```java
Long userId = contextResolver.requireUserId(toolContext);
String sessionId = contextResolver.requireSessionId(toolContext);
contextResolver.ensureSessionOwnedByUser(sessionId, userId);
```

---

## 四、工具统一模式（三件套）

**每个 @Tool 方法都遵循这个模板：**

```java
@Tool(description = "...")
public Result someTool(ToolContext toolContext) {
    String toolName = "someTool";
    
    // ① 通知前端：工具开始
    toolNotifier.toolStarted(toolContext, toolName);
    
    // ② 审计日志：记录开始
    Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, args);
    
    try {
        // ③ 权限校验
        Long userId = contextResolver.requireUserId(toolContext);
        
        // ④ 业务逻辑
        Result result = doSomething(userId);
        
        // ⑤ 审计成功 + 通知前端
        auditService.success(auditId, result);
        toolNotifier.toolCompleted(toolContext, toolName);
        
        return result;
    } catch (Exception e) {
        // ⑥ 审计失败 + 通知前端
        auditService.failure(auditId, e);
        toolNotifier.toolFailed(toolContext, toolName, e);
        
        throw e;
    }
}
```

---

## 五、前端渲染

### 5.1 状态管理

```javascript
state = {
    fullText: '',           // LLM 生成的文本
    toolEvents: [],         // 工具调用历史
    pendingActions: [],     // 待确认操作
    documentCards: [],      // 知识库文档卡片
    workspaceCards: []      // 工作区文件卡片
}
```

### 5.2 渲染层次

```html
<div class="bubble">
    <!-- 工具面板（上面） -->
    <div class="agent-tool-panel">
        <!-- 1. 工作区文件卡片 -->
        <div class="agent-doc-card">...</div>
        
        <!-- 2. 知识库文档卡片 -->
        <div class="agent-doc-card">...</div>
        
        <!-- 3. 工具执行历史（最近 6 个） -->
        <div class="agent-tool-row">✅ 使用 searchKnowledge ─── 完成</div>
        <div class="agent-tool-row">⚙️ 使用 createKnowledgeDocument ─── 执行中</div>
        
        <!-- 4. 待确认操作 -->
        <div class="agent-tool-row">🛡️ DELETE_DOCUMENT #456 ─── [Confirm]</div>
    </div>
    
    <!-- LLM 生成的文本（下面） -->
    <div class="markdown-body">...</div>
</div>
```

---

## 六、工具清单

### 6.1 知识库工具 (KnowledgeReadTools / KnowledgeWriteTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `listAllKnowledgeDocuments` | READ_ONLY | 列出当前用户所有知识库文档 |
| `searchKnowledge` | READ_ONLY | 语义搜索知识库 |
| `createKnowledgeDocument` | LOW_RISK_WRITE | 创建知识库文档 |
| `requestDeleteKnowledgeDocument` | REQUIRE_CONFIRMATION | 请求删除知识库文档（需确认） |

### 6.2 工作区工具 (WorkspaceTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `listWorkspaceFiles` | READ_ONLY | 列出工作区文件 |
| `readWorkspaceFile` | READ_ONLY | 读取工作区文件 |
| `createWorkspaceFile` | LOW_RISK_WRITE | 创建工作区文件 |
| `updateWorkspaceFile` | LOW_RISK_WRITE | 更新工作区文件 |
| `appendWorkspaceFile` | LOW_RISK_WRITE | 追加工作区文件内容 |
| `saveWorkspaceFileToKnowledge` | LOW_RISK_WRITE | 保存文件到知识库 |

### 6.3 文件工具 (FileReadTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `getFileInfo` | READ_ONLY | 获取文件元数据 |
| `listUserFiles` | READ_ONLY | 列出用户上传的文件 |

### 6.4 聊天历史工具 (ChatHistoryTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `getCurrentChatHistory` | READ_ONLY | 获取当前会话历史 |
| `getChatHistoryBySession` | READ_ONLY | 获取指定会话历史 |

### 6.5 网页工具 (WebTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `searchWeb` | READ_ONLY | 搜索网页 |
| `fetchWebPage` | READ_ONLY | 抓取网页内容 |
| `createWorkspaceFileFromWebPage` | LOW_RISK_WRITE | 从网页创建工作区文件 |

### 6.6 Git 工具 (GitReviewTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `getGitStatus` | READ_ONLY | 获取 Git 状态 |
| `getChangedFiles` | READ_ONLY | 获取变更文件列表 |
| `getFileDiff` | READ_ONLY | 获取文件 diff |

### 6.7 时间工具 (TimeTools)

| 工具名 | 风险级别 | 功能 |
|--------|----------|------|
| `getCurrentTime` | READ_ONLY | 获取当前服务器时间 |

---

## 七、最核心部分总结

### ⭐ 第一核心：Pending Action 两阶段确认机制

**为什么最核心？**
- 解决了 LLM 误操作的**不可逆风险**
- 删除操作一旦执行无法回滚，必须有人工确认
- 实现了"调用 → 确认 → 执行"的三步流程
- 10 分钟过期机制防止遗留风险

**关键代码路径：**
```
KnowledgeWriteTools.requestDeleteKnowledgeDocument()
    → AgentPendingActionService.requestDeleteKnowledgeDocument()
    → 创建 PendingAction (status=PENDING)
    → 返回 { requiresConfirmation: true, actionId: 456 }
    → 前端渲染 Confirm 按钮
    → 用户点击 → POST /api/chat/agent/actions/456/confirm
    → AgentPendingActionService.confirm()
    → 执行真正的删除
```

### ⭐ 第二核心：三级风险分级

**为什么核心？**
- 决定了每个工具的执行策略
- `READ_ONLY` 直接执行，`LOW_RISK_WRITE` 直接执行 + 审计，`REQUIRE_CONFIRMATION` 需要确认
- 是整个治理框架的**分类基础**

### ⭐ 第三核心：审计日志

**为什么核心？**
- 提供全链路追溯能力
- 支持事后审计、安全分析、调试定位
- 每次工具调用都记录：谁、什么时候、调用什么、传了什么参数、结果如何

---

## 八、设计决策与 Trade-off

### 8.1 为什么不用 AOP 统一处理审计？

**当前选择：显式三件套模式**

| 方面 | 显式写法 | AOP |
|------|----------|-----|
| 代码重复 | 每个工具 ~10 行重复 | 0 行重复 |
| 可读性 | 一眼看到完整流程 | 需要看注解 + 切面 |
| 灵活性 | 每个工具可定制 | 统一处理，定制需扩展注解 |
| SSE 事件定制 | `toolNotifier.workspaceFileCreated(...)` | 难以统一处理 |

**结论：** 业务特定的 SSE 事件（如 `knowledge_document_created`）无法用统一的 AOP 处理，需要在工具方法中手动调用。工具数量有限（~20 个），显式写法更直观。

### 8.2 为什么审计日志不抛异常？

```java
try {
    auditService.start(...);
} catch (Exception e) {
    log.warn("Audit failed", e);  // 不抛出
    return null;
}
```

**原因：** 审计日志是辅助功能，不应该影响工具执行。如果审计失败就抛异常，会导致工具无法执行，影响用户体验。

### 8.3 为什么 PendingAction 有 10 分钟过期？

**原因：**
- 防止用户忘记确认，遗留风险
- 10 分钟足够用户思考和确认
- 过期后自动标记为 FAILED，不执行操作

---

## 九、扩展点

### 9.1 新增工具

1. 创建工具类，添加 `@Component` 注解
2. 添加 `@Tool` 方法，指定 `description` 和 `@ToolParam`
3. 在工具方法中遵循三件套模式
4. 在 `AgentService` 中注册工具：`.tools(newToolClass)`

### 9.2 新增 PendingAction 类型

1. 在 `AgentPendingActionService` 中定义新的 `ACTION_TYPE`
2. 在 `requestXxx()` 方法中创建 PendingAction
3. 在 `confirm()` 方法中添加执行逻辑
4. 前端处理新的 `requiresConfirmation` 响应

### 9.3 新增审计字段

1. 修改 `agent_tool_execution_log` 表结构
2. 更新 `AgentToolExecutionLog` 实体类
3. 在 `AgentToolAuditService` 中记录新字段

---

## 十、相关文件索引

| 文件 | 职责 |
|------|------|
| `agent/AgentService.java` | Agent 主服务，注册工具到 ChatClient |
| `agent/AgentToolLevel.java` | 三级风险常量 |
| `agent/AgentToolAuditService.java` | 审计日志服务 |
| `agent/AgentToolNotifier.java` | SSE 实时推送 |
| `agent/AgentPendingActionService.java` | Pending Action 两阶段确认 |
| `agent/AgentToolContextResolver.java` | 权限校验 |
| `agent/AgentToolContextKeys.java` | ToolContext 键常量 |
| `agent/tool/*.java` | 9 个工具类 |
| `entity/AgentToolExecutionLog.java` | 审计日志实体 |
| `entity/AgentPendingAction.java` | PendingAction 实体 |
| `mapper/AgentToolExecutionLogMapper.java` | 审计日志 Mapper |
| `mapper/AgentPendingActionMapper.java` | PendingAction Mapper |
| `db/migration/V3__add_agent_tool_audit_tables.sql` | 建表 SQL |
