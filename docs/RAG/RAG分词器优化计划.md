# RAG 关键词检索重构与分词器优化计划

生成日期：2026-06-16

## 背景

当前 RAG 已经具备混合检索能力：

- 向量检索：PGVector / embedding 语义召回。
- 关键词检索：Elasticsearch 已引入，但 Java 侧仍保留大量自研关键词提取和规则评分。
- 融合排序：`HybridRanker` 使用 RRF 思路融合向量候选和关键词候选。

目前最大问题是关键词主链路仍被 Java 手写规则强绑定：

- `KeywordExtractor.STOP_WORDS`
- `KeywordExtractor.FILLER_CHARS`
- `KeywordExtractor.BAD_BIGRAMS`
- 中文 2-gram 枚举
- 中文 phrase 枚举
- `String.contains(...)` 命中正文/标题
- 按命中词数量手动累加 `keywordScore`

这些规则短期能跑通，但维护成本很高：

1. 垃圾 bigram 和无意义 phrase 仍然很多。
2. 每发现一个新垃圾词，都要改 Java 代码、重新构建、重新部署。
3. 词典量一大后，硬编码集合维护不住。
4. Java 自己做关键词主评分，容易和 ES BM25 排序、ES analyzer 的结果不一致。
5. Debug 日志里的 `matchedTerms/coveredTerms` 不是稳定的搜索解释，排查召回问题时容易误导。

因此本次优化不只是“引入分词器”，而是把关键词主链路迁移到 Elasticsearch：

```text
用户问题
  -> QueryEnhancer 增强查询
  -> Elasticsearch 关键词召回和 BM25 排序
  -> PGVector 向量召回
  -> RRF 融合
  -> TopK 知识片段进入 prompt
```

## 总目标

- Elasticsearch 负责关键词检索主排序：中文分词、倒排索引、BM25、多字段 boost。
- Java 不再承担关键词主评分，不再用手写 2-gram / phrase / contains 作为主检索排序逻辑。
- 旧 `KeywordExtractor` 降级为 QueryEnhancer、debug 辅助或 fallback，不作为关键词主评分。
- 保留当前混合检索架构：ES 关键词召回 + PGVector 向量召回 + RRF 融合。
- 新增 debug 搜索接口，能看清 ES、向量、RRF 三阶段结果。
- 分阶段引入分词器/analyzer：优先 ES analyzer，应用层只做 query 增强和词典管理。

## 非目标

本阶段不做：

- 不改变现有聊天 Controller 对外接口。
- 不删除 PGVector 向量检索。
- 不废弃 RRF 融合。
- 不一次性接入 rerank 模型。
- 不把 Java 分词器变成主关键词评分器。
- 不直接暴力删除旧类，先废弃主链路并保留 fallback/debug。

## 当前代码定位

需要重点检查和改造的类：

| 类 | 当前职责 | 本次调整 |
| --- | --- | --- |
| `HybridSearchService` | 编排向量候选、关键词候选、融合排序。 | 保留编排职责，但关键词候选改为 ES 主链路。 |
| `ElasticsearchRagService` | 当前 ES 索引写入、ES 关键词候选查询。 | 升级为 ES BM25 主关键词召回，或拆出 `ElasticsearchKeywordSearchService`。 |
| `KeywordExtractor` | 技术词、phrase、bigram、停用词、规则词提取。 | 降级为 QueryEnhancer/debug/fallback，不再做主评分。 |
| `HybridRanker` | RRF + 规则过滤。 | 可保留，但建议拆出更清晰的 `RrfFusionService`。 |
| `VectorRagService` | PGVector 向量检索。 | 保持不破坏。 |
| `VectorIndexingService` | 向量索引写入。 | 保持。 |
| `RagService` | RAG 总入口和 prompt 拼接。 | 保持对外行为。 |

旧逻辑中需要删除或降级的点：

