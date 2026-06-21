# RAG 关键词检索索引优化说明

生成日期：2026-06-13

## 背景

当前项目的 RAG 是 Hybrid RAG：

- 向量召回：`VectorRagService` 走 PGVector。
- 关键词召回：`HybridSearchService.searchKeyword(...)` 对知识库文档做关键词评分。
- 融合排序：`HybridRanker` 把向量候选和关键词候选合并，再选出进入 prompt 的引用。

这次加索引优化的是“关键词召回”这一路，不是替换 PGVector，也不是引入 Elasticsearch。

## 是怎么发现没加合适索引的

这次不是通过线上慢查询日志发现的，而是通过代码审查发现的性能隐患。判断依据有两类：查询代码和表结构迁移。

### 1. 原关键词检索是先查出用户全部启用文档

优化前，`HybridSearchService.searchKeyword(...)` 的核心逻辑是：

```java
List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
        new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getEnabled, true));
```

也就是说，数据库只负责按 `user_id` 和 `enabled` 过滤，真正的标题、正文、标签匹配全部放到 Java 内存里完成。

后续 Java 会对每篇文档执行：

- `KeywordExtractor.extractBigrams(query)`
- `KeywordExtractor.extractQueryPhrases(query)`
- `KeywordExtractor.extractTechnicalTerms(query)`
- `scoreDocument(...)`
- `title.contains(...)`
- `content.contains(...)`

这在文档少时没问题，但数据增长后，每次提问都会把该用户启用的知识库文档全部拉到 JVM，再逐篇扫描 `content TEXT`。性能瓶颈会出现在三个地方：

- MySQL 需要返回大量 `TEXT` 字段。
- JVM 需要对大量正文做字符串匹配。
- RAG 是聊天链路的一部分，会直接拉长首 token 前等待时间。

### 2. 原表结构只有过滤索引，没有内容检索索引

初始表结构在 `chatbot-service/src/main/resources/db/migration/V1__init_schema.sql`：

```sql
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    tags VARCHAR(256) NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_knowledge_user_enabled (user_id, enabled),
    INDEX idx_knowledge_updated_time (updated_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

这里有两个索引：

- `idx_knowledge_user_enabled (user_id, enabled)`：适合筛选某个用户启用的文档。
- `idx_knowledge_updated_time (updated_time)`：适合按更新时间排序。

但它们不能解决“在 `title/content/tags` 里按关键词找相关文档”的问题。普通 BTree 索引不适合 `TEXT` 正文里的包含匹配，也不能支撑 `content.contains(...)` 这种 Java 侧全文扫描。

所以结论是：原来不是完全没有索引，而是没有能支持 RAG 关键词内容召回的索引。

## 为什么选择 MySQL FULLTEXT

项目运行约束是 4GB 单机服务器。直接引入 Elasticsearch/OpenSearch 会增加：

- 常驻内存压力。
- 部署和运维复杂度。
- 索引同步链路。
- 故障排查成本。

当前知识库规模还没大到必须上独立搜索引擎，所以本轮选择 MySQL FULLTEXT 作为轻量增强：

- 不新增中间件。
- 直接复用现有 MySQL。
- 能把“候选粗筛”下推到数据库。
- 仍保留原有 Java 关键词评分逻辑，降低行为变化风险。

这次目标不是把关键词排序完全交给 MySQL，而是让 MySQL 先筛出一批候选文档，再交给原来的 `scoreDocument(...)` 做可解释评分。

## 具体怎么加索引

新增迁移文件：

`chatbot-service/src/main/resources/db/migration/V8__add_knowledge_fulltext_index.sql`

```sql
ALTER TABLE knowledge_document
    ADD FULLTEXT INDEX ft_knowledge_title_content_tags (title, content, tags);
```

这个索引覆盖三列：

- `title`：标题通常最能表达文档主题。
- `content`：正文是主要检索内容。
- `tags`：标签是人工或系统补充的召回信号。

索引命名为 `ft_knowledge_title_content_tags`，`ft` 表示 fulltext。

## 查询代码怎么改

### 1. Mapper 增加 FULLTEXT 查询

文件：

`chatbot-service/src/main/java/com/example/chatbot/mapper/KnowledgeDocumentMapper.java`

新增方法：

```java
@Select("""
        SELECT *
        FROM knowledge_document
        WHERE user_id = #{userId}
          AND enabled = 1
          AND MATCH(title, content, tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE)
        ORDER BY updated_time DESC, id DESC
        LIMIT #{limit}
        """)
