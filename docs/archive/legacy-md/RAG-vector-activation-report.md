# RAG 向量检索启用报告

执行日期：2026-06-07

## 1. 本次目标

把项目从“关键词 RAG 为主、向量能力预留”的状态，推进到可以实际使用的 Hybrid RAG：

- 继续保留关键词 RAG，作为稳定 fallback。
- 使用 PGVector 保存知识库文档向量。
- 使用外部 embedding API，不在 4G 云服务器本地跑 embedding 模型。
- 生产环境通过环境变量启用，密钥只放在远程 `.env`，不写入代码仓库。
- 启用后必须通过 embedding 连通性、健康检查、端到端验证、真实向量召回验证。

## 2. 最终结论

结论：已经可以使用标准 RAG 的核心链路。

当前生产环境状态：

- `APP_RAG_MODE=hybrid`
- `APP_RAG_VECTOR_ENABLED=true`
- PGVector 容器已启动并保持 healthy。
- embedding provider 使用 `openai-compatible`。
- embedding 模型使用 `text-embedding-v3`。
- 向量维度为 `1024`。
- embedding API Key 仅配置在服务器 `/opt/springai-chatbot/.env` 中，没有写入仓库。

4G 服务器可行性判断：

- 可行，但必须维持“外部 embedding API + 本机 PGVector”的模式。
- 不建议在这台 4G 服务器上再跑本地 embedding 模型。
- PGVector 只承担向量存储和相似度检索，资源压力比本地 embedding 推理低很多。
- 一旦 embedding API 或 PGVector 异常，代码会回退到关键词 RAG，聊天主链路不会直接中断。

## 3. 代码改动

### 3.1 OpenAI-compatible embedding 适配

文件：

- `chatbot-service/src/main/java/com/example/chatbot/rag/RagProperties.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/EmbeddingClient.java`
- `chatbot-service/src/main/resources/application.yml`

新增配置能力：

```yaml
app:
  rag:
    vector:
      dimensions: 1024
    embedding:
      provider: openai-compatible
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      embeddings-path: /v1/embeddings
      model: text-embedding-v3
      encoding-format: float
```

实现点：

- `EmbeddingClient` 支持自定义 `embeddings-path`，不再写死 `/v1/embeddings`。
- OpenAI-compatible 请求体会携带 `model`、`input`、`dimensions`、`encoding_format`。
- API Key 通过 `Authorization: Bearer <key>` 发送。
- 如果 embedding 返回格式不对，会抛出明确异常，并由 Hybrid RAG fallback 兜底。

### 3.2 知识库重建索引接口

文件：

- `chatbot-service/src/main/java/com/example/chatbot/service/RagService.java`
- `chatbot-service/src/main/java/com/example/chatbot/controller/KnowledgeController.java`

新增接口：

```http
POST /api/knowledge/reindex
```

作用：

- 对当前登录用户的已启用知识库文档重新提交向量索引。
- 返回是否启用向量索引，以及本次提交索引的文档数量。

这个接口用于后续从关键词 RAG 切到 Hybrid RAG 时，补建旧知识库文档的向量索引。

### 3.3 生产部署配置

文件：

- `docker-compose.prod.yml`

主要变化：

- PGVector 端口改为只绑定本机：`127.0.0.1:5432:5432`。
- `chatbot-service` 增加 RAG/embedding 相关环境变量。
- Gateway 仍只绑定 `127.0.0.1:9000:9000`，对外统一由 Nginx 代理。

生产 `.env` 当前使用的非敏感配置：

```env
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
APP_RAG_VECTOR_JDBC_URL=jdbc:postgresql://pgvector:5432/chatbot_vector
APP_RAG_VECTOR_USERNAME=postgres
APP_RAG_VECTOR_DIMENSIONS=1024
APP_RAG_EMBEDDING_PROVIDER=openai-compatible
APP_RAG_EMBEDDING_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
APP_RAG_EMBEDDING_PATH=/v1/embeddings
APP_RAG_EMBEDDING_MODEL=text-embedding-v3
APP_RAG_EMBEDDING_ENCODING_FORMAT=float
```

敏感项没有写入报告：

- `APP_RAG_EMBEDDING_API_KEY`
- `APP_RAG_VECTOR_PASSWORD`

## 4. 验证脚本

新增：

- `scripts/prod-embedding-smoke-test.sh`

作用：

- 从 `chatbot-service` 容器读取 embedding 配置。
- 调用 embedding API 做一条烟雾测试。
- 校验返回向量维度是否等于 `APP_RAG_VECTOR_DIMENSIONS`。
- 不打印 API Key。

已更新：

- `scripts/prod-rag-status-check.sh`

作用：

- 检查当前 RAG 模式。
- 检查向量配置是否完整。
- 检查 PGVector 容器状态。
- 检查聊天健康接口。

## 5. 本地验证结果

本地执行：

