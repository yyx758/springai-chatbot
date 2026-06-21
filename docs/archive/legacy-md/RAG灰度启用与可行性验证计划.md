# RAG 灰度启用与可行性验证计划

## 1. 当前结论

按 `md/AI-Agent升级分阶段执行计划.md` 的主线，当前已经完成到：

- 阶段 0：基线检查与资源评估
- 阶段 1：Agent Runtime 最小可用版
- 阶段 2：工具调用审计与可控写操作
- 阶段 3：Hybrid RAG 与向量检索

阶段 3 的代码框架已经完成，但生产环境默认仍是轻量关键词 RAG：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

原因是远程服务器只有 4G 内存，当前已经运行：

- MySQL
- Redis
- Kafka
- Nacos
- chatbot-service
- file-service
- chatbot-gateway

因此，当前不建议在远程服务器上再长期运行本地 embedding 模型。PGVector 可以作为灰度 profile 启用，但必须先验证资源余量。

## 2. 当前 RAG 已具备的能力

已具备：

- 关键词 RAG 兜底。
- `keyword` / `vector` / `hybrid` 三种模式配置。
- 文档切块 `DocumentChunker`。
- embedding 客户端 `EmbeddingClient`。
  - `remote-ollama`
  - `openai-compatible`
- PGVector 客户端 `PgVectorClient`。
- 向量索引服务 `VectorIndexingService`。
- 向量查询服务 `VectorRagService`。
- 知识文档索引状态字段：
  - `VECTOR_DISABLED`
  - `PENDING_INDEX`
  - `INDEXING`
  - `INDEXED`
  - `INDEX_FAILED`
  - `INDEX_SKIPPED`
- 向量检索失败时回退关键词 RAG。
- Compose 中 PGVector 已放到 `vector` profile，不默认启动。

## 3. 灰度启用原则

本项目的 RAG 策略不是“立即替换为标准向量 RAG”，而是：

```text
关键词 RAG 稳定兜底
  -> 可选 PGVector
  -> 远程 embedding
  -> 小流量 hybrid
  -> 验证稳定后再扩大
```

必须满足：

- 任何时候关键词 RAG 都不能被移除。
- 向量失败不能影响普通聊天。
- embedding 调用必须限流。
- 4G 服务器不部署本地 embedding 大模型。
- PGVector 只在灰度时启用 profile。
- 所有生产启用前必须跑验证脚本。

## 4. 推荐部署方案

### 4.1 当前生产稳定模式

继续使用：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

优点：

- 内存压力最低。
- 不依赖 embedding API。
- 不依赖 PGVector。
- 知识库问答链路稳定。

适合当前线上默认运行。

### 4.2 灰度模式 A：只启动 PGVector，不启用向量检索

目的：

- 验证 4G 服务器是否能承受 PGVector 常驻。
- 不影响现有 RAG 查询。

配置：

```bash
docker compose -f docker-compose.prod.yml --profile vector up -d pgvector
```

保持：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
```

通过标准：

- `chatbot-service`、`chatbot-gateway` 不重启异常。
- `prod-health-check.sh` 通过。
- `prod-e2e-verify.sh` 通过。
- 内存没有明显打满。

### 4.3 灰度模式 B：启用向量索引，但仍以关键词为主

目的：

- 验证知识文档能切块、embedding、写入 PGVector。
- 仍不让用户查询完全依赖向量结果。

配置：

```text
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
APP_RAG_VECTOR_JDBC_URL=jdbc:postgresql://pgvector:5432/chatbot_vector
APP_RAG_VECTOR_USERNAME=postgres
APP_RAG_VECTOR_PASSWORD=<PGVector密码>
APP_RAG_EMBEDDING_PROVIDER=openai-compatible 或 remote-ollama
APP_RAG_EMBEDDING_BASE_URL=<远程embedding服务地址>
APP_RAG_EMBEDDING_MODEL=<embedding模型>
APP_RAG_VECTOR_DIMENSIONS=<模型向量维度>
```

注意：

- `APP_RAG_VECTOR_DIMENSIONS` 必须和 embedding 模型输出维度一致。
- 如果使用 `bge-m3`，通常是 1024 维。
- 如果使用 OpenAI `text-embedding-3-small`，通常是 1536 维。
- 维度不一致会导致 PGVector 写入失败。

通过标准：

- 创建知识文档后状态从 `PENDING_INDEX` 进入 `INDEXED` 或失败可见。
- 查询时 hybrid 可返回结果。
- embedding 服务断开时仍回退关键词。
- PGVector 停止时聊天仍可用。

### 4.4 不推荐模式：本机 embedding 模型跑在 4G 服务器

不建议：

```text
4G 服务器同时跑 Ollama embedding + PGVector + Kafka + Nacos + MySQL + Redis + Java 服务
```

风险：

- 内存不足。
- embedding 队列阻塞。
- Java 服务被系统 OOM kill。
- Kafka/Nacos 不稳定。

如果一定要本机 embedding，建议先停掉非必要组件或迁移 Kafka/Nacos，但这已经超出当前阶段。

## 5. 验证脚本

新增：

```text
scripts/prod-rag-status-check.sh
```

作用：

- 检查 `APP_RAG_MODE`。
- 检查 `APP_RAG_VECTOR_ENABLED`。
- 检查 vector JDBC URL 与 embedding base URL 是否满足启用条件。
- 检查 PGVector profile 是否运行。
- 检查 `/api/chat/health`。
- 不打印 embedding API key、PGVector 密码或 JWT secret。

当前生产默认预期输出：

```text
[rag] mode=keyword
[rag] vector_enabled=false
[pass] RAG mode is keyword
[pass] vector RAG is disabled
[pass] PGVector container is not created; vector profile is not active
[pass] chat health endpoint is available
[rag] RAG status check passed
```

## 6. 分阶段执行计划

### 阶段 3A：生产 RAG 状态确认

目标：

确认当前生产仍是安全的 keyword 模式，且向量没有误启用。

执行：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
```

