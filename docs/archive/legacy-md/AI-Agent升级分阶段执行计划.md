# AI Agent 升级分阶段执行计划

> 目标：将 AI Studio 从“智能客服聊天机器人”升级为“具备工具调用、可审计执行、混合 RAG、基础 MCP 扩展能力的 AI Agent 客服平台”。

## 1. 核心约束

### 1.1 远程服务器资源约束

当前远程云服务器只有 4G 内存，不能按“全本地大模型 + embedding + 向量库 + Kafka + Nacos + MySQL + Redis + 三个 Java 服务”的方式硬堆。

因此本计划采用轻量可部署路线：

- Agent Runtime 部署在现有 `chatbot-service` 内，不新增独立 `agent-service`。
- 本地/远程 embedding 模型不部署在 4G 云服务器上。
- embedding 通过外部 API、远程 Ollama，或本机安全暴露的 embedding 服务调用。
- PGVector 可以先接入开发/测试环境，生产环境默认通过配置开关控制，不强制启动。
- RAG 保留关键词检索兜底，向量检索失败时不影响普通聊天。
- MCP 先做基础能力和白名单，不开放 shell、filesystem、任意 SQL、任意 HTTP 写操作。

### 1.2 执行原则

每个阶段必须满足以下规则：

1. 先开发当前阶段。
2. 跑完当前阶段测试。
3. 测试通过后，记录通过结果。
4. 再进入下一阶段。

任何阶段测试失败时，不继续进入下一阶段，优先修复当前阶段问题。

## 2. 总体架构目标

```text
Browser
  -> Gateway :9000
  -> chatbot-service
       -> ChatbotService              # 保留原聊天链路
       -> AgentService                # 新增 Agent 运行入口
       -> AgentToolRegistry           # 工具注册与权限控制
       -> AgentToolExecutionLog       # 工具调用审计
       -> HybridRagService            # 关键词 + 向量混合检索
       -> VectorIndexingService       # 异步索引
       -> MCP Server/Client           # 基础 MCP 能力
  -> file-service
       -> 文件上传、下载、知识文档解析

External / Optional
  -> Remote Embedding API
  -> PGVector
  -> Remote Ollama
```

## 3. 阶段 0：基线检查与资源评估

### 3.1 目标

在正式改代码前确认项目能构建、compose 配置可解析、当前工作区改动不会被误覆盖，并建立资源边界。

### 3.2 改动范围

本阶段原则上不改业务代码，只做检查和必要配置规划。

需要确认：

- 当前未提交改动清单。
- Maven 父工程和三个子模块是否能构建。
- `docker-compose.yml` 和 `docker-compose.prod.yml` 是否配置有效。
- 生产环境 Java 服务、Kafka、Nacos、MySQL、Redis 的内存预算。
- 是否存在硬编码 `8080`、`8081` 访问。

### 3.3 测试命令

```bash
git status --short
mvn -q -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
rg "8080|8081" chatbot-service/src/main/resources gateway/src/main/resources file-service/src/main/resources
```

### 3.4 通过标准

- Maven 构建成功。
- 两个 compose 配置均能正常解析。
- 没有新增业务服务宿主机端口暴露。
- 前端和模板不硬编码访问业务服务 `:8080` 或 `:8081`。
- 明确哪些未提交改动属于用户已有改动，后续不覆盖。

### 3.5 阶段完成后才能进入

阶段 1：Agent Runtime 最小可用版本。

## 4. 阶段 1：Agent Runtime 最小可用版

### 4.1 目标

新增 Agent 聊天入口，让系统从单次 LLM 回复升级为可工具调用的 Agent。

本阶段只启用只读工具，不做写操作。

### 4.2 改动范围

新增或调整：

- `chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java`
- `chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolRegistry.java`
- `chatbot-service/src/main/java/com/example/chatbot/agent/tool/KnowledgeReadTools.java`
- `chatbot-service/src/main/java/com/example/chatbot/agent/tool/FileReadTools.java`
- `chatbot-service/src/main/java/com/example/chatbot/agent/tool/ChatHistoryTools.java`
- `ChatbotController` 增加 `/api/chat/agent/stream` 或新增 `AgentController`
- `chat.html` 增加 Agent 模式入口
- `application.yml` 增加：

```yaml
app:
  agent:
    enabled: true
    max-tool-iterations: 6
    tool-timeout-ms: 10000
```

### 4.3 第一批工具

只读工具：

