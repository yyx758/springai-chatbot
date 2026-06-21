# Hybrid RAG 全链路说明

生成日期：2026-06-15

## 文档定位

本文是 AI Studio 当前 RAG 实现的主文档，以源码为准说明“用户问题如何检索知识库、如何排序、如何进入 prompt、索引如何写入和更新”。

相关辅助文档：

- `ES关键词召回说明.md`：专门解释 ES 关键词召回。
- `RAG索引优化说明.md`：说明 MySQL FULLTEXT 和 ES 索引优化依据。
- `RAG召回率优化执行报告.md`：记录召回率优化改动。
- `RAG召回实际查询测试报告.md`：记录实际查询样例、修改前后对比和微调思路。
- `Elasticsearch试运行记录.md`：记录 ES 部署、启动、验证和回滚方式。

## 总体链路

当前 RAG 入口在 `RagService.retrieveReferences(...)`。默认模式是 `hybrid`，也保留 `keyword` 和 `vector` 两种兼容模式。

```text
用户问题
  -> ChatbotService 构建聊天上下文
  -> RagService.retrieveReferences(userId, query, topK)
  -> mode=hybrid 时进入 HybridSearchService.search(...)
  -> QueryRewriteService 生成 retrievalQuery
  -> QueryEnhancer 追加技术同义词/领域词
  -> QueryIntentAnalyzer 记录问题类型
  -> 向量检索：VectorRagService + PGVector
  -> 关键词检索：ElasticsearchKeywordSearchService
  -> ES 无结果时 fallback：MySQL FULLTEXT -> 最近 200 条扫描 -> 旧 Java 规则
  -> RrfFusionService 按 rank 融合
  -> RagReference 列表
  -> RagService.buildKnowledgePrompt(...)
  -> 注入模型 prompt
```

核心代码：

- `chatbot-service/src/main/java/com/example/chatbot/service/RagService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/HybridSearchService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/ElasticsearchKeywordSearchService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/QueryEnhancer.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/RrfFusionService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/HybridRanker.java`（旧规则兼容和部分历史测试仍保留）
- `chatbot-service/src/main/java/com/example/chatbot/rag/ElasticsearchRagService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/VectorRagService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/VectorIndexingService.java`
- `chatbot-service/src/main/java/com/example/chatbot/rag/PgVectorClient.java`

## 模式选择

`RagService.retrieveReferences(...)` 根据 `app.rag.mode` 选择链路：

| 模式 | 行为 | 用途 |
| --- | --- | --- |
| `hybrid` | 进入 `HybridSearchService`，走 ES 关键词候选和向量候选，再用 RRF 融合。 | 当前推荐模式。 |
| `vector` | 只走 `VectorRagService`；失败或无结果时可按 `fallback-to-keyword` 回退关键词。 | 用于单独验证向量召回。 |
| `keyword` | 走 `RagService.retrieveKeywordReferences(...)` 的旧关键词逻辑。 | 兼容模式，外部依赖最少。 |

当前配置默认：

```yaml
app:
  rag:
    mode: ${APP_RAG_MODE:hybrid}
    fallback-to-keyword: true
```

## 查询改写

hybrid 模式第一步不是直接拿原始问题检索，而是调用 `QueryRewriteService.rewriteForRetrieval(...)` 得到 `retrievalQuery`。

示例：

| 原始问题 | 检索词 |
| --- | --- |
| 如何退款 | 退款 |
| 帮我查一下文件上传失败怎么办 | 文件上传失败 |
| JWT刷新令牌怎么轮转 | JWT刷新令牌轮转 |
| Kafka消息为什么会丢失 | Kafka消息会丢失 |
| Docker部署ES内存怎么配置 | Docker部署ES内存配置 |

这里删除 `请问`、`帮我`、`查一下`、`如何`、`怎么` 等词，不是因为它们会直接扣最终评分，而是为了减少候选召回阶段的噪声：

- ES / FULLTEXT 的 query 更聚焦核心实体。
- 向量 embedding 的语义中心更稳定。
- 关键词提取和日志更干净。

## 查询增强

`QueryEnhancer` 在 `retrievalQuery` 基础上追加少量技术同义词或领域词，不做文档评分。

词典文件：

```text
chatbot-service/src/main/resources/rag/query-synonyms.yml
```

示例：

```text
自动续期 -> 自动续期 看门狗 watchdog Redisson
向量数据库 -> 向量数据库 vector database pgvector
混合检索 -> 混合检索 hybrid search RRF rerank
```

配置：

```yaml
app:
  rag:
    query-enhancer:
      enabled: true
      max-expanded-terms: 6
      synonym-file: classpath:rag/query-synonyms.yml
```

注意：`QueryEnhancer` 只负责 query 增强，不替代 ES analyzer，也不对文档打分。

