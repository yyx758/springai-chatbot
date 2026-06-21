# RAG 关键词检索重构执行报告

生成日期：2026-06-16

## 执行结论

本次已把关键词检索主链路从“Java 自研关键词提取 + 自定义规则打分”切换为“Elasticsearch BM25 关键词召回 + RRF 融合”。

当前链路：

```text
用户问题
  -> QueryRewriteService
  -> QueryEnhancer
  -> ElasticsearchKeywordSearchService
  -> VectorRagService
  -> RrfFusionService
  -> RagReference
  -> LLM prompt
```

旧 Java 关键词规则仍保留，但职责已降级：

- ES 有结果：关键词主排序来自 ES `_score` 和 rank。
- ES 无结果或不可用：才 fallback 到 MySQL FULLTEXT + 旧 Java 规则。
- `KeywordExtractor` 不再是关键词主链路评分器。

## 已完成阶段

### 阶段 1：DTO 和服务抽象

新增：

- `SearchResult`
- `HybridSearchResult`
- `SearchDebugResponse`
- `RrfFusionService`
- `QueryEnhancer`

作用：

- `SearchResult` 表示单路搜索结果。
- `HybridSearchResult` 表示 RRF 融合结果。
- `SearchDebugResponse` 用于 debug 接口。
- `RrfFusionService` 按 rank 融合 ES 和向量结果。
- `QueryEnhancer` 只做 query 增强，不做文档评分。

### 阶段 2：QueryEnhancer

新增词典：

```text
chatbot-service/src/main/resources/rag/query-synonyms.yml
```

示例：

```text
自动续期 -> 看门狗 watchdog Redisson
向量数据库 -> vector database pgvector
混合检索 -> hybrid search RRF rerank
死信队列 -> DLT dead letter topic
```

配置：

```yaml
app.rag.query-enhancer.enabled
app.rag.query-enhancer.max-expanded-terms
app.rag.query-enhancer.synonym-file
```

### 阶段 3：Elasticsearch 关键词主搜索服务

新增：

```text
ElasticsearchKeywordSearchService
```

ES 查询结构：

```text
bool
  filter:
    userId
    enabled=true
  should:
    multi_match title^5, keywords^4, tags^3, summary^2, content
    multi_match title.ngram, keywords.ngram, tags.ngram, summary.ngram, content.ngram
    match_phrase title/keywords/tags/summary/content
    term title.keyword/keywords.keyword/tags.keyword
```

返回：

```text
chunkId
docId
title
content
score = ES _score
rank = ES rank
source = elasticsearch
```

### 阶段 4：ES v3 索引字段

默认 ES 索引名已改为：

```text
ai_studio_knowledge_v3
```

同步更新：

- `RagProperties`
- `application.yml`
- `docker-compose.yml`
- `docker-compose.prod.yml`

`ElasticsearchRagService.indexDocument(...)` 写入 v3 字段：

```text
id
chunkId
docId
userId
title
content
summary
keywords
tags
fileName
enabled
createdAt
updatedAt
```

同时保留旧字段别名：

```text
chunk_id
document_id
user_id
updated_time
```

用于兼容旧测试和旧读取逻辑。

### 阶段 5：RRF 融合重构

新增 `RrfFusionService`，按 rank 计算：

```text
rrfScore = Σ 1 / (k + rank)
```

融合结果保留：

```text
chunkId
docId
title
content
rrfScore
vectorRank
keywordRank
vectorScore
keywordScore
sources
```

同一个 chunk 被 ES 和向量同时召回时，`sources` 会包含：

```text
["vector", "elasticsearch"]
```

### 阶段 6：Debug 接口

新增：

```http
GET /api/admin/debug/search?userId=xxx&query=xxx&topK=3
```

返回：

- `query`
- `enhancedQuery`
- `esResults`
- `vectorResults`
- `rrfResults`

安全：

- 路径在 `/api/admin/**` 下。
- Gateway 会校验 ADMIN。
- `SearchDebugController` 也加了 `@RequireRole("ADMIN")`。

## 降级策略

当前关键词链路：

```text
ElasticsearchKeywordSearchService
  -> 有结果：直接作为 keywordResults
  -> 无结果/失败：MySQL FULLTEXT
  -> FULLTEXT 失败/无结果：最近 200 条扫描
  -> 旧 Java KeywordExtractor + contains 规则评分
```

这保证 ES 切换为主链路时，主聊天链路不会因为 ES 故障直接失效。

## 测试结果

已执行：

```bash
mvn -q -pl chatbot-service "-Dtest=ElasticsearchKeywordSearchServiceTest,QueryEnhancerTest,RrfFusionServiceTest,HybridSearchServiceCandidateTest,RagRecallEvaluationTest,ElasticsearchRagServiceTest,VectorRagServiceTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest" test
```

结果：通过。

覆盖内容：

- ES bool query 包含 `userId` filter、`enabled` filter、多字段 boost、`minimum_should_match`。
- ES hit 能转换为 `SearchResult`。
- QueryEnhancer 能从词典增强 query，也能配置关闭。
- RRF 能按 chunkId 合并 ES 和向量来源。
- ES 为空时能 fallback 到 MySQL FULLTEXT / 旧 Java 规则。
- 原代表性查询仍能通过 fallback 测试。
- 向量 raw fallback 仍可用。
- 知识库 Kafka 事件仍触发向量和 ES 索引刷新。

## 线上使用注意

因为默认索引从 v2 切到 v3，部署后需要重建索引：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

检查：

```bash
curl -sS 'http://127.0.0.1:9200/_cat/indices?v'
```

重点看：

```text
ai_studio_knowledge_v3
docs.count > 0
```

## 未完成/后续阶段

本次还没有引入 IK analyzer 或 Java 分词器。

后续建议：

1. 先在线上用 v3 + standard/ngram 跑真实 query。
2. 通过 `/api/admin/debug/search` 收集 ES、向量、RRF 的对比结果。
3. 如果中文分词仍明显不足，再做 ES IK 插件方案：
   - 构建匹配 ES 8.15.3 的 IK 镜像。
   - 新建 v4 index。
   - reindex。
   - 灰度切换。

Java 应用层分词器只建议用于 QueryEnhancer/debug，不建议重新变成主评分器。