List<KnowledgeDocument> searchFulltextCandidates(@Param("userId") Long userId,
                                                 @Param("query") String query,
                                                 @Param("limit") int limit);
```

这里做了三件事：

- 仍然保留用户隔离：`user_id = #{userId}`。
- 仍然只查启用文档：`enabled = 1`。
- 用 `MATCH(title, content, tags) AGAINST (...)` 走 FULLTEXT 内容检索。

`LIMIT #{limit}` 是为了控制返回候选数量，避免一次把大量正文拉回应用层。

### 2. HybridSearchService 先取候选，再做 Java 评分

文件：

`chatbot-service/src/main/java/com/example/chatbot/rag/HybridSearchService.java`

新增候选上限：

```java
private static final int KEYWORD_CANDIDATE_LIMIT = 200;
```

关键词检索入口从“直接查全部文档”改为“加载候选文档”：

```java
private List<HybridCandidate> searchKeyword(Long userId, String query, int topK) {
    List<KnowledgeDocument> documents = loadKeywordCandidates(userId, query);
    ...
}
```

候选加载逻辑：

```java
private List<KnowledgeDocument> loadKeywordCandidates(Long userId, String query) {
    try {
        List<KnowledgeDocument> candidates = knowledgeDocumentMapper.searchFulltextCandidates(
                userId, query, KEYWORD_CANDIDATE_LIMIT);
        if (!candidates.isEmpty()) {
            return candidates;
        }
    } catch (Exception e) {
        log.warn("[HybridRAG] fulltext candidate search failed, falling back to limited scan: {}", e.getMessage());
    }
    return knowledgeDocumentMapper.selectList(
            new LambdaQueryWrapper<KnowledgeDocument>()
                    .eq(KnowledgeDocument::getUserId, userId)
                    .eq(KnowledgeDocument::getEnabled, true)
                    .orderByDesc(KnowledgeDocument::getUpdatedTime)
                    .orderByDesc(KnowledgeDocument::getId)
                    .last("LIMIT " + KEYWORD_CANDIDATE_LIMIT));
}
```

执行顺序是：

1. 优先走 MySQL FULLTEXT，最多取 200 条候选。
2. 如果 FULLTEXT 查询异常，记录 warning。
3. 如果 FULLTEXT 没结果或异常，则降级为按更新时间取最近 200 条。
4. 后续仍然使用原来的 `scoreDocument(...)` 精排。

这样做的好处是，数据库先把候选集合从“用户全部文档”缩小到“最多 200 条候选”，应用层评分逻辑不需要大改。

## 优化前后的链路对比

### 优化前

```text
用户问题
  -> HybridSearchService.searchKeyword
  -> MyBatis selectList(user_id, enabled)
  -> 拉回该用户全部启用文档
  -> Java 遍历所有 title/content/tags
  -> scoreDocument 打分
  -> HybridRanker 融合排序
```

问题是：文档越多，每次聊天扫描越多正文。

### 优化后

```text
用户问题
  -> HybridSearchService.searchKeyword
  -> loadKeywordCandidates
  -> MySQL FULLTEXT 粗筛 title/content/tags
  -> 最多返回 200 条候选
  -> Java 对候选做原有关键词评分
  -> HybridRanker 融合排序
```

如果 FULLTEXT 不可用：

```text
FULLTEXT 异常或无结果
  -> fallback
  -> 最近更新的 200 条启用文档
  -> Java 关键词评分
```

这个 fallback 保证数据库迁移、MySQL 版本、FULLTEXT 分词效果不理想时，聊天主链路仍能工作。

## 为什么不是只加普通组合索引

`idx_knowledge_user_enabled (user_id, enabled)` 已经能优化过滤条件，但不能优化正文内容匹配。

例如下面这种逻辑：

```java
content.contains(term)
```

或者 SQL 中的：

```sql
content LIKE '%退款%'
```

普通 BTree 索引基本无法利用，因为前置通配符和 TEXT 内部匹配不能按索引有序查找。

所以如果目标是“从正文中找关键词相关文档”，需要的是：

- MySQL FULLTEXT；
- MySQL ngram FULLTEXT；
- 外部搜索引擎；
- 或 embedding/vector search。

本轮选的是成本最低的 MySQL FULLTEXT。

## 为什么还保留 Java 评分

MySQL FULLTEXT 只负责候选粗筛，不直接决定最终进入 prompt 的排序。原因是当前项目的关键词评分已经有业务规则：