| 旧逻辑 | 位置 | 处理 |
| --- | --- | --- |
| Java 手写 2-gram 切词 | `KeywordExtractor.extractBigrams(...)` | 降级为 fallback/debug。 |
| Java 手写 phrase 枚举 | `KeywordExtractor.extractPhrases(...)`、`extractQueryPhrases(...)` | 降级为 fallback/debug。 |
| badBigrams 过滤 | `KeywordExtractor.BAD_BIGRAMS` | 不再作为主链路依赖。 |
| filler chars 过滤 | `KeywordExtractor.FILLER_CHARS` | 不再作为主链路依赖。 |
| contains 正文匹配 | `HybridSearchService.scoreDocument(...)` | 从关键词主链路移除。 |
| 命中词手动累加分数 | `HybridSearchService.scoreDocument(...)` | 用 ES `_score` / BM25 替代。 |

## 目标链路

### 查询链路

```text
用户问题
  -> QueryEnhancer.enhance(query)
  -> ElasticsearchKeywordSearchService.search(userId, enhancedQuery, keywordTopK)
  -> VectorRagService.retrieve(userId, enhancedQuery 或 originalQuery, vectorTopK)
  -> RrfFusionService.fuse(vectorResults, keywordResults, finalTopK)
  -> RagService.buildKnowledgePrompt(...)
```

说明：

- ES 关键词结果用 ES `_score` 排序，但进入 RRF 时只使用 rank。
- 向量结果保留 vector score，但进入 RRF 时也只使用 rank。
- RRF 结果保留两路原始分数用于 debug，不把 raw score 混合。

### 索引写入链路

```text
KnowledgeDocument 写入 MySQL
  -> Kafka KNOWLEDGE_CREATED / UPDATED
  -> KnowledgeEventConsumer
  -> VectorIndexingService.indexDocument(...)
  -> ElasticsearchKeywordIndexService.indexDocument(...)
```

删除：

```text
KNOWLEDGE_DELETED
  -> VectorIndexingService.deleteDocument(...)
  -> ElasticsearchKeywordIndexService.deleteDocument(...)
```

## Elasticsearch 索引设计

建议新建 v3 索引，避免破坏当前 `ai_studio_knowledge_v2`：

```text
ai_studio_knowledge_v3
```

### 字段设计

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `keyword` | ES 文档 ID，也可等于 chunkId。 |
| `chunkId` | `keyword` | chunk 唯一标识。 |
| `docId` | `long` | 知识文档 ID。 |
| `userId` | `keyword` 或 `long` | 用户隔离过滤字段。项目当前 userId 是 Long，可用 `long`。 |
| `title` | `text` + 子字段 | 文档标题。 |
| `content` | `text` + 子字段 | chunk 正文。 |
| `summary` | `text` + 子字段 | 可选摘要。第一阶段可为空。 |
| `keywords` | `text` + `keyword` | 可选关键词数组。 |
| `tags` | `text` + `keyword` | 标签数组或标签文本拆分。 |
| `fileName` | `text` + `keyword` | 文件名。 |
| `enabled` | `boolean` | 是否启用。 |
| `createdAt` | `date` | 创建时间。 |
| `updatedAt` | `date` | 更新时间。 |

### IK analyzer 方案

如果能使用 IK 插件，建议 v3 mapping 使用：

```json
{
  "settings": {
    "analysis": {
      "analyzer": {
        "ik_search_analyzer": {
          "type": "custom",
          "tokenizer": "ik_smart"
        },
        "ik_index_analyzer": {
          "type": "custom",
          "tokenizer": "ik_max_word"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "chunkId": { "type": "keyword" },
      "docId": { "type": "long" },
      "userId": { "type": "long" },
      "enabled": { "type": "boolean" },
      "title": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 256 }
        }
      },
      "content": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer"
      },
      "summary": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer"
      },
      "keywords": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 128 }
        }
      },
      "tags": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 128 }
        }
      },
      "fileName": {
        "type": "text",
        "analyzer": "ik_index_analyzer",
        "search_analyzer": "ik_search_analyzer",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 256 }
        }
      },
      "createdAt": { "type": "date" },
      "updatedAt": { "type": "date" }
    }
  }
}
```

IK 注意事项：

- IK 插件版本必须匹配 `elasticsearch:8.15.3`。
- 可能需要自定义 ES Docker 镜像。
- 4GB 服务器内存有限，需要评估 heap 和系统余量。
- 如果 IK 构建失败，必须能回退 standard/ngram 方案。

### 无 IK 兼容方案

