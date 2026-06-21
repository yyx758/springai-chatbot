# Elasticsearch 关键词召回说明

生成日期：2026-06-15

## 结论

远程生产环境当前会默认使用 Elasticsearch 做 RAG 的关键词召回。

需要区分三层默认值：

| 场景 | 默认值 | 依据 |
| --- | --- | --- |
| 应用自身默认 | 关闭 ES | `application.yml` 中 `APP_RAG_ELASTICSEARCH_ENABLED:false` |
| 本地 Docker 默认 | 关闭 ES | `docker-compose.yml` 中 `APP_RAG_ELASTICSEARCH_ENABLED:-false`，且 ES 在 profile 下 |
| 生产 Docker 默认 | 开启 ES | `docker-compose.prod.yml` 中 `APP_RAG_ELASTICSEARCH_ENABLED:-true`，且 ES 默认启动 |

也就是说，应用代码保持保守默认，不强依赖 ES；生产部署通过环境变量覆盖为开启 ES，用来提高关键词召回能力。

## 原来是怎么做的

### 阶段 1：应用层全文扫描

最早的关键词召回逻辑是：

```text
用户问题
  -> 按 user_id + enabled 查出该用户全部启用知识库文档
  -> Java 遍历每篇文档的 title/content/tags
  -> KeywordExtractor 提取技术词、中文短语、bigram
  -> scoreDocument(...) 计算关键词分数
  -> HybridRanker 融合排序
```

这个方案的优点是简单、可解释、不依赖额外中间件；问题是数据量上来后，每次聊天都会把用户全部知识库正文拉回 JVM，再做字符串匹配，召回链路会越来越慢。

### 阶段 2：MySQL FULLTEXT 粗筛

后来增加了 MySQL FULLTEXT：

```text
用户问题
  -> MATCH(title, content, tags) AGAINST (...)
  -> MySQL 先筛出最多 200 条候选
  -> Java 继续使用原有 scoreDocument(...) 精排
  -> HybridRanker 融合排序
```

这个阶段的核心变化是把“候选粗筛”下推到 MySQL，避免每次扫描全部文档。Java 评分逻辑继续保留，所以标题、标签、技术词、中文短语、bigram 的可解释规则没有丢。

局限是 MySQL 默认 FULLTEXT 对中文不一定理想。如果没有 ngram parser，中文短语、短问题和混合技术词的召回效果会受限。

## 引入 ES 后的链路

现在关键词召回链路变成：

```text
用户问题
  -> HybridSearchService.searchKeyword(...)
  -> ElasticsearchRagService.searchKeywordCandidates(...)
       -> ES 有结果：返回 ES 关键词候选
       -> ES 关闭 / 失败 / 无结果：回退 MySQL FULLTEXT
       -> FULLTEXT 失败 / 无结果：回退最近 200 条有限扫描
  -> HybridRanker 融合 PGVector 向量候选和关键词候选
  -> prompt 注入最终选中的知识片段
```

关键代码关系：

```java
List<HybridCandidate> elasticsearchCandidates =
        elasticsearchRagService.searchKeywordCandidates(userId, query, limit);
if (!elasticsearchCandidates.isEmpty()) {
    return elasticsearchCandidates;
}

List<KnowledgeDocument> documents = loadKeywordCandidates(userId, query);
```

含义是：ES 是关键词召回的第一优先级。只有 ES 不可用、查询失败或没有结果时，才会走 MySQL FULLTEXT 和最近 200 条 fallback。

## ES 具体优化了什么

### 1. 从文档级召回升级到 chunk 级召回

MySQL FULLTEXT fallback 查询的是 `knowledge_document` 文档表，候选单位更接近“整篇文档”。

ES 索引时会先通过 `DocumentChunker` 把正文切成 chunk，然后写入 ES：

```text
documentId=12
  -> 12_0
  -> 12_1
  -> 12_2
```

这样检索命中的是更小的知识片段。长文档里只有某一段和问题相关时，ES 更容易返回靠近命中位置的 chunk，减少把整篇文档当成一个候选带来的噪声。

### 2. 使用倒排索引替代应用层正文扫描

ES 为 `title`、`tags`、`content` 建倒排索引。查询时不需要把所有文档正文拉回 JVM 再 `contains(...)`，而是由 ES 直接根据倒排索引找候选。

这主要优化两点：

- 性能：候选检索由搜索引擎完成，减少 MySQL 大字段读取和 JVM 字符串扫描。
- 召回：ES 对全文检索、字段权重、高亮片段支持更完整，比单纯 Java 扫描更适合作为关键词召回层。

### 3. 字段加权更明确，并增加 ngram 中文召回