- 技术词命中标题加更高分。
- 中文短语命中标题加更高分。
- bigram 只作为兜底。
- 有最长匹配抑制，避免短词重复加分。

这些规则都在 `HybridSearchService.scoreDocument(...)` 和 `KeywordExtractor` 里。保留它们可以保持检索结果更可解释，也降低改动风险。

## 测试怎么验证

新增测试：

`chatbot-service/src/test/java/com/example/chatbot/rag/HybridSearchServiceCandidateTest.java`

测试 1：FULLTEXT 有结果时，不再走全量 `selectList`。

```java
verify(mapper).searchFulltextCandidates(eq(7L), eq("Redis TTL"), eq(200));
verify(mapper, never()).selectList(any(Wrapper.class));
```

测试 2：FULLTEXT 异常时，可以降级。

```java
when(mapper.searchFulltextCandidates(anyLong(), anyString(), anyInt()))
        .thenThrow(new RuntimeException("fulltext unavailable"));

verify(mapper).selectList(any(Wrapper.class));
```

本轮验证命令：

```bash
mvn -q -pl chatbot-service,file-service "-Dtest=KafkaReliabilityTest,ChatContextServiceTest,HybridSearchServiceCandidateTest,FileServiceAccessTest" test
```

结果：通过。

整体构建也通过：

```bash
mvn -q -DskipTests package
```

## 当前方案的限制

### 1. MySQL 默认 FULLTEXT 对中文不一定理想

当前 SQL 使用：

```sql
MATCH(title, content, tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE)
```

如果 MySQL 没有配置 ngram parser，中文分词效果可能不如英文或技术词。比如“退款政策”这种中文短语，默认 FULLTEXT 可能不一定按预期切分。

所以这次保留 fallback 和 Java 评分很重要：FULLTEXT 不是唯一召回手段。

### 2. 现在没有跑真实 EXPLAIN 和压测

本次判断来自静态代码审查、表结构检查和单元测试验证。还没有在真实 MySQL 数据量下执行：

```sql
EXPLAIN SELECT *
FROM knowledge_document
WHERE user_id = 7
  AND enabled = 1
  AND MATCH(title, content, tags) AGAINST ('Redis TTL' IN NATURAL LANGUAGE MODE)
ORDER BY updated_time DESC, id DESC
LIMIT 200;
```

生产或预发验证时建议补：

- `EXPLAIN` 看是否使用 `ft_knowledge_title_content_tags`。
- 1 千、1 万、5 万文档下的检索耗时。
- RAG 首 token 前耗时变化。
- FULLTEXT 命中为空时 fallback 比例。

### 3. 排序仍然不是最优

当前 FULLTEXT 查询结果按 `updated_time DESC, id DESC` 排序，而不是按 MySQL 相关性分数排序。这样做是为了保持行为稳定，但后续可以改为：

```sql
MATCH(title, content, tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE) AS relevance
ORDER BY relevance DESC, updated_time DESC
```

这样可以让数据库候选更相关，再交给 Java 精排。

### 4. Elasticsearch 仍是后续选项，不是当前推荐

如果后续出现这些情况，可以重新评估 Elasticsearch/OpenSearch：

- 知识库 chunk 数超过 5 万到 10 万。
- 中文复杂查询明显召回不足。
- 需要高亮、字段权重、模糊匹配、拼写纠错。
- 搜索服务可以独立部署，服务器内存不再只有 4GB。

在当前 4GB 单机条件下，MySQL FULLTEXT + PGVector + Java rerank 是更稳妥的渐进方案。

## 总结

这次 RAG 索引优化的本质是：把关键词检索从“应用层全量扫描用户文档”改成“数据库 FULLTEXT 粗筛候选 + 应用层原规则精排”。

关键改动是：

- `V8__add_knowledge_fulltext_index.sql` 增加 `FULLTEXT(title, content, tags)`。
- `KnowledgeDocumentMapper.searchFulltextCandidates(...)` 增加 `MATCH ... AGAINST` 查询。
- `HybridSearchService.loadKeywordCandidates(...)` 优先走 FULLTEXT，失败后降级为最近 200 条有限扫描。
- `HybridSearchServiceCandidateTest` 验证 FULLTEXT 优先和 fallback 行为。

这样既降低了每次聊天的 RAG 扫描成本，又没有引入 Elasticsearch 这类对 4GB 单机不友好的额外中间件。
