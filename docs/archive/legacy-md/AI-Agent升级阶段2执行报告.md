# AI Agent 升级阶段 2 执行报告

执行时间：2026-06-04

## 1. 阶段目标

阶段 2 目标是实现工具调用审计与可控写操作。

本阶段仍不引入向量库、embedding、MCP 或新重型服务。

## 2. 已完成改动

### 2.1 新增数据库迁移

新增：

```text
chatbot-service/src/main/resources/db/migration/V3__add_agent_tool_audit_tables.sql
```

新增表：

```text
agent_tool_execution_log
agent_pending_action
```

用途：

- `agent_tool_execution_log`：记录工具调用用户、会话、工具名、权限级别、参数、结果、状态和耗时边界。
- `agent_pending_action`：记录需要用户确认后才能执行的动作。

### 2.2 新增实体和 Mapper

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/entity/AgentToolExecutionLog.java
chatbot-service/src/main/java/com/example/chatbot/entity/AgentPendingAction.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentToolExecutionLogMapper.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentPendingActionMapper.java
```

### 2.3 新增审计服务

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolAuditService.java
chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolLevel.java
```

权限级别：

```text
READ_ONLY
LOW_RISK_WRITE
REQUIRE_CONFIRMATION
```

审计行为：

- 工具开始：插入 `RUNNING`。
- 工具成功：更新 `SUCCESS`。
- 工具失败：更新 `FAILED`。
- 审计失败不会直接中断工具主流程。

### 2.4 新增 Pending Action 服务与确认接口

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentPendingActionService.java
chatbot-service/src/main/java/com/example/chatbot/controller/AgentActionController.java
```

新增接口：

```text
POST /api/chat/agent/actions/{actionId}/confirm
```

当前支持的确认动作：

```text
DELETE_KNOWLEDGE_DOCUMENT
```

确认执行规则：

- 必须是当前登录用户自己的 pending action。
- 状态必须是 `PENDING`。
- 未过期。
- 确认后才调用 `RagService.deleteDocument()`。
- 过期或失败时标记 `FAILED`。

### 2.5 新增可控写工具

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/tool/KnowledgeWriteTools.java
```

工具：

- `createKnowledgeDocument`
  - 低风险写操作。
  - 直接创建当前用户知识文档。
  - 写入审计日志。

- `requestDeleteKnowledgeDocument`
  - 不直接删除。
  - 只创建 pending action。
  - 返回 `actionId` 和确认路径。
  - 用户确认后才真正删除。

### 2.6 只读工具接入审计

阶段 1 的只读工具已接入审计：

```text
KnowledgeReadTools
FileReadTools
ChatHistoryTools
TimeTools
```

覆盖工具：

- `searchKnowledge`
- `getFileInfo`
- `listUserFiles`
- `getCurrentChatHistory`
- `getChatHistoryBySession`
- `getCurrentTime`

### 2.7 AgentService 接入写工具

修改：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java
```

新增注册：

```text
KnowledgeWriteTools
```

系统提示词更新：

- 允许明确请求下创建知识文档。
- 删除必须请求 pending action。
- 不能声称已直接删除敏感资源。

### 2.8 配置

修改：

```text
chatbot-service/src/main/resources/application.yml
```

新增：

```yaml
app:
  agent:
    pending-action-expire-minutes: 10
```

## 3. 新增测试

新增：

```text
chatbot-service/src/test/java/com/example/chatbot/agent/AgentPendingActionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/agent/KnowledgeWriteToolsTest.java
```

更新：

```text
chatbot-service/src/test/java/com/example/chatbot/agent/AgentToolSecurityTest.java
```

覆盖内容：

- 只读工具会写审计。
- 工具 schema 仍不暴露 `userId`、`sessionId`、`ToolContext`。
- 越权 session 被拒绝并记录失败审计。
- 创建知识文档工具直接调用 `RagService.createDocument()`。
- 删除知识文档工具只创建 pending action。
- pending action 确认后才执行删除。
- 过期 pending action 被拒绝并标记失败。

## 4. 已执行测试

### 4.1 阶段 2 指定测试

```bash
mvn -q -pl chatbot-service "-Dtest=AgentToolSecurityTest,AgentPendingActionServiceTest,KnowledgeWriteToolsTest" test
```

结果：通过。

### 4.2 chatbot-service 构建

```bash
mvn -q -pl chatbot-service -DskipTests package
```

结果：通过。

说明：

- 曾经并行执行 `chatbot-service package` 和全工程 package，单模块 repackage 出现 `zip file is empty`。
- 顺序重跑后通过，判断为并行 Maven 写同一 target 产物导致的竞争，不是代码问题。

### 4.3 全工程构建

```bash
mvn -q -DskipTests package
```

结果：通过。

### 4.4 Compose 配置

```bash
docker compose config
docker compose -f docker-compose.prod.yml config
```

结果：通过。

## 5. 未完成或后续项

### 5.1 前端 pending action 确认 UI

本阶段已提供确认 API：

```text
POST /api/chat/agent/actions/{actionId}/confirm
```

但尚未在 `chat.html` 中增加 pending action 确认按钮。

原因：

- 阶段 2 的核心目标是服务端安全边界和审计链路。
- 前端确认 UI 可以作为阶段 2 后续增强或阶段 5 前的体验补齐。

### 5.2 更多写操作工具

当前只实现：

- 创建知识文档。
- 请求删除知识文档。

暂未实现：

- 删除文件。
- 发送邮件。
- 发送通知。

原因：

- 先用知识库文档做最小闭环，降低阶段 2 风险。
- 删除文件、发邮件属于更高风险操作，建议后续单独加测试和前端确认。

## 6. 已知非阶段 2 问题

`git diff --check` 仍显示：

```text
chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java:369: trailing whitespace.
```

说明：

- 这是阶段 1 前已存在的改动问题。
- 本阶段未修改该行，避免回退或干扰用户已有改动。

`docker compose config` 仍会展开本地 `.env` 中的敏感值。

说明：

- 阶段 0 已记录该风险。
- 后续建议单独做密钥治理和轮换。

## 7. 阶段 2 通过标准对照

| 标准 | 结果 |
|---|---|
| 工具调用有审计表 | 通过 |
| 只读工具接入审计 | 通过 |
| 低风险写工具可审计 | 通过 |
| 删除类动作需要 pending action | 通过 |
| 确认接口校验用户和过期时间 | 通过 |
| Agent 不直接删除知识文档 | 通过 |
| 阶段 2 单元测试通过 | 通过 |
| 全工程构建通过 | 通过 |
| 未新增重型服务 | 通过 |

## 8. 阶段 2 结论

阶段 2 通过。

可以进入阶段 3：Hybrid RAG 与向量检索。

进入阶段 3 前必须继续遵守：

- 向量能力可关闭、可降级。
- 4G 云服务器不本地跑 embedding 模型。
- PGVector 不作为聊天主链路强依赖。
- 关键词 RAG 必须保留兜底。
