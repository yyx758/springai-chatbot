# Elasticsearch RAG 试运行说明

生成日期：2026-06-13

## 目标

本次把 Elasticsearch 引入为可选关键词召回层，用来增强 Hybrid RAG 的关键词召回能力。

最终链路变为：

```text
用户问题
  -> HybridSearchService
  -> 向量召回：PGVector
  -> 关键词召回：Elasticsearch 优先
       -> ES 不可用或无结果：MySQL FULLTEXT
       -> FULLTEXT 不可用或无结果：最近 200 条有限扫描
  -> HybridRanker 融合排序
  -> prompt 注入知识片段
```

## 部署策略

你的服务器是 4GB 单机，Elasticsearch 会额外占用常驻内存，所以本次先按“可回滚、可降级”的方式接入：

- 应用配置层仍保留 `APP_RAG_ELASTICSEARCH_ENABLED` 开关。
- ES 查询失败时自动回退到 MySQL FULLTEXT，不影响聊天主链路。
- 生产 compose 已按你的要求默认启动 Elasticsearch，并把 `APP_RAG_ELASTICSEARCH_ENABLED` 默认值改为 `true`。
- ES heap 在 4GB 服务器上从 512m 下调到 384m，降低常驻内存压力。

## 代码改动

### 1. 新增配置

文件：`chatbot-service/src/main/java/com/example/chatbot/rag/RagProperties.java`

新增：

```java
private Elasticsearch elasticsearch = new Elasticsearch();
```

配置项：

```java
private boolean enabled = false;
private String baseUrl = "http://localhost:9200";
private String indexName = "ai_studio_knowledge";
private String username = "";
private String password = "";
private String apiKey = "";
private int topK = 20;
private boolean initializeIndex = true;
```

文件：`chatbot-service/src/main/resources/application.yml`

```yaml
app:
  rag:
    elasticsearch:
      enabled: ${APP_RAG_ELASTICSEARCH_ENABLED:false}
      base-url: ${APP_RAG_ELASTICSEARCH_BASE_URL:http://localhost:9200}
      index-name: ${APP_RAG_ELASTICSEARCH_INDEX:ai_studio_knowledge}
      username: ${APP_RAG_ELASTICSEARCH_USERNAME:}
      password: ${APP_RAG_ELASTICSEARCH_PASSWORD:}
      api-key: ${APP_RAG_ELASTICSEARCH_API_KEY:}
      top-k: ${APP_RAG_ELASTICSEARCH_TOP_K:50}
      initialize-index: ${APP_RAG_ELASTICSEARCH_INITIALIZE_INDEX:true}
```

### 2. 新增 ES 服务

文件：`chatbot-service/src/main/java/com/example/chatbot/rag/ElasticsearchRagService.java`

职责：

- `initializeIndexIfNeeded()`：创建 ES index 和 mapping。
- `indexDocument(userId, documentId)`：把知识库文档按 `DocumentChunker` 切块后写入 ES。
- `deleteDocument(userId, documentId)`：按 `user_id + document_id` 删除 ES 中对应 chunk。
- `searchKeywordCandidates(userId, query, limit)`：用 ES `multi_match` 检索 `title/tags/content`，转换成 `HybridCandidate`。

ES 查询字段权重：

```json
{
  "fields": ["title^3", "tags^2", "content"]
}
```

这样标题命中权重最高，标签其次，正文最后。

### 3. 接入 HybridSearchService

文件：`chatbot-service/src/main/java/com/example/chatbot/rag/HybridSearchService.java`

关键词召回顺序：

```java
List<HybridCandidate> elasticsearchCandidates = elasticsearchRagService.searchKeywordCandidates(...);
if (!elasticsearchCandidates.isEmpty()) {
    return elasticsearchCandidates;
}

List<KnowledgeDocument> documents = loadKeywordCandidates(userId, query);
```

也就是：

1. ES 有结果：直接用 ES 作为关键词候选。
2. ES 没开、失败、无结果：走 MySQL FULLTEXT。
3. FULLTEXT 失败：走最近 200 条有限扫描。

### 4. 接入知识库事件

文件：`chatbot-service/src/main/java/com/example/chatbot/kafka/KnowledgeEventConsumer.java`

创建/更新：

```java
vectorIndexingService.indexDocument(event.getUserId(), event.getDocumentId());
elasticsearchRagService.indexDocument(event.getUserId(), event.getDocumentId());
```

删除：

```java
vectorIndexingService.deleteDocument(event.getUserId(), event.getDocumentId());
elasticsearchRagService.deleteDocument(event.getUserId(), event.getDocumentId());
```

### 5. 重建索引接口同步支持 ES