- `searchKnowledge`：检索当前用户知识库。
- `getChatHistory`：查询当前用户指定会话历史。
- `getFileInfo`：查询当前用户文件元数据。
- `listUserFiles`：分页查询当前用户文件。
- `getCurrentTime`：获取当前时间。

所有工具必须满足：

- 不允许模型传入任意 `userId` 越权。
- `userId` 从 JWT/Gateway 解析结果注入。
- 工具失败时返回可控错误，不抛出敏感堆栈给前端。

### 4.4 测试命令

```bash
mvn -q -pl chatbot-service -DskipTests package
mvn -q -DskipTests package
```

如果新增单元测试：

```bash
mvn -q -pl chatbot-service -Dtest=AgentServiceTest test
mvn -q -pl chatbot-service -Dtest=AgentToolSecurityTest test
```

### 4.5 手工测试

通过 Gateway 登录后，在前端测试：

1. 普通聊天仍可用。
2. Agent 模式能回答普通问题。
3. Agent 能根据问题调用知识库检索工具。
4. Agent 能查询当前用户文件信息。
5. 未登录请求 `/api/chat/agent/stream` 返回 401。
6. 使用其他用户 sessionId 查询历史时被拒绝。

### 4.6 通过标准

- 原 `/api/chat/stream` 不回归。
- 新 Agent SSE 能返回最终回答。
- 工具调用失败不会中断整个服务。
- 工具不能越权访问其他用户数据。
- 4G 服务器部署时不新增重型服务。

### 4.7 阶段完成后才能进入

阶段 2：工具调用审计与二次确认。

## 5. 阶段 2：工具调用审计与可控写操作

### 5.1 目标

让 Agent 具备有限“动手能力”，但所有写操作必须可审计，敏感操作必须二次确认。

### 5.2 改动范围

新增数据库表：

```sql
agent_tool_execution_log
agent_pending_action
```

建议字段：

```text
agent_tool_execution_log:
  id
  user_id
  session_id
  tool_name
  tool_level
  arguments_json
  result_summary
  status
  started_time
  finished_time

agent_pending_action:
  id
  user_id
  session_id
  action_type
  tool_name
  arguments_json
  status
  expire_time
  created_time
```

新增权限分级：

```text
L0 READ_ONLY              # 只读，直接执行
L1 LOW_RISK_WRITE         # 低风险写，可直接执行并记录日志
L2 REQUIRE_CONFIRMATION   # 中风险操作，必须二次确认
L3 FORBIDDEN              # 禁止开放给 Agent
```

### 5.3 第二批工具

低风险写工具：

- `createKnowledgeDocumentDraft`
- `createKnowledgeDocument`

需要二次确认的工具：

- `deleteKnowledgeDocument`
- `deleteFile`
- `sendEmail`
- `sendNotification`

明确禁止：

- 任意 SQL 执行。
- 任意 shell 命令。
- 任意服务器文件读写。
- 任意外部 HTTP POST。
- 删除数据库、清空缓存、Docker 操作。

### 5.4 测试命令

```bash
mvn -q -pl chatbot-service -Dtest=AgentToolExecutionLogTest test
mvn -q -pl chatbot-service -Dtest=AgentPendingActionTest test
mvn -q -pl chatbot-service -Dtest=AgentToolSecurityTest test
mvn -q -DskipTests package
```

### 5.5 手工测试

1. Agent 创建知识文档成功，并写入工具执行日志。
2. Agent 请求删除知识文档时，只生成 pending action，不直接删除。
3. 用户确认 pending action 后，才真正执行删除。
4. pending action 过期后不能执行。
5. 普通用户不能调用 ADMIN_ONLY 工具。
6. 工具参数里伪造 `userId` 无效。

### 5.6 通过标准

- 所有工具调用都有审计记录。
- L2 工具不会绕过确认直接执行。
- 越权操作被拒绝。
- 原认证、聊天、知识库、文件管理功能不回归。

### 5.7 阶段完成后才能进入

阶段 3：Hybrid RAG 与向量索引。

## 6. 阶段 3：Hybrid RAG 与向量检索

### 6.1 目标

将当前关键词 RAG 升级为“关键词 + 向量”的混合 RAG。

在 4G 云服务器上，向量能力必须可关闭、可降级。

### 6.2 推荐部署策略

开发环境：

- 可以本机 Docker 跑 PGVector。
- embedding 可以调用本机 Ollama 或云端 API。

生产环境：