通过标准：

- 三个脚本全部通过。
- `APP_RAG_MODE=keyword`。
- `APP_RAG_VECTOR_ENABLED=false`。
- PGVector profile 未运行或运行但未被 chatbot-service 使用。

通过后可进入阶段 3B。

### 阶段 3B：PGVector 空载灰度

目标：

只验证 PGVector 常驻资源，不启用向量查询。

执行：

```bash
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml --profile vector up -d pgvector
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
docker stats --no-stream
```

通过标准：

- 服务不重启。
- 登录、知识库接口不回归。
- PGVector 运行状态正常。
- 服务器内存仍有余量。

如果内存压力明显，停止 PGVector：

```bash
docker compose -f docker-compose.prod.yml --profile vector stop pgvector
```

通过后才考虑阶段 3C。

### 阶段 3C：远程 embedding 连通性验证

目标：

验证 embedding 服务可用，不写入生产知识库。

前提：

- 需要用户提供远程 embedding 服务地址。
- 需要明确模型维度。
- 不使用 4G 服务器本地 embedding 模型。

验证项：

- 单条文本 embedding 请求成功。
- 返回向量维度正确。
- 响应时间可接受。
- API 失败时不会影响聊天。

建议补充脚本：

```text
scripts/prod-embedding-smoke-test.sh
```

暂不执行，等 embedding 服务确定后再写。

### 阶段 3D：Hybrid RAG 小流量验证

目标：

让临时测试用户创建一份测试知识文档，触发向量索引，再查询 hybrid 结果。

执行前配置：

```text
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
APP_RAG_VECTOR_JDBC_URL=jdbc:postgresql://pgvector:5432/chatbot_vector
APP_RAG_VECTOR_USERNAME=postgres
APP_RAG_VECTOR_PASSWORD=<PGVector密码>
APP_RAG_EMBEDDING_BASE_URL=<远程embedding服务>
APP_RAG_EMBEDDING_MODEL=<模型名>
APP_RAG_VECTOR_DIMENSIONS=<维度>
```

验证流程：

1. 创建临时用户。
2. 登录。
3. 创建测试知识文档。
4. 等待 Kafka 消费索引事件。
5. 检查文档 `index_status`。
6. 调用 `/api/knowledge/search`。
7. 模拟 embedding 服务不可用，验证回退关键词。
8. 删除测试文档和临时用户。

通过标准：

- 文档最终 `INDEXED`，或失败时前端/接口可见失败状态。
- hybrid 查询可返回向量或关键词合并结果。
- embedding/PGVector 故障时，接口仍返回 200，结果来自关键词 RAG。

### 阶段 3E：是否进入阶段 4 MCP

只有满足以下条件，才建议进入 MCP：

- 当前 keyword RAG 生产稳定。
- RAG 状态检查脚本稳定通过。
- 若启用 PGVector，空载灰度没有内存问题。
- 若启用 hybrid，embedding 服务稳定且有明确成本预算。

否则建议先维持：

```text
Agent + 工具调用 + 关键词 RAG + 可选向量灰度
```

## 7. 当前建议

现阶段建议先执行阶段 3A：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
```

不建议立刻启用：

```text
APP_RAG_MODE=hybrid
APP_RAG_VECTOR_ENABLED=true
```

原因：

- 还没有确认远程 embedding 服务。
- 还没有确认 embedding 模型维度。
- 还没有做 PGVector 空载资源验证。
- 当前 4G 服务器资源有限。

## 8. 审批点

请确认是否执行：

```text
阶段 3A：生产 RAG 状态确认
```

阶段 3A 只执行验证脚本，不改变生产 RAG 配置。

阶段 3A 通过后，再决定是否进入：

```text
阶段 3B：PGVector 空载灰度
```
