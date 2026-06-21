# AI Agent 升级阶段 3 执行报告

执行时间：2026-06-04

## 1. 阶段目标

阶段 3 目标是实现 Hybrid RAG 与向量检索，并保证 4G 云服务器可部署。

本阶段不强制启用 PGVector，不在 4G 云服务器上部署 embedding 模型。

## 2. 已完成改动

### 2.1 RAG 配置

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/rag/RagProperties.java
```

新增配置：

```yaml
app:
  rag:
    mode: keyword # keyword | vector | hybrid
    fallback-to-keyword: true
    vector:
      enabled: false
    embedding:
      provider: remote-ollama
      model: bge-m3
```

默认状态：

- `APP_RAG_MODE=keyword`
- `APP_RAG_VECTOR_ENABLED=false`

因此默认部署不会连接 PGVector，也不会调用 embedding 服务。

### 2.2 文档切块

新增：

```text
DocumentChunk
DocumentChunker
```

能力：

- 固定长度切块。
- 支持 overlap。
- 优先在换行、中文句号、问号、叹号等边界切分。
- 限制单文档最大 chunk 数，避免大文档压垮 embedding 服务。

### 2.3 Embedding 客户端

新增：

```text
EmbeddingClient
```

支持：

- `remote-ollama`
  - 调用 `/api/embeddings`
- `openai-compatible`
  - 调用 `/v1/embeddings`

说明：

- 只有向量检索或索引实际启用时才调用。
- 默认不调用外部 embedding。

### 2.4 PGVector 客户端

新增：

```text
PgVectorClient
```

能力：

- 默认关闭。
- 只在 `app.rag.vector.enabled=true` 且配置了 `jdbc-url`、embedding base-url 后启用。
- 可初始化 PGVector schema。
- 支持写入 chunk 向量。
- 支持按 `user_id` 检索相似 chunk。
- 支持按用户和文档删除向量。

说明：

- 没有使用 Spring AI 自动 PGVector starter，避免在没有 PGVector 数据源时误用 MySQL 或导致启动失败。
- 使用自定义 PGVector JDBC 客户端，开关更可控。

### 2.5 向量索引服务

新增：

```text
VectorIndexingService
VectorRagService
```

索引流程：

```text
KnowledgeEvent
  -> VectorIndexingService
  -> DocumentChunker
  -> EmbeddingClient
  -> PgVectorClient