- 不建议在 4G 云服务器本地跑 embedding 模型。
- PGVector 默认不强制启动，可通过 compose profile 或外部数据库连接。
- 向量检索失败时自动回退关键词 RAG。

### 6.3 改动范围

新增配置：

```yaml
app:
  rag:
    mode: hybrid # keyword | vector | hybrid
    fallback-to-keyword: true
    vector:
      enabled: false
      top-k: 5
      similarity-threshold: 0.55
    embedding:
      provider: remote-ollama # remote-ollama | openai-compatible
      model: bge-m3
      base-url: ${APP_EMBEDDING_BASE_URL:}
      api-key: ${APP_EMBEDDING_API_KEY:}
      concurrency: 1
      timeout-ms: 20000
```

新增服务：

- `DocumentChunker`
- `VectorIndexingService`
- `HybridRagService`
- `EmbeddingClient`
- `KnowledgeIndexStatusService`

新增或调整表字段：

```text
knowledge_document:
  index_status: PENDING_INDEX | INDEXING | INDEXED | INDEX_FAILED
  index_error: nullable
  indexed_time: nullable

knowledge_document_chunk:
  id
  document_id
  user_id
  chunk_index
  content
  token_count / char_count
  created_time
```

如果使用 Spring AI PGVector，也可以把 chunk metadata 写入 vector store：

```text
metadata:
  userId
  documentId
  title
  fileKey
  chunkIndex
  tags
```

### 6.4 异步索引流程

```text
用户创建知识文档
  -> MySQL 保存文档
  -> index_status = PENDING_INDEX
  -> Kafka 发送 rag.index.requested
  -> 消费者切 chunk
  -> 限流调用 embedding API
  -> 写入 PGVector
  -> index_status = INDEXED
```

失败时：

```text
embedding/PGVector 失败
  -> 重试 3 次
  -> 仍失败：index_status = INDEX_FAILED
  -> 查询时 fallback keyword RAG
```

### 6.5 并发控制

远程调用本机 embedding 时必须限制：

```text
embedding 并发数：1
单 chunk 超时：10-20 秒
单文档最大 chunk 数：建议 200
索引任务队列：Kafka 串行或小并发消费
查询 embedding：单请求一次，失败走关键词 RAG
```

### 6.6 测试命令

关键词兜底测试：

```bash
mvn -q -pl chatbot-service -Dtest=RagServiceTest test
```

向量索引测试：

```bash
mvn -q -pl chatbot-service -Dtest=DocumentChunkerTest test
mvn -q -pl chatbot-service -Dtest=VectorIndexingServiceTest test
mvn -q -pl chatbot-service -Dtest=HybridRagServiceTest test
```

整体构建：

```bash
mvn -q -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
```

### 6.7 手工测试

1. `app.rag.mode=keyword` 时，系统表现和旧版一致。
2. `app.rag.mode=hybrid` 且向量服务正常时，能返回向量召回结果。
3. embedding 服务断开时，聊天仍可用，并回退关键词 RAG。
4. PGVector 不可用时，聊天仍可用，并回退关键词 RAG。
5. 上传大文档时接口快速返回，索引异步执行。
6. 索引失败时前端可看到失败状态。

### 6.8 通过标准

- 关键词 RAG 不回归。
- 向量 RAG 可用时生效。
- 向量 RAG 不可用时不影响聊天。
- 文档索引不阻塞用户上传接口。
- embedding 并发被限制，不压垮本机模型服务。

### 6.9 阶段完成后才能进入

阶段 4：基础 MCP。

## 7. 阶段 4：基础 MCP 能力

### 7.1 目标

让 AI Studio 具备基础 MCP 扩展能力：

- 作为 MCP Server，暴露项目内部安全工具。
- 作为 MCP Client，预留连接外部 MCP Server 的能力。

### 7.2 改动范围

新增：

- MCP Server 配置。
- MCP Client 配置。
- MCP 工具白名单。
- Gateway 路由和鉴权规则。

建议只开放：

- 知识库检索。
- 文件元数据查询。
- 聊天历史查询。
- 创建知识文档草稿。

暂不开放：

- 文件系统读写。
- shell。
- 任意数据库查询。
- 任意 HTTP 请求。
- 删除、发送邮件等高风险工具。

### 7.3 配置建议

```yaml
app:
  mcp:
    enabled: false
    server:
      enabled: false
    client:
      enabled: false
      allowed-tools:
        - knowledge.search
        - files.info
        - chat.history
```