如果暂时没有 IK，继续使用内置 analyzer：

- `standard`：主 text analyzer。
- `ngram`：增强中文短词和子串召回。
- `keyword` 子字段：用于标题、标签、文件名精确匹配。

兼容 mapping 思路：

```json
{
  "settings": {
    "analysis": {
      "tokenizer": {
        "ai_studio_ngram_tokenizer": {
          "type": "ngram",
          "min_gram": 2,
          "max_gram": 4,
          "token_chars": ["letter", "digit"]
        }
      },
      "analyzer": {
        "ai_studio_ngram": {
          "type": "custom",
          "tokenizer": "ai_studio_ngram_tokenizer",
          "filter": ["lowercase"]
        }
      }
    }
  }
}
```

字段：

- `title`: `text(standard)` + `keyword` + `ngram`
- `content`: `text(standard)` + `ngram`
- `summary`: `text(standard)` + `ngram`
- `keywords`: `text(standard)` + `keyword` + `ngram`
- `tags`: `text(standard)` + `keyword` + `ngram`
- `fileName`: `text(standard)` + `keyword`

## Elasticsearch 查询设计

新增或重构：

```java
ElasticsearchKeywordSearchService
```

核心方法：

```java
List<SearchResult> search(Long userId, String query, int topK);
```

查询结构：

```text
bool
  filter:
    term userId
    term enabled=true
  should:
    multi_match title^5, keywords^4, tags^3, summary^2, content^1
    match_phrase title^8
    match_phrase keywords^6
    match_phrase tags^5
    match_phrase summary^3
    match_phrase content^2
    term title.keyword^10
    term tags.keyword^6
    term keywords.keyword^6
  minimum_should_match: 60%
```

返回：

```text
chunkId
docId
title
content
score
rank
source = elasticsearch
```

字段 boost 第一阶段可以写常量，配置化预留：

```yaml
app:
  rag:
    keyword:
      top-k: ${APP_RAG_KEYWORD_TOP_K:50}
      minimum-should-match: ${APP_RAG_KEYWORD_MINIMUM_SHOULD_MATCH:60%}
      source: elasticsearch
```

## QueryEnhancer 设计

新增：

```java
QueryEnhancer
```

职责只做查询增强，不做文档评分。

输入：

```text
自动续期
```

输出：

```text
自动续期 看门狗 watchdog Redisson
```

词典不写复杂 if-else，建议放资源文件：

```text
chatbot-service/src/main/resources/rag/query-synonyms.yml
```

示例：

```yaml
synonyms:
  自动续期:
    - 看门狗
    - watchdog
    - Redisson
  向量数据库:
    - vector database
    - pgvector
  混合检索:
    - hybrid search
    - RRF
    - rerank
  死信队列:
    - DLT
    - dead letter topic
```

QueryEnhancer 规则：

- 只追加少量同义词，不大幅改写用户原意。
- 保留原始 query。
- 每个 query 最多追加 N 个扩展词，避免 query 过长。
- 记录日志：`originalQuery`、`enhancedQuery`、`matchedSynonymKeys`。
- 可配置关闭：`APP_RAG_QUERY_ENHANCER_ENABLED=false`。

## RRF 融合重构

建议新增：

```java
RrfFusionService
```

输入：

```text
vectorResults
keywordResults
```

RRF 公式：

```text
rrfScore = Σ 1 / (k + rank)
```

默认：

```yaml
app:
  rag:
    rrf:
      k: ${APP_RAG_RRF_K:60}
```

