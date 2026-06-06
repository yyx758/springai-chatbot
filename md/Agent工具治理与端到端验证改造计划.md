# Agent 工具治理与端到端验证改造计划

## 目标

在不引入重型向量 RAG、不新增高内存中间件的前提下，把当前 Agent 的“动手能力”做得更稳定、可审计、可验证。

本阶段重点不是增加更多工具，而是让已有工具满足：

- 用户能清楚看到 Agent 调用了什么工具。
- 后端能完整记录工具调用、参数摘要、结果、耗时和失败原因。
- 写操作和高风险操作有明确权限边界。
- 需要确认的操作必须先进入 pending action，不能被模型直接执行。
- 每次部署后能通过脚本验证 Agent 基础链路。

## 当前基础

已有能力：

- Agent 流式接口：`/api/chat/agent/stream`
- 工具调用前端展示：`tool_call_started`、`tool_call_result`、`tool_call_error`
- 工具审计表：`agent_tool_execution_log`
- 待确认操作表：`agent_pending_action`
- 已有工具：
  - `getCurrentTime`
  - `searchKnowledge`
  - `openKnowledgeDocument`
  - `createKnowledgeDocument`
  - `requestDeleteKnowledgeDocument`
  - `getCurrentChatHistory`
  - `getChatHistoryBySession`
  - 文件读取相关工具
- 当前生产验证脚本：
  - `scripts/prod-health-check.sh`
  - `scripts/prod-e2e-verify.sh`

## 不做的事

本阶段不做：

- 不引入 Milvus/Qdrant/PGVector 作为必选部署项。
- 不把当前 tools 强行 MCP 化。
- 不新增本地 embedding 模型。
- 不做大范围前端重构。
- 不开放 Agent 直接删除用户、删除文件、执行任意 SQL、执行 shell 命令。

## 阶段 1：工具元数据与风险等级统一

### 改造内容

建立统一的工具定义规范，让每个工具都能被清楚识别和治理。

计划新增或完善：

- `AgentToolDefinition`
  - `toolName`
  - `displayName`
  - `description`
  - `level`
  - `requiresConfirmation`
  - `enabled`
- `AgentToolRegistry`
  - 集中维护工具元数据。
  - 前端展示优先使用后端传来的 `displayName`。
  - 审计记录使用稳定的 `toolName`。

工具风险等级建议：

| 等级 | 含义 | 示例 |
| --- | --- | --- |
| `READ_ONLY` | 只读，不修改业务数据 | 查时间、查知识库、读聊天历史 |
| `LOW_RISK_WRITE` | 低风险写入，可直接执行 | 创建知识库文档 |
| `REQUIRE_CONFIRMATION` | 必须用户确认 | 删除知识文档、后续删除文件 |
| `FORBIDDEN` | 禁止模型调用 | 删除用户、执行 SQL、执行系统命令 |

### 测试

本阶段完成后必须通过：

```bash
mvn -q -pl chatbot-service -DskipTests package
```

新增/补充单元测试：

- 工具注册表能查到所有已有工具。
- 每个工具都有非空 `toolName`、`displayName`、`level`。
- `REQUIRE_CONFIRMATION` 工具不能被标记为直接执行。

通过后再进入阶段 2。

## 阶段 2：统一工具执行审计

### 改造内容

当前每个工具里都手动调用：

- `toolNotifier.toolStarted`
- `auditService.start`
- `auditService.success`
- `auditService.failure`
- `toolNotifier.toolCompleted`

这容易漏记，也会重复样板代码。

计划引入统一执行包装器：

- `AgentToolExecutor`
  - 负责 started 事件。
  - 负责审计开始。
  - 捕获异常并记录失败。
  - 记录耗时。
  - 发送 completed/error 事件。

保留每个工具的业务逻辑，但把审计和事件发送收敛到一个入口。

审计字段增强：

- `duration_ms`
- `request_id` 或 `trace_id`
- `argument_summary`
- `result_summary`

如果需要新增字段，使用 Flyway migration，例如：

```text
V5__enhance_agent_tool_audit.sql
```

### 测试

必须通过：

```bash
mvn -q -pl chatbot-service -DskipTests package
```