## 意图识别和权重

`QueryIntentAnalyzer` 根据增强后的检索词判断问题类型，用于日志和后续扩展。当前新 RRF 主链路按 rank 融合，不再把向量/关键词 raw score 混合。

| 类型 | 典型问题 | 向量权重 | 关键词权重 |
| --- | --- | ---: | ---: |
| `EXACT_KEYWORD` | 类名、方法名、SQL、JVM 参数 | 0.8 | 1.2 |
| `COMMAND_EXPLAIN` | docker、mvn、curl 等命令 | 0.8 | 1.2 |
| `ERROR_DEBUG` | 报错、失败、timeout、denied | 0.8 | 1.2 |
| `SEMANTIC_EXPLAIN` | 原理、机制、是什么 | 1.3 | 0.7 |
| `SOLUTION_DESIGN` | 方案、架构、优化、部署 | 1.3 | 0.7 |
| `COMPARISON` | 区别、对比、优劣 | 1.2 | 0.8 |
| `DEFAULT` | 默认问题 | 1.1 | 0.9 |

说明：历史 `HybridRanker` 仍保留意图权重和规则过滤能力，但当前 ES 主链路优先走 `RrfFusionService`，用两路 rank 做融合。

## 向量检索链路

向量检索入口：

```text
HybridSearchService.searchVector(...)
  -> VectorRagService.retrieve(userId, retrievalQuery, max(topK * 4, 20))
  -> EmbeddingClient.embed(retrievalQuery)
  -> PgVectorClient.search(...)
```

PGVector 查询使用余弦距离：

```sql
SELECT id, document_id, title, content, 1 - (embedding <=> ?::vector) AS score
FROM ai_studio_knowledge_vectors
WHERE user_id = ?
  AND (1 - (embedding <=> ?::vector)) >= ?
ORDER BY embedding <=> ?::vector
LIMIT ?
```

如果阈值过滤后没有结果，会再走 raw topK 兜底：

```text
threshold results empty
  -> searchRawTopK(userId, vector, min(3, finalTopK))
  -> 返回给 RrfFusionService 参与融合
```

注意：raw fallback 不是无条件注入 prompt。它只补候选，最终仍受强向量阈值、中等向量 + 关键词、fallback 阈值等规则限制。

## 关键词检索链路

关键词检索入口：

```text
HybridSearchService.searchKeyword(...)
  -> ElasticsearchKeywordSearchService.search(...)
  -> MySQL FULLTEXT fallback
  -> 最近 200 条扫描 fallback
  -> 旧 Java 关键词精排 fallback
```

### 1. ES 优先召回

生产 compose 默认启用 ES，本地开发和应用自身默认仍是关闭。

ES 查询使用：

- `userId` filter：用户隔离。
- `enabled` filter：只召回启用文档。
- `multi_match(title^5,keywords^4,tags^3,summary^2,content)`：主 BM25 关键词检索。
- `match_phrase(title/keywords/tags/summary/content)`：短语增强。
- `term(title.keyword/keywords.keyword/tags.keyword)`：精确匹配增强。
- `multi_match(title.ngram/keywords.ngram/tags.ngram/summary.ngram/content.ngram)`：无 IK 时的中文短词兼容召回。
- highlight：优先生成贴近命中位置的 snippet。

ES 命中后会转成 `SearchResult`：

```text
chunkId
docId
title
content/snippet
score = ES _score
rank = ES rank
source = elasticsearch
```

如果 ES 不可用、报错或无结果，则回退到 MySQL。旧 Java 规则不再是关键词主排序，只作为 fallback。

### 2. MySQL FULLTEXT fallback

回退时先调用：

```java
knowledgeDocumentMapper.searchFulltextCandidates(userId, query, 200)
```

这里的目标是从 MySQL 里粗筛最多 200 条候选，避免回到最早“查出全部文档再 Java 扫描”的高成本模式。

### 3. 最近 200 条扫描 fallback

如果 FULLTEXT 不可用或没有候选，会按用户和启用状态加载最近 200 条：

```text
WHERE user_id = ?
  AND enabled = true
ORDER BY updated_time DESC, id DESC
LIMIT 200
```

这是最后兜底，目的是保证 ES/FULLTEXT 出问题时聊天主链路仍可用。

### 4. Java 关键词精排

仅当 ES 无结果时，对 MySQL/Fallback 候选继续用旧 `KeywordExtractor` 做兜底精排：

| 信号 | 标题命中 | 正文命中 |
| --- | ---: | ---: |
| 英文技术词 | +8 | +4 |
| 中文短语 | +8 | +4 |
| 标题短语反向命中 query | +8 | - |
| bigram 兜底 | +2 | +1 |

精排规则：

