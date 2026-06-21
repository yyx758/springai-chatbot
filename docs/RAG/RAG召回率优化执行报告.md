# RAG 召回率优化执行报告

生成日期：2026-06-15

## 目标

本次优化面向“实际落地后召回率不可能一次就很好”的问题，在不引入新重型服务的前提下，继续增强当前 Hybrid RAG：

- 关键词层：提升中文短语、短查询、标题/标签命中的召回能力。
- 向量检索层：扩大候选池，降低阈值过滤导致“完全无候选”的概率。
- 可观测性：让日志能看出原始 query、检索 query、候选数量和最终过滤原因。

## 关键词层改动

### 1. Elasticsearch 索引升级为 v2

默认 ES 索引名从：

```text
ai_studio_knowledge
```

调整为：

```text
ai_studio_knowledge_v2
```

这样不会直接覆盖旧索引。线上切换到 v2 后，需要调用 `/api/knowledge/reindex` 把已有知识库重新写入新索引。

涉及配置：

```yaml
APP_RAG_ELASTICSEARCH_INDEX: ${APP_RAG_ELASTICSEARCH_INDEX:-ai_studio_knowledge_v2}
```

### 2. ES mapping 增加内置 ngram analyzer

v2 index 初始化时增加：

- `ai_studio_ngram_tokenizer`
- `ai_studio_ngram`
- `title.ngram`
- `tags.ngram`
- `content.ngram`
- `title.exact`
- `tags.exact`

作用：

- `standard` 字段继续用于常规全文检索。
- `*.ngram` 字段增强中文短语和短查询召回。
- `*.exact` 字段保留标题、标签精确匹配信号。

### 3. ES 查询从单一 multi_match 改为 bool should

原来 ES 查询主要是：

```json
{
  "multi_match": {
    "fields": ["title^3", "tags^2", "content"]
  }
}
```

现在改为多路 should：

- `title.exact` 精确命中，最高权重。
- `tags.exact` 精确命中，较高权重。
- `title/tags/content` 的 `match_phrase`。
- `title^3/tags^2/content` 的普通全文检索。
- `title.ngram^2/tags.ngram^1.5/content.ngram` 的中文短语召回。

这样可以兼顾精确匹配、短语匹配和中文子串召回。

### 4. ES matchedTerms 复用 KeywordExtractor

原来 ES 的 `matchedTerms` 主要按空格和标点切分，对中文整句不友好。

现在复用 `KeywordExtractor`：

- 技术词：如 `Redis`、`Docker`、`NullPointerException`。
- 中文短语：如 `退款政策`。
- 中文 bigram：如 `退款`。
- 最长匹配抑制：避免长词和短碎片重复计分。

### 5. 中文两字关键词可作为强关键词信号

原来的 `HybridRanker.hasStrongKeywordSignal(...)` 对中文短词不够友好。比如“退款”能拿到关键词分数，但因为长度不足 4，可能被过滤。

现在中文 2 字以上关键词也可以作为强关键词信号，避免“退款”“登录”“上传”这类高价值短词被误过滤。

## 向量检索层改动

### 1. 扩大向量候选池

`HybridSearchService` 调用向量检索时，从：

```text
max(topK * 2, 10)
```

调整为：

```text
max(topK * 4, 20)
```

最终进入 prompt 的数量仍由 `HybridRanker` 控制，不会直接导致 prompt 变长。

### 2. 阈值过滤为空时 raw top 兜底

`VectorRagService` 仍先按 `similarityThreshold` 查询 PGVector。

如果阈值过滤后没有任何结果，则额外取 raw top 3：

```text
threshold results empty
  -> searchRawTopK(...)
  -> 进入 HybridRanker
```

这些 raw fallback 候选不会无条件进入 prompt，仍要经过 `HybridRanker` 的强向量、中等向量 + 关键词、fallback 阈值等规则。

### 3. Embedding 文本加入标题和标签

向量索引时，embedding 输入从“纯 chunk 正文”调整为：

```text
标题：...
标签：...
正文 chunk
```

PGVector 中保存的 snippet 仍是原始 chunk 内容，避免 prompt 中出现重复标题/标签；但 embedding 会更理解文档主题，提升标题型、标签型问题的语义召回概率。

## Query Rewrite

新增轻量规则版 `QueryRewriteService`，用于检索前清洗用户问题。

示例：

```text
请问如何退款政策？ -> 退款政策
Redis TTL -> Redis TTL
```

它只用于检索 query，不改用户原始输入，不引入 LLM 调用，因此不会增加明显延迟。

## 验证结果

已执行：

```bash
mvn -q -pl chatbot-service "-Dtest=ElasticsearchRagServiceTest,HybridSearchServiceCandidateTest,VectorRagServiceTest,HybridRagServiceTest,KnowledgeEventVectorIndexTest" test
```

结果：通过。

覆盖场景：

- ES v2 index 初始化包含 ngram mapping。
- ES 查询 payload 包含 `title^3`、`tags^2`、`title.ngram^2`、`content.ngram` 和 `minimum_should_match`。
- ES 命中会转换为 `HybridCandidate.keywordScore` 和 `matchedTerms`。
- ES 不可用时仍回退 MySQL FULLTEXT。
- FULLTEXT 失败时仍回退最近 200 条有限扫描。
- query rewrite 会把“如何退款”改成“退款”参与检索。
- 中文两字关键词“退款”可以被 `HybridRanker` 选中。
- 向量阈值无结果时会调用 raw top fallback。

## 线上使用注意

本次把默认 ES index 改成 `ai_studio_knowledge_v2`。远程生产如果要使用新召回策略，需要：

1. 部署新代码和 compose 配置。
2. 确认环境变量：

```bash
docker exec chatbot-service sh -c 'printenv | grep APP_RAG_ELASTICSEARCH'
```

3. 登录后调用：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

4. 查看 ES 文档数量：

```bash
curl -sS 'http://127.0.0.1:9200/_cat/indices?v'
```

看到 `ai_studio_knowledge_v2` 的 `docs.count` 大于 0，说明新索引已有数据。

## 当前边界

- 仍未引入 IK analyzer，避免增加 ES 插件部署复杂度。
- 仍未引入独立 rerank 模型，先通过召回候选和规则融合提升稳定性。
- ES 仍只做关键词召回，不做 dense vector 检索。
- 当前还没有自动化离线评测集；下一步可以补 10-20 条 query 的 recall@3 / recall@5 对比。