建议新增集成/单元测试：

- 工具成功执行后写入 `agent_tool_execution_log`。
- 工具失败后记录 `FAILED` 和错误摘要。
- 工具执行结果不会把 token、密码、API key 写入审计表。

通过后再进入阶段 3。

## 阶段 3：权限边界与确认链路加固

### 改造内容

重点保证 Agent “能动手，但不能越权”。

计划加固：

- 所有工具必须从 `ToolContext` 获取 `userId` 和 `sessionId`。
- 工具禁止信任模型传入的 `userId`。
- 读取历史会话时必须校验 session 属于当前用户。
- 读取知识库文档时必须校验文档属于当前用户。
- 删除类操作只允许创建 pending action。
- pending action 确认时再次校验：
  - action 属于当前用户。
  - action 未过期。
  - action 状态是 `PENDING`。
  - 目标资源仍属于当前用户。

前端优化：

- pending action 卡片展示：
  - 工具名
  - 操作类型
  - 目标资源摘要
  - 过期时间
  - 确认按钮

### 测试

必须通过：

```bash
mvn -q -pl chatbot-service -DskipTests package
```

新增测试：

- 用户 A 不能读取用户 B 的知识文档。
- 用户 A 不能确认用户 B 的 pending action。
- 过期 action 不能确认。
- 删除知识库文档必须先 pending，再 confirm。

通过后再进入阶段 4。

## 阶段 4：Agent 端到端验证脚本

### 改造内容

在现有生产验证脚本基础上增加 Agent 专项验证脚本：

```text
scripts/prod-agent-e2e-verify.sh
```

验证流程：

1. 创建临时测试用户。
2. 登录获取 token。
3. 调用 `/api/chat/agent/stream`，请求 Agent 创建一份小型知识库文档。
4. 校验流式响应包含工具事件：
   - `tool_call_started`
   - `tool_call_result`
   - `knowledge_document_created`
5. 查询 `/api/knowledge/documents`，确认文档已创建。
6. 调用 Agent 请求删除该文档。
7. 校验产生 pending action，而不是直接删除。
8. 调用确认接口。
9. 校验文档被删除。
10. 清理临时用户、refresh token 和残留测试数据。

### 测试

本阶段必须通过：

```bash
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-agent-e2e-verify.sh
```

通过后再进入阶段 5。

## 阶段 5：部署与回归验证

### 部署方式

考虑远程服务器只有 4G 内存，继续使用低内存部署方式：

1. 本地构建 jar：

```bash
mvn -q -pl chatbot-service -DskipTests package
```

2. 上传 jar 到服务器。
3. `docker cp` 替换容器内 `/app/app.jar`。
4. 重启 `chatbot-service`。
5. 同步源码和脚本到 `/opt/springai-chatbot`。

如修改 Gateway，再单独构建和重启 Gateway。

### 回归验证

部署后必须通过：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-agent-e2e-verify.sh
```

同时检查：

```bash
docker compose -f docker-compose.prod.yml ps
docker logs chatbot-service --since 5m --tail 200
docker logs chatbot-gateway --since 5m --tail 200
```

## 风险评估

### 服务器资源

本阶段主要是 Java 代码和数据库字段增强，不新增常驻中间件，对 4G 服务器可接受。

预计影响：

- 内存：基本不增加。
- CPU：工具审计会增加少量数据库写入。
- MySQL：新增审计字段和查询索引，影响可控。
- Redis：只用于 refresh token 和已有缓存，不新增高负载任务。

### 主要风险

- 工具包装器改造可能影响所有工具调用。
- Flyway migration 需要保证幂等和字段兼容。
- Agent 流式验证依赖模型输出，脚本要设计成检查工具事件，不依赖完整自然语言内容。

### 降低风险方式

- 分阶段执行。
- 每阶段只改小范围。
- 每阶段测试通过才进入下一阶段。
- 写操作仍保留 pending confirmation。
- 部署后必须跑生产验证脚本。

## 审批点

请确认是否按这个阶段计划执行。

如果批准，建议从阶段 1 开始：

```text
阶段 1：工具元数据与风险等级统一
```

阶段 1 完成并测试通过后，再进入阶段 2。