当前 ES v2 查询使用：

```json
{
  "fields": ["title^3", "tags^2", "content", "title.ngram^2", "tags.ngram^1.5", "content.ngram"]
}
```

含义是：

- 标题命中权重最高，因为标题通常代表文档主题。
- 标签命中次高，因为标签是人为或系统补充的主题信号。
- 正文命中作为基础召回来源。
- `*.ngram` 字段用于增强中文短语和短查询的召回。

所以同样命中关键词时，标题或标签命中的文档会更容易排在前面。

### 4. 保留用户隔离和启用状态过滤

ES 查询不是全局搜索所有知识库，而是在 bool filter 中限制：

```text
user_id = 当前用户
enabled = true
```

这样仍然保持当前系统的用户隔离，不会把其他用户的知识库召回进来，也不会召回已禁用文档。

### 5. 使用 highlight 生成更贴近命中位置的 snippet

ES 查询会请求 highlight：

```text
content fragment_size = 160
number_of_fragments = 1
```

如果正文命中，返回的 snippet 更接近关键词所在位置；如果没有 highlight，再回退到 chunk 内容前 160 字。

这比简单截取正文开头更适合作为 prompt 里的知识片段，因为模型看到的是更接近用户问题的上下文。

### 6. 继续复用 HybridRanker

ES 不直接决定最终进入 prompt 的内容。ES `_score` 会转换成：

```text
HybridCandidate.keywordScore
```

然后进入 `HybridRanker`，和 PGVector 的向量候选一起做 RRF 融合排序。

所以当前不是“ES 替代 RAG”，而是：

```text
PGVector 负责语义召回
ES 负责关键词召回
HybridRanker 负责最终融合和过滤
```

### 7. 保留 fallback，避免 ES 故障影响主链路

ES 只是第一优先级，不是唯一链路：

```text
ES 失败
  -> MySQL FULLTEXT
  -> 最近 200 条有限扫描
```

这样即使 ES 容器异常、网络失败、索引为空，普通聊天仍然可以继续走 MySQL 和 Java 评分，不会因为搜索组件故障导致主聊天链路不可用。

## 当前限制

- 当前 ES v2 使用内置 ngram analyzer 增强中文短语召回，但还不是专业中文分词；后续可以继续评估 IK analyzer。
- 当前 ES 只做关键词召回，不做 dense vector 检索；向量检索仍由 PGVector 负责。
- 当前 ES 写入是逐 chunk PUT，大规模重建时可以优化为 Bulk API。
- 4GB 服务器可以运行 ES，但内存余量有限；线上曾观测 ES 约 741MiB，swap 已有使用，需要持续观察。
- 已有知识库文档需要执行 `/api/knowledge/reindex` 后才会完整进入 ES 索引。

## 如何确认线上正在使用 ES

进入服务器：

```bash
ssh ubuntu@111.229.127.171
cd /opt/springai-chatbot
```

确认 `chatbot-service` 收到了生产环境变量：

```bash
docker exec chatbot-service sh -c 'printenv | grep APP_RAG_ELASTICSEARCH'
```

预期看到：

```text
APP_RAG_ELASTICSEARCH_ENABLED=true
APP_RAG_ELASTICSEARCH_BASE_URL=http://elasticsearch:9200
APP_RAG_ELASTICSEARCH_INDEX=ai_studio_knowledge_v3
```

确认 ES 容器启动：

```bash
docker compose -f docker-compose.prod.yml ps elasticsearch
```

预期看到 `chatbot-elasticsearch` 为 `healthy`。

确认 ES 集群健康：

```bash
curl -sS http://127.0.0.1:9200/_cluster/health
```

预期看到：

```json
{"status":"green"}
```

重建索引后查看文档数量：

```bash
curl -sS 'http://127.0.0.1:9200/_cat/indices?v'
```

如果 `ai_studio_knowledge_v3` 的 `docs.count` 大于 0，说明已有 chunk 写入 ES。

## 面试表达

可以这样说：

> 我没有直接把 ES 当成向量数据库，而是把它放在 Hybrid RAG 的关键词召回层。原来关键词召回先经历了 Java 全量扫描，再优化到 MySQL FULLTEXT 粗筛；引入 ES 后，生产环境优先用 ES 的倒排索引检索知识库 chunk，并通过 `title^3`、`tags^2`、`content` 做字段加权。ES 返回的结果会转换成关键词候选，再和 PGVector 的语义候选进入 HybridRanker 融合排序。为了保证 4GB 单机的稳定性，ES 失败时会自动回退 MySQL FULLTEXT 和最近 200 条有限扫描，不影响主聊天链路。