```

失败策略：

- 索引失败只更新状态，不影响聊天。
- 向量检索失败时，RAG 回退关键词检索。

### 2.6 知识文档索引状态

新增迁移：

```text
V4__add_knowledge_index_status.sql
```

新增字段：

```text
index_status
index_error
indexed_time
```

状态示例：

- `VECTOR_DISABLED`
- `PENDING_INDEX`
- `INDEXING`
- `INDEXED`
- `INDEX_FAILED`
- `INDEX_SKIPPED`

### 2.7 Hybrid RAG 主链路

修改：

```text
RagService
```

模式：

- `keyword`：只走当前关键词 RAG。
- `vector`：优先向量，失败/空结果时可回退关键词。
- `hybrid`：关键词 + 向量合并去重重排。

现有调用方不需要修改：

```java
ragService.retrieveReferences(...)
```

仍然是唯一入口。

### 2.8 Kafka 知识事件接入索引

修改：

```text
KnowledgeEventConsumer
```

行为：

- `KNOWLEDGE_CREATED` / `KNOWLEDGE_UPDATED`：触发向量索引。
- `KNOWLEDGE_DELETED`：删除向量。
- 向量关闭时快速 no-op。

### 2.9 Compose 可选 PGVector

修改：

```text
docker-compose.yml
docker-compose.prod.yml
```

新增 profile：

```bash
docker compose --profile vector up -d pgvector
```

默认生产部署不启动 PGVector，避免 4G 云服务器增加内存压力。

## 3. 已执行测试

### 3.1 阶段 3 指定测试

```bash
mvn -q -pl chatbot-service "-Dtest=DocumentChunkerTest,PgVectorClientTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest,AgentToolSecurityTest,AgentPendingActionServiceTest,KnowledgeWriteToolsTest" test
```

结果：通过。

### 3.2 模块构建

```bash
mvn -q -pl chatbot-service -DskipTests package
```

结果：通过。

### 3.3 全工程构建

```bash
mvn -q -DskipTests package
```

结果：通过。

### 3.4 Compose 配置

```bash
docker compose config
docker compose -f docker-compose.prod.yml config
docker compose --profile vector config
docker compose -f docker-compose.prod.yml --profile vector config
```

结果：通过。

## 4. 部署策略

本次远程部署使用默认生产配置：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

原因：

- 远程服务器只有 4G。
- 不在远程服务器本地跑 embedding 模型。
- PGVector 作为 profile 保留，不默认启动。
- 先验证 Agent + 审计 + pending action + 原 RAG 不回归。

如果后续要验证向量 RAG，需要额外配置：

```text
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
APP_RAG_VECTOR_JDBC_URL=jdbc:postgresql://pgvector:5432/chatbot_vector
APP_RAG_VECTOR_USERNAME=postgres
APP_RAG_VECTOR_PASSWORD=...
APP_RAG_EMBEDDING_BASE_URL=https://你的embedding服务
APP_RAG_EMBEDDING_MODEL=bge-m3
APP_RAG_VECTOR_DIMENSIONS=1024
```

## 5. 已知风险

- `docker compose config` 会展开本地 `.env` 中的敏感值，阶段 0 已记录。
- `git diff --check` 仍显示阶段 1 前已存在的 `ChatbotService.java` trailing whitespace，本阶段未修改该行。
- 前端尚未实现 pending action 确认按钮，需要手动调用确认接口或后续补 UI。

## 6. 阶段 3 结论

阶段 3 通过。

当前系统具备：

- Agent 工具调用。
- 工具审计。
- 可控写操作。
- pending action 二次确认。
- 关键词 RAG。
- 可选向量 RAG。
- Hybrid RAG 回退机制。
- 可选 PGVector profile。

下一步：部署远程服务器，先按默认配置验证功能。

## 7. 前端统一补充

补充时间：2026-06-04

本次已同步修改 `chatbot-service/src/main/resources/templates/chat.html`：

- 模型选择器新增 `AI Agent` 选项。
- 文本消息选择 `AI Agent` 时调用 `/api/chat/agent/stream`。
- 图片消息继续调用 `/api/chat/stream/filekey`，避免 Agent 文本工具链误处理多模态请求。
- 前端 SSE 解析支持 `event:` 命名事件，可展示 `agent_status`、`tool_call_started`、`tool_call_result`、`tool_call_error`。
- Agent 工具结果支持渲染 pending action 确认按钮，并调用 `/api/chat/agent/actions/{actionId}/confirm`。
- 知识库列表展示后端新增的 `indexStatus` / `indexError`，用于观察向量索引状态。

同步修改后，阶段 3 报告中“前端尚未实现 pending action 确认按钮”的风险已关闭。

补充测试：

```bash
node -e "const fs=require('fs'); const html=fs.readFileSync('chatbot-service/src/main/resources/templates/chat.html','utf8'); const m=html.match(/<script>[\s\S]*?<\/script>/); new Function(m[0].replace(/^<script>/,'').replace(/<\/script>$/,'')); console.log('chat.html script syntax OK');"
mvn -q -pl chatbot-service "-Dtest=DocumentChunkerTest,PgVectorClientTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest,AgentToolSecurityTest,AgentPendingActionServiceTest,KnowledgeWriteToolsTest" test
mvn -q -pl chatbot-service -DskipTests package
mvn -q -DskipTests package
```

结果：通过。

## 8. 远程部署补充

部署时间：2026-06-04 18:49 Asia/Shanghai

由于 4G 云服务器上直接执行 Docker 多阶段 Maven build 超过 5 分钟未完成，本次采用低内存部署方式：

1. 本地完成 `chatbot-service` 和 `chatbot-gateway` jar 构建。
2. 上传 jar 到远程 `/tmp`。
3. 替换运行中容器的 `/app/app.jar`。
4. 重启 `chatbot-service` 和 `chatbot-gateway`。

远程验证结果：

- `chatbot-service` 启动成功。
- Flyway 已将数据库从 v2 迁移到 v4，成功应用：
  - `V3__add_agent_tool_audit_tables.sql`
  - `V4__add_knowledge_index_status.sql`
- `chatbot-gateway` 启动成功。
- `GET http://127.0.0.1:9000/chat` 返回 `200 text/html;charset=UTF-8`，页面包含 `AI Agent` 选项。
- `POST http://127.0.0.1:9000/api/chat/agent/stream` 未登录返回 `401 application/json`，说明 Gateway 路由已接通并进入鉴权链路。

注意：本次为了适配 4G 服务器采用 jar 替换方式完成验证部署；远程源码也已更新到 `/opt/springai-chatbot`，但容器镜像不是通过完整 `docker compose up -d --build` 重建生成。后续若服务器资源允许，可再做一次完整镜像重建。