```bash
mvn -q -pl chatbot-service "-Dtest=DocumentChunkerTest,PgVectorClientTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest,AgentWorkspaceServiceTest" test
mvn -q -DskipTests package
```

结果：

- 指定单元测试通过。
- 全项目跳过测试打包通过。

## 6. 远程部署结果

远程执行要点：

```bash
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml --profile vector up -d pgvector
docker compose -f docker-compose.prod.yml up -d --no-deps chatbot-service
docker cp /tmp/rag-hybrid/chatbot-service.jar chatbot-service:/app/app.jar
docker restart chatbot-service
```

结果：

- PGVector 已启动。
- `chatbot-service` 已重启并读取新的 RAG 环境变量。
- Nginx 入口仍通过 `http://111.229.127.171` 访问。
- 业务服务端口没有重新暴露到公网。

## 7. 远程验证结果

### 7.1 embedding API 烟雾测试

执行：

```bash
bash scripts/prod-embedding-smoke-test.sh
```

结果：

```text
[embedding] provider=openai-compatible
[embedding] model=text-embedding-v3
[embedding] dimensions=1024
[pass] embedding API smoke test passed
```

### 7.2 RAG 状态检查

执行：

```bash
BASE_URL=http://127.0.0.1 bash scripts/prod-rag-status-check.sh
```

结果：

- RAG mode 为 `hybrid`。
- vector RAG 已启用。
- vector JDBC URL 已配置。
- embedding base URL 已配置。
- PGVector 容器正在运行。
- `/api/chat/health` 可用。

### 7.3 生产健康检查

执行：

```bash
BASE_URL=http://127.0.0.1 bash scripts/prod-health-check.sh
```

结果：通过。

覆盖项：

- `/login`
- `/chat`
- `/api/chat/health`
- 未登录接口鉴权返回预期状态

### 7.4 端到端验证

执行：

```bash
BASE_URL=http://127.0.0.1 bash scripts/prod-e2e-verify.sh
```

结果：通过。

覆盖项：

- 临时用户注册。
- 登录。
- 获取当前用户。
- 获取聊天统计。
- 获取知识库文档列表。

### 7.5 真实向量 RAG E2E

验证方式：

1. 创建临时用户。
2. 创建包含唯一短语“蓝色山谷番茄钟”的知识库文档。
3. 等待文档 `index_status=INDEXED`。
4. 查询 PGVector 向量表，确认该文档已有向量行。
5. 调用 `/api/knowledge/search` 搜索唯一短语。
6. 确认返回该知识库文档。
7. 清理临时用户、临时知识库文档和临时向量数据。

结果：

```text
[pass] vector RAG E2E passed
```

实际验证到的状态：

- 文档成功进入 MySQL。
- 文档成功切块并写入 PGVector。
- `index_status` 成功变为 `INDEXED`。
- `/api/knowledge/search` 可以召回该文档。

### 7.6 Workspace 回归验证

执行：

```bash
BASE_URL=http://127.0.0.1 bash scripts/prod-workspace-status-check.sh
```

结果：

```text
[workspace] workspace status check passed
```

覆盖项：

- 未登录访问 Workspace API 返回 401。
- 临时用户登录。
- 获取当前对话工作区。
- 创建和读取普通工作区文件。
- 创建和读取 Java 源码文件。
- 创建和读取项目 dotfile。
- 拦截 `target` 构建输出路径。

## 8. 当前用户如何使用

### 新知识库文档

现在通过页面、Agent 工具或接口新增知识库文档后，会进入向量索引流程：

1. 文档保存到 MySQL。
2. 文档被切块。
3. 调用 DashScope embedding API 生成 1024 维向量。
4. 向量写入 PGVector。
5. 查询时使用 Hybrid RAG：向量召回 + 关键词召回合并。

### 旧知识库文档

如果之前已有旧知识库文档，需要补建向量索引，可以调用：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

后续也可以在前端知识库管理页面加一个“重建向量索引”按钮。

## 9. 风险与回退

风险：

- embedding API 是外部依赖，存在网络失败、限流、费用和延迟问题。
- 大批量上传知识库时会产生 embedding 调用成本。
- 4G 服务器可以跑 PGVector，但不适合继续叠加本地 embedding 模型。

回退方式：

```env
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

然后重启 `chatbot-service` 即可退回关键词 RAG。

即使不回退，Hybrid RAG 内部也保留 fallback：向量检索失败时使用关键词结果，避免聊天主链路直接不可用。

## 10. 后续建议

优先级最高：

- 给知识库页面增加“重建向量索引”入口。
- 在文档列表中展示 `index_status`，让用户知道文档是否已完成向量索引。
- 给 embedding 调用增加耗时和失败次数日志，便于排查成本和稳定性。

下一阶段：

- 根据真实使用量决定是否增加 embedding 队列、限流和重试策略。
- 再进入 MCP 外部工具接入，补浏览器搜索、本地文件系统、代码工作区等能力。