融合结果：

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
sources: ["vector", "elasticsearch"]
```

同一个 chunk 同时被 ES 和向量命中：

- 按 `chunkId` 合并。
- 保留两个 rank。
- 保留两个 raw score。
- sources 同时包含 `vector` 和 `elasticsearch`。
- RRF 只使用 rank，不直接混合 raw score。

## Debug 接口

新增：

```http
GET /debug/search?userId=xxx&query=xxx
```

如果走生产 Gateway，建议后续把路径放到管理员权限下：

```http
GET /api/admin/debug/search?userId=xxx&query=xxx
```

返回：

```json
{
  "query": "自动续期",
  "enhancedQuery": "自动续期 看门狗 watchdog Redisson",
  "esResults": [
    {
      "rank": 1,
      "chunkId": "12_0",
      "docId": 12,
      "title": "Redisson 看门狗机制",
      "score": 13.42,
      "source": "elasticsearch"
    }
  ],
  "vectorResults": [
    {
      "rank": 1,
      "chunkId": "12_0",
      "docId": 12,
      "title": "Redisson 看门狗机制",
      "score": 0.78,
      "source": "vector"
    }
  ],
  "rrfResults": [
    {
      "rank": 1,
      "chunkId": "12_0",
      "docId": 12,
      "title": "Redisson 看门狗机制",
      "rrfScore": 0.0327,
      "vectorRank": 1,
      "keywordRank": 1,
      "vectorScore": 0.78,
      "keywordScore": 13.42,
      "sources": ["vector", "elasticsearch"]
    }
  ]
}
```

安全要求：

- Debug 接口不要匿名开放。
- 本地开发可临时使用 `/debug/search`。
- 生产建议挂到 `/api/admin/debug/search` 并走 RBAC。

## 分阶段实施计划

### 阶段 0：基线审计和文档确认

目标：

- 找出现有 Java 手写关键词评分的所有入口。
- 确认哪些保留为 QueryEnhancer/debug，哪些从主链路废弃。

输出：

- 更新本计划或新增执行报告。
- 不改业务逻辑。

### 阶段 1：DTO 和服务抽象

新增：

- `SearchResult`
- `HybridSearchResult`
- `SearchDebugResponse`
- `ElasticsearchKeywordSearchService`
- `QueryEnhancer`
- `RrfFusionService`

要求：

- 不立即替换线上主链路。
- 先通过单元测试验证 ES payload、QueryEnhancer、RRF 合并逻辑。

### 阶段 2：ES v3 索引和索引写入

新增或升级：

- `ai_studio_knowledge_v3`
- v3 mapping 初始化。
- 文档 chunk 写入 v3 字段：`chunkId/docId/userId/title/content/summary/keywords/tags/fileName/enabled/createdAt/updatedAt`。

兼容：

- 如果 IK 可用，使用 IK mapping。
- 如果 IK 不可用，使用 `standard + ngram + keyword` mapping。

验收：

- `/api/knowledge/reindex` 可把旧知识库写入 v3。
- ES 文档数可查。

### 阶段 3：ES 成为关键词主链路

调整：

- `HybridSearchService.searchKeyword(...)` 改为调用 `ElasticsearchKeywordSearchService`。
- ES 有结果时，直接使用 ES rank 和 `_score`。
- Java `scoreDocument(...)` 不再作为关键词主排序。
- MySQL FULLTEXT / Java contains 只作为 fallback 或 debug。

验收：

- 关键词检索主结果来源为 `elasticsearch`。
- 日志能看到 ES topK。
- 旧 Java 规则不再主导 keywordScore。

### 阶段 4：RRF 融合重构

调整：

- 引入 `RrfFusionService`。
- 输入向量结果和 ES 结果。
- 按 rank 计算 RRF。
- 合并 sources、vectorRank、keywordRank、vectorScore、keywordScore。

验收：

- 同 chunk 双路命中时 sources 包含 `vector` 和 `elasticsearch`。
- RRF 使用 rank，不直接混 raw score。
- 原有向量召回不被破坏。

### 阶段 5：Debug 接口

新增：

- `SearchDebugController`
- `GET /debug/search?userId=xxx&query=xxx`

返回：

- `enhancedQuery`
- `esResults`
- `vectorResults`
- `rrfResults`

验收：

- 能解释某个 chunk 为什么被召回。
- 能看到 ES 和向量结果差异。

### 阶段 6：引入分词器/analyzer

优先顺序：

1. ES IK analyzer：如果能稳定构建 ES 8.15.3 IK 镜像，并且服务器内存允许。
2. ES 内置 `standard + ngram`：作为无 IK 兼容方案。
3. Java 应用层分词器：只用于 QueryEnhancer、debug、词典辅助，不作为主评分。

如果引入 Java 分词器：

- 使用 `RagTokenizer` 抽象。
- 停用词、保护词、同义词放资源文件。
- 不恢复 Java 主评分。

## 配置规划

建议新增：

```yaml
app:
  rag:
    elasticsearch:
      enabled: ${APP_RAG_ELASTICSEARCH_ENABLED:false}
      index-name: ${APP_RAG_ELASTICSEARCH_INDEX:ai_studio_knowledge_v3}
      analyzer-mode: ${APP_RAG_ELASTICSEARCH_ANALYZER_MODE:standard_ngram}
      minimum-should-match: ${APP_RAG_KEYWORD_MINIMUM_SHOULD_MATCH:60%}
      top-k: ${APP_RAG_KEYWORD_TOP_K:50}
    query-enhancer:
      enabled: ${APP_RAG_QUERY_ENHANCER_ENABLED:true}
      max-expanded-terms: 6
      synonym-file: classpath:rag/query-synonyms.yml
    rrf:
      k: ${APP_RAG_RRF_K:60}
      final-top-k: ${APP_RAG_FINAL_TOP_K:3}