文件：`chatbot-service/src/main/java/com/example/chatbot/service/RagService.java`

`/api/knowledge/reindex` 现在会同时触发：

- PGVector 重建。
- Elasticsearch 重建。

返回中增加：

```java
"elasticsearchEnabled", elasticsearchRagService.isEnabled()
```

## Docker 试运行

### 本地启动 ES

```bash
docker compose --profile elasticsearch up -d elasticsearch
```

然后在 `.env` 或启动环境中开启：

```bash
APP_RAG_ELASTICSEARCH_ENABLED=true
APP_RAG_ELASTICSEARCH_BASE_URL=http://elasticsearch:9200
APP_RAG_ELASTICSEARCH_INDEX=ai_studio_knowledge
```

重启 `chatbot-service`。

### 生产启动

生产 compose 默认启动 Elasticsearch，不需要 profile：

```bash
docker compose -f docker-compose.prod.yml up -d elasticsearch
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
```

注意：4GB 服务器上 ES heap 当前是 384m。建议不要同时打开过多高内存能力，例如大模型本地推理、PGVector 大批量索引、ES 批量重建。

线上 Docker 镜像使用：

```yaml
elasticsearch:
  image: elasticsearch:8.15.3
  environment:
    discovery.type: single-node
    xpack.security.enabled: "false"
    ES_JAVA_OPTS: "-Xms384m -Xmx384m"
```

`chatbot-service` 生产默认开启：

```yaml
APP_RAG_ELASTICSEARCH_ENABLED: ${APP_RAG_ELASTICSEARCH_ENABLED:-true}
APP_RAG_ELASTICSEARCH_BASE_URL: ${APP_RAG_ELASTICSEARCH_BASE_URL:-http://elasticsearch:9200}
APP_RAG_ELASTICSEARCH_INDEX: ${APP_RAG_ELASTICSEARCH_INDEX:-ai_studio_knowledge}
```

## 重建 ES 索引

登录后调用：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

返回示例：

```json
{
  "success": true,
  "vectorEnabled": true,
  "elasticsearchEnabled": true,
  "requested": 12
}
```

## 验证命令

本次已执行：

```bash
mvn -q -pl chatbot-service "-Dtest=HybridSearchServiceCandidateTest,KnowledgeEventVectorIndexTest,HybridRagServiceTest,ElasticsearchRagServiceTest" test
mvn -q -pl chatbot-service -DskipTests package
docker compose --profile elasticsearch config --quiet
docker compose -f docker-compose.prod.yml config --quiet
```

结果：全部通过。

Docker 命令在本机输出了 `C:\Users\29146\.docker\config.json` 权限 warning，但退出码为 0，compose 配置解析通过。

线上 2026-06-14 已执行：

```bash
docker pull elasticsearch:8.15.3
docker compose -f docker-compose.prod.yml up -d elasticsearch
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
curl -sS http://127.0.0.1:9000/api/chat/health
curl -sS http://127.0.0.1:9200/_cluster/health
docker stats --no-stream chatbot-service chatbot-elasticsearch
```

线上验证结果：

- Docker 镜像加速已配置为腾讯云镜像 + 原有镜像源。
- `chatbot-elasticsearch` 状态为 `healthy`，ES cluster health 为 `green`。
- `chatbot-service` 已重建并启动，环境变量 `APP_RAG_ELASTICSEARCH_ENABLED=true`。
- Gateway 健康接口返回 `{"status":"UP"}`。
- Flyway 已应用 `V8__add_knowledge_fulltext_index.sql`，schema 当前版本为 v8。
- 观测时 ES 占用约 741MiB，chatbot-service 占用约 275MiB，整机 available 约 874MiB，swap 已使用约 812MiB。

## 当前限制

- 目前 ES 使用 `standard` analyzer。中文召回比 MySQL FULLTEXT 更可控，但还不是最佳中文分词。后续可以评估 IK analyzer 或内置 ngram 分析器。
- 当前 ES 索引写入是逐 chunk PUT，适合试运行；大规模重建时可以改为 Bulk API。
- 当前 ES 只作为关键词召回，不做向量检索。真正的混合检索仍是 PGVector 语义召回 + ES 关键词召回 + HybridRanker 融合。
- ES score 会转换成 `HybridCandidate.keywordScore`，并提取 query token 作为 `matchedTerms`，确保能被现有 `HybridRanker` 识别为有效关键词信号。

## 回滚方式

关闭配置即可：

```bash
APP_RAG_ELASTICSEARCH_ENABLED=false
```

关闭后链路自动回到：

```text
PGVector + MySQL FULLTEXT + 最近 200 条 fallback
```

不需要数据库迁移回滚。