- 先用技术词和中文短语。
- 如果已有高价值命中，bigram 只记录为 ignored，不重复加分。
- 最后做最长匹配抑制，例如保留 `文件上传`、`上传失败`，覆盖 `文件`、`上传`、`失败` 等碎片。

## 融合排序和过滤

`RrfFusionService` 负责把向量候选和 ES 关键词候选合并为最终进入 prompt 的片段。

### 1. 按 chunk 合并

候选以 `chunkId` 为单位合并。同一个 chunk 同时被向量和 ES 召回时，会保留两边的 rank、raw score 和来源。

### 2. 加权 RRF

RRF 分数：

```text
rrfScore += 1 / (rrfK + vectorRank)
rrfScore += 1 / (rrfK + keywordRank)
```

默认：

```yaml
app:
  rag:
    hybrid:
      rrf-k: 60
      rrf-k: 60
```

RRF 融合结果保留：

- `rrfScore`
- `vectorRank`
- `keywordRank`
- `vectorScore`
- `keywordScore`
- `sources`，例如 `["vector", "elasticsearch"]`

## Prompt 注入

最终选中的 `RagReference` 会进入 `RagService.buildKnowledgePrompt(...)`：

```text
以下是可用知识片段，请优先基于这些内容回答，并在答案中尽量保持事实一致：
[1] 标题
片段内容
[2] 标题
片段内容
如果知识片段无法覆盖问题，请明确说明并给出保守回答。
```

同时会输出日志：

- `selectedChunks`：进入 prompt 的片段数。
- `PromptChunk`：chunkId、docId、title、score、contentPreview。
- `PromptContext`：最终拼接长度和预览。

## 索引写入链路

知识库文档写入后，会发 Kafka 事件：

```text
RagService.createDocument(...)
  -> MySQL 插入 knowledge_document
  -> KnowledgeEventProducer 发送 KNOWLEDGE_CREATED
  -> KnowledgeEventConsumer.onKnowledgeEvent(...)
```

消费者处理：

```text
KnowledgeEventConsumer
  -> ChatContextService.evictUserContext(userId)
  -> VectorIndexingService.indexDocument(...)
  -> ElasticsearchRagService.indexDocument(...)
  -> ACK
```

删除文档时：

```text
KNOWLEDGE_DELETED
  -> VectorIndexingService.deleteDocument(...)
  -> ElasticsearchRagService.deleteDocument(...)
```

## 向量索引写入

`VectorIndexingService.indexDocument(...)` 流程：

```text
读取 KnowledgeDocument
  -> DocumentChunker 按 chunk.size/chunk.overlap 切块
  -> enrichForEmbedding: 标题 + 标签 + chunk 正文
  -> EmbeddingClient.embed(...)
  -> 校验 embedding dimensions
  -> PgVectorClient.indexDocument(...)
  -> 更新 indexStatus=INDEXED
```

embedding 输入会补标题和标签：

```text
标题：...
标签：...
正文 chunk
```

但 PGVector 表里保存的 `content` 仍是原始 chunk，用于 prompt 展示，避免把标题和标签重复注入模型。

PGVector 表：

```text
ai_studio_knowledge_vectors
  id TEXT PRIMARY KEY
  document_id BIGINT
  user_id BIGINT
  chunk_index INT
  content TEXT
  title TEXT
  metadata JSONB
  embedding VECTOR(1024)
```

索引：

- `(user_id, document_id)` 普通索引。
- `embedding vector_cosine_ops` HNSW 索引。

## ES 索引写入

`ElasticsearchRagService.indexDocument(...)` 流程：

```text
读取 KnowledgeDocument
  -> initializeIndexIfNeeded()
  -> deleteDocument(userId, documentId)
  -> DocumentChunker 切块
  -> 每个 chunk 写入 ES
```

ES chunk 文档字段：

```text
chunk_id
document_id
user_id
enabled
title
content
tags
updated_time
```

当前默认索引名：

```text
ai_studio_knowledge_v3
```

v3 mapping 重点：

- `title`、`keywords`、`tags`、`summary`、`content` 使用 text 字段。
- `title.keyword`、`keywords.keyword`、`tags.keyword`、`fileName.keyword` 用于精确匹配。
- `title.ngram`、`keywords.ngram`、`tags.ngram`、`summary.ngram`、`content.ngram` 用于无 IK 时的中文短词兼容召回。
- `userId`、`docId`、`chunkId` 用于精确过滤和去重。

## 手动重建索引

入口：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

服务端调用：

```text
RagService.reindexUserDocuments(userId)
  -> 查询用户所有 enabled 文档
  -> VectorIndexingService.indexDocument(...)
  -> ElasticsearchRagService.indexDocument(...)
```

返回字段：

```text
success
vectorEnabled
elasticsearchEnabled
requested
```