```

说明：

- 当前项目已有 `app.rag.elasticsearch`，实施时优先复用，避免配置重复。
- `minimum-should-match` 和 `top-k` 可以放在现有 `RagProperties.Elasticsearch` 中。
- `rrf` 可以复用或迁移现有 `HybridRagProperties.rrfK`。

## 测试计划

新增或更新测试：

| 测试类 | 重点 |
| --- | --- |
| `ElasticsearchKeywordSearchServiceTest` | ES bool query、filter userId、multi_match boost、minimum_should_match、返回 rank。 |
| `ElasticsearchIndexMappingTest` | v3 mapping 字段、IK/standard_ngram 两套 mapping。 |
| `QueryEnhancerTest` | 同义词增强、关闭开关、最大扩展词数。 |
| `RrfFusionServiceTest` | rank 融合、同 chunk 合并 sources、raw score 保留但不参与混合。 |
| `SearchDebugControllerTest` | debug response 结构。 |
| `RagRecallEvaluationTest` | 代表性 query 不回退，ES 关键词主链路命中。 |

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ElasticsearchKeywordSearchServiceTest,ElasticsearchIndexMappingTest,QueryEnhancerTest,RrfFusionServiceTest,RagRecallEvaluationTest" test
mvn -q -DskipTests package
```

## 验收标准

改造完成后应满足：

1. 关键词检索主链路由 Elasticsearch 完成。
2. Java 不再承担主要关键词打分职责。
3. ES 支持 `title/keywords/tags/summary/content` 多字段加权检索。
4. ES 查询必须通过 `userId` filter 做数据隔离。
5. ES 结果和向量结果通过 RRF 按 rank 融合。
6. Debug 接口能看到 enhancedQuery、ES 结果、向量结果、RRF 结果。
7. 旧手写关键词规则被降级为 QueryEnhancer/debug/fallback。
8. 后续可以继续接入同义词词典、IK 分词器、rerank 模型。

## 风险与回滚

| 风险 | 回滚/缓解 |
| --- | --- |
| ES v3 mapping 不兼容 | 保留 v2 index，不直接覆盖旧索引。 |
| IK 插件构建失败 | 使用 `standard + ngram` 兼容方案。 |
| ES 无结果导致关键词召回断掉 | 保留 MySQL FULLTEXT / Java 旧逻辑 fallback。 |
| QueryEnhancer 扩展过度 | 限制最大扩展词数，可配置关闭。 |
| RRF 重构影响向量召回 | 向量结果 DTO 保留原始 score/rank，增加单测覆盖。 |
| Debug 接口泄露数据 | 生产放到 admin 路径并走 RBAC，不匿名开放。 |

## 审批后执行顺序

建议审批后分批执行：

1. 先做阶段 1：DTO、QueryEnhancer、RRF service、ES keyword service 单测。
2. 再做阶段 2：ES v3 mapping 和索引写入。
3. 再做阶段 3：把 keyword 主链路切到 ES。
4. 再做阶段 4-5：RRF debug 和 debug 接口。
5. 最后评估阶段 6：IK / analyzer / Java 分词器增强。

每个阶段完成后都生成测试结果记录，避免一次性大改后难以定位问题。