生产默认 `false`，测试通过后再打开。

### 7.4 测试命令

```bash
mvn -q -pl chatbot-service -Dtest=McpToolSecurityTest test
mvn -q -pl chatbot-service -Dtest=McpServerSmokeTest test
mvn -q -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
```

### 7.5 手工测试

1. MCP 未开启时，相关 endpoint 不可用或不暴露。
2. MCP 开启后，只能调用白名单工具。
3. 未认证请求不能访问受保护 MCP 工具。
4. MCP 工具不能越权访问其他用户数据。
5. Agent 通过 MCP 调用知识库工具正常。

### 7.6 通过标准

- MCP 不影响普通聊天。
- MCP 工具受白名单限制。
- MCP 工具受用户鉴权限制。
- 没有开放危险操作。

### 7.7 阶段完成后才能进入

阶段 5：生产部署与观测。

## 8. 阶段 5：生产部署与观测

### 8.1 目标

在 4G 云服务器上稳定部署轻量 Agent 能力，并通过日志和指标观察运行状态。

### 8.2 部署策略

生产优先启用：

- Agent Runtime。
- 只读工具。
- 工具审计。
- 关键词 RAG。
- 可选 Hybrid RAG，但必须可降级。

生产谨慎启用：

- PGVector。
- MCP Server。
- 写操作工具。

生产不启用：

- 本地 embedding 模型。
- 本地 Ollama/llava。
- 任意 shell/filesystem MCP。

### 8.3 资源配置建议

保持或收紧 JVM：

```text
chatbot-service: -Xms256m -Xmx512m
file-service:    -Xms128m -Xmx256m
gateway:         -Xms128m -Xmx256m
```

如启用 PGVector：

- 建议先加 swap。
- PGVector 单独 profile 启动。
- Kafka/Nacos 内存参数需要一起收紧。
- 观察 24 小时后再决定是否常驻。

### 8.4 观测指标

至少记录日志：

- Agent 请求总耗时。
- 模型调用耗时。
- 工具调用次数和耗时。
- 工具失败率。
- RAG 检索模式：keyword/vector/hybrid。
- embedding 调用耗时和失败次数。
- PGVector 查询耗时。
- 索引任务成功/失败数量。

### 8.5 部署测试命令

远程服务器：

```bash
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml config
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
docker compose -f docker-compose.prod.yml up -d --build chatbot-gateway
docker compose -f docker-compose.prod.yml ps
docker stats --no-stream
```

如果修改 file-service：

```bash
docker compose -f docker-compose.prod.yml up -d --build file-service
docker compose -f docker-compose.prod.yml ps
docker stats --no-stream
```

### 8.6 手工验收

1. Gateway `:9000` 可访问。
2. 登录、注册、刷新 token 正常。
3. 普通聊天正常。
4. Agent 聊天正常。
5. 只读工具正常。
6. 写操作需要确认。
7. 文件上传、下载正常。
8. 知识库创建、检索正常。
9. embedding 服务断开时，聊天仍可用。
10. `docker stats` 中内存没有持续逼近 4G。

### 8.7 通过标准

- 服务连续运行稳定。
- 4G 内存没有持续耗尽。
- Agent 功能可用。
- RAG 降级有效。
- 无 8080/8081 宿主机暴露。
- 无敏感工具裸露。

## 9. 阶段执行顺序总表

| 阶段 | 名称 | 是否必须 | 通过后进入 |
|---|---|---:|---|
| 0 | 基线检查与资源评估 | 是 | 阶段 1 |
| 1 | Agent Runtime 最小可用版 | 是 | 阶段 2 |
| 2 | 工具调用审计与可控写操作 | 是 | 阶段 3 |
| 3 | Hybrid RAG 与向量检索 | 可灰度 | 阶段 4 |
| 4 | 基础 MCP 能力 | 可灰度 | 阶段 5 |
| 5 | 生产部署与观测 | 是 | 完成 |

## 10. 最终建议

推荐先审批并执行阶段 0 到阶段 2。

原因：

- Agent 能力和审计能力对项目价值提升最大。
- 对 4G 服务器资源压力最小。
- 可演示、可面试、可稳定部署。

阶段 3 的向量 RAG 建议作为灰度增强，不替换当前关键词 RAG。阶段 4 的 MCP 建议点到为止，只开放安全只读工具。

最终落地目标不是堆功能，而是形成一个可解释、可审计、可降级、能在 4G 服务器上跑起来的 AI Agent 系统。