线上切换 ES v3 后，需要执行一次 reindex，让旧知识库补写到 `ai_studio_knowledge_v3`。

## 降级策略

当前 RAG 链路的降级分几层：

| 故障点 | 降级方式 |
| --- | --- |
| hybrid 向量检索失败 | 捕获异常，向量候选为空，继续关键词候选。 |
| vector 模式失败 | 如果 `fallback-to-keyword=true`，回退旧关键词检索。 |
| ES 不可用或无结果 | 回退 MySQL FULLTEXT。 |
| MySQL FULLTEXT 失败或无候选 | 回退最近 200 条扫描。 |
| PGVector 阈值过滤为空 | raw top 3 作为候选，再由 `RrfFusionService` 融合。 |
| 所有候选都弱相关 | 不注入 RAG；如果存在足够相似的 best vector，可 fallback 注入。 |

目标是：检索增强失败不影响聊天主链路，最多退化为普通对话或关键词 fallback。

## 关键配置

```yaml
app:
  rag:
    mode: ${APP_RAG_MODE:hybrid}
    fallback-to-keyword: true
    chunk:
      size: ${APP_RAG_CHUNK_SIZE:800}
      overlap: ${APP_RAG_CHUNK_OVERLAP:100}
      max-chunks-per-document: ${APP_RAG_MAX_CHUNKS_PER_DOCUMENT:200}
    vector:
      enabled: ${APP_RAG_VECTOR_ENABLED:true}
      table-name: ai_studio_knowledge_vectors
      dimensions: ${APP_RAG_VECTOR_DIMENSIONS:1024}
      top-k: ${APP_RAG_VECTOR_TOP_K:5}
      similarity-threshold: ${APP_RAG_VECTOR_SIMILARITY_THRESHOLD:0.3}
    elasticsearch:
      enabled: ${APP_RAG_ELASTICSEARCH_ENABLED:false}
      base-url: ${APP_RAG_ELASTICSEARCH_BASE_URL:http://localhost:9200}
      index-name: ${APP_RAG_ELASTICSEARCH_INDEX:ai_studio_knowledge_v3}
      top-k: ${APP_RAG_ELASTICSEARCH_TOP_K:50}
    embedding:
      provider: ${APP_RAG_EMBEDDING_PROVIDER:openai-compatible}
      model: ${APP_RAG_EMBEDDING_MODEL:text-embedding-v4}
      base-url: ${APP_RAG_EMBEDDING_BASE_URL:}
      embeddings-path: ${APP_RAG_EMBEDDING_PATH:/v1/embeddings}
    hybrid:
      rrf-k: 60
      final-top-k: 3
      strong-vector-threshold: 0.55
      medium-vector-threshold: 0.35
      fallback-vector-threshold: 0.45
      strong-keyword-threshold: 8.0
      max-chunks-per-document: 2
```

## 观测日志

排查 RAG 时优先看这些日志：

| 日志前缀 | 含义 |
| --- | --- |
| `[RAG] mode=...` | 当前 RAG 模式和 topK。 |
| `[HybridRAG-Intent]` | 检索词对应的问题类型和权重。 |
| `[HybridRAG] query=..., retrievalQuery=...` | 原始 query、改写 query、候选数量。 |
| `[QueryEnhancer]` | 查询增强后的 enhancedQuery 和命中的同义词 key。 |
| `[HybridRAG] keywordSource=elasticsearch` | 确认关键词候选是否来自 ES。 |
| `[ElasticsearchKeyword]` | ES BM25 关键词召回排名和 `_score`。 |
| `[HybridRAG-Vector]` | 向量候选原始排名和分数。 |
| `[HybridRAG-Keyword]` | 关键词候选原始排名、分数、matchedTerms。 |
| `[HybridRAG-RRF]` | RRF 后最终排名、双路 rank、raw score 和 sources。 |
| `[HybridRAG-PromptChunk]` | 最终注入 prompt 的知识片段。 |
| `[VectorRAG] threshold results empty, raw fallback results=...` | 向量阈值为空后的 raw fallback 情况。 |
| `[ElasticsearchRAG] keyword search failed, falling back` | ES 故障并回退。 |

## 文档阅读建议

日常维护按这个顺序看：

1. 本文：理解当前 RAG 全链路。
2. `RAG召回实际查询测试报告.md`：看真实查询样例和微调依据。
3. `ES关键词召回说明.md`：看 ES 在当前项目里的职责。
4. `RAG索引优化说明.md`：看为什么要加 FULLTEXT / ES index。
5. `Elasticsearch试运行记录.md`：看线上怎么启动、验证和回滚 ES。

旧的阶段报告和历史教程放在 `docs/archive/`，只用于追溯，不作为当前实现的权威说明。
