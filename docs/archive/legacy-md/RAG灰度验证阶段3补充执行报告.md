# RAG 灰度验证阶段 3 补充执行报告

执行时间：2026-06-07

## 1. 本次目标

在不破坏当前生产功能的前提下，继续验证 AI Agent 升级计划中的阶段 3：RAG 灰度启用方案。

本次重点不是直接把生产切到向量 RAG，而是验证三件事：

1. 当前关键词 RAG 与生产主链路是否稳定。
2. 4G 云服务器是否能承受 PGVector 空载运行。
3. 是否已经具备启用 hybrid/vector RAG 的完整条件。

## 2. 当前结论

结论：暂不建议把生产 RAG 切到 hybrid/vector。

原因：

- PGVector 容器可以在 4G 服务器上启动并保持 healthy，空载内存约 52 MiB，这一项可行。
- 生产环境当前仍是 `APP_RAG_MODE=keyword`、`APP_RAG_VECTOR_ENABLED=false`，主链路稳定。
- 真正启用向量 RAG 还缺少 embedding 服务地址：`APP_RAG_EMBEDDING_BASE_URL` 为空。
- 代码中 `EmbeddingClient` 在 `base-url` 为空时会直接抛出 `embedding base-url is not configured`。如果此时强行启用向量索引或向量检索，会导致索引/检索链路失败，只能依赖 fallback 回退关键词，收益不明确。

因此，本轮只完成 RAG 灰度可行性验证，不切换生产检索模式。

## 3. 已完成动作

### 3.1 阶段 3A：关键词 RAG 基线验证

远程执行：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
```

结果：

- `/login` 返回 200。
- `/chat` 返回 200。
- `/api/chat/health` 返回 200。
- 未登录访问 `/api/auth/me` 返回 401。
- 临时用户注册/登录通过。
- `/api/auth/me`、`/api/chat/stats`、`/api/knowledge/documents` 均返回 200。
- RAG 状态为 `keyword`，向量 RAG 为 disabled。

### 3.2 阶段 3B：PGVector 空载灰度验证

远程执行：

```bash
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml --profile vector up -d pgvector
```

PGVector 启动结果：

```text
chatbot-pgvector   Up 2 minutes (healthy)
```

启动后资源占用：

```text
chatbot-pgvector   52.39MiB / 3.32GiB
chatbot-gateway    214.1MiB / 3.32GiB
chatbot-service    351MiB / 3.32GiB
file-service       165.5MiB / 3.32GiB
chatbot-kafka      243.5MiB / 3.32GiB
chatbot-redis      5.922MiB / 3.32GiB
chatbot-mysql      77.99MiB / 3.32GiB
chatbot-nacos      326.2MiB / 3.32GiB
```

判断：

- 单独启动 PGVector 对当前 4G 服务器的空载压力较小。
- 但这只代表向量数据库可运行，不代表完整向量 RAG 可启用。

### 3.3 阶段 3C：embedding 条件验证

远程容器中的非敏感配置：

```text
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false
APP_RAG_EMBEDDING_PROVIDER=remote-ollama
APP_RAG_EMBEDDING_MODEL=bge-m3
APP_RAG_VECTOR_DIMENSIONS=1024
APP_RAG_EMBEDDING_BASE_URL=
```

代码约束：

- `EmbeddingClient` 支持 `remote-ollama` 和 `openai-compatible` 两种形式。
- `remote-ollama` 会请求 `{baseUrl}/api/embeddings`。
- `openai-compatible` 会请求 `{baseUrl}/v1/embeddings`。
- `base-url` 为空时直接失败。

判断：

- 当前生产不具备启用向量 RAG 的完整条件。
- 若要继续阶段 3 的 hybrid RAG，需要先提供稳定的 embedding 服务。

## 4. 脚本修正

本次修正了：

```text
scripts/prod-rag-status-check.sh
```

修正原因：

- 灰度验证后，PGVector 容器可能处于 `exited` 状态。
- 当 `APP_RAG_VECTOR_ENABLED=false` 时，PGVector 未运行不应该被判定为生产故障。

修正后规则：

- 向量 RAG 关闭时：
  - PGVector 未创建：通过。
  - PGVector 已停止：通过。
  - PGVector 正在运行：通过并提示。
- 向量 RAG 开启时：
  - PGVector 未创建或未运行：失败。

远程验证结果：

```text
[pass] RAG mode is keyword
[pass] vector RAG is disabled
[pass] PGVector container exists but vector RAG is disabled (state=exited)
[pass] chat health endpoint is available
[rag] RAG status check passed
```

## 5. 停止 PGVector 后的最终验证

为了保持 4G 服务器轻量，本次验证结束后已停止 PGVector 灰度容器：

```bash
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml --profile vector stop pgvector
```

停止后再次执行：

```bash
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
```

最终结果：

- 健康检查通过。
- 端到端验证通过。
- RAG 状态检查通过。
- 当前生产仍保持关键词 RAG，不依赖 PGVector。

停止 PGVector 后资源占用：

```text
chatbot-gateway   214.4MiB / 3.32GiB
chatbot-service   354.1MiB / 3.32GiB
file-service      165.6MiB / 3.32GiB
chatbot-kafka     243.6MiB / 3.32GiB
chatbot-redis     5.926MiB / 3.32GiB
chatbot-mysql     79.5MiB / 3.32GiB
chatbot-nacos     326.4MiB / 3.32GiB
```

## 6. 后续决策建议

### 方案 A：继续完成标准 RAG

前提：

- 提供稳定 embedding 服务。
- 明确 provider：
  - `remote-ollama`：需要远程或本机可被服务器访问的 Ollama embedding 服务。
  - `openai-compatible`：需要兼容 `/v1/embeddings` 的云端 embedding API。
- 明确向量维度与 `APP_RAG_VECTOR_DIMENSIONS` 一致。
- 做一轮小规模知识库重建索引测试。

风险：

- 4G 服务器不适合同时跑大 embedding 模型。
- 如果 embedding 服务放在本机，远程服务器调用会受到家庭网络、公网可达性、延迟、并发和稳定性影响。
- 本机 embedding 服务需要额外做鉴权、限流、超时、重试和队列，否则并发写入知识库时容易拖垮。

建议：

- 如果要做标准 RAG，优先使用云端 embedding API 或单独的小模型服务，而不是让 4G 业务服务器同时承担 embedding 推理。

### 方案 B：暂缓标准 RAG，进入阶段 4 MCP

前提：

- 当前关键词 RAG 已能满足轻量知识库问答。
- Agent 已具备项目内工具调用能力，例如创建知识库文档、保存文件、展示工具执行状态。
- MCP 可以优先补“外部工具接入协议”和“工具边界治理”，不强依赖向量数据库。

建议：

- 当前更推荐进入阶段 4 MCP。
- 原因是标准 RAG 的阻塞点在 embedding 服务，而 MCP 可以继续提升 Agent 动手能力，且对 4G 服务器压力更可控。

## 7. 当前状态

生产状态：

- Gateway 正常。
- chatbot-service 正常。
- file-service 正常。
- MySQL/Redis/Kafka/Nacos 正常。
- RAG 模式保持 `keyword`。
- 向量 RAG 保持 disabled。
- PGVector 容器已停止，仅保留灰度验证痕迹，不参与生产流量。

下一步推荐：

1. 若用户能提供稳定 embedding API，则继续阶段 3D：小规模 hybrid RAG 索引与召回验证。
2. 若暂不提供 embedding API，则进入阶段 4：基础 MCP 接入与 Agent 工具能力规范化。
