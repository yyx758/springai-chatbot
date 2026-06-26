# ES 全链路详细教程

## 一、ES 的作用

你项目有两套检索引擎，分工不同：

| | PGVector (向量检索) | Elasticsearch (关键词检索) |
|--|---|---|
| 擅长 | "用户想表达什么意思" → 语义相似 | "用户说了什么词" → 精确匹配 |
| 例子 | "怎么让程序一直跑" → 匹配到 "watchdog看门狗配置" | "Redisson" → 精确匹配包含 "Redisson" 的文档 |
| 弱点 | 对专有名词、命令名不敏感 | 理解不了同义词和语义 |

两者互补，RRF 融合后取 top-3 喂给 LLM。

### ES 解决的具体问题

- 专有名词精确命中（"Redisson" → 标题含 "Redisson" 的文档）
- 技术术语匹配（"NullPointerException" → 报错文档）
- 部分关键词匹配（用户只说了文档标题里的一个词）
- 向量检索"说的对但找不准"的短板

### 为什么不只用 ES

单独用 ES（纯关键词检索）的问题：

1. **用户说法和文档用词不一样就搜不到**。"怎么让程序一直跑" → 文档写的是 "watchdog 看门狗"，ES 搜不到。
2. **同义词需要人工维护**。"自动续期"、"看门狗"、"watchdog"、"Redisson" 得手动配同义词词典，维护成本高。
3. **理解不了语序和意图**。"锁过期了怎么办" 和 "锁续期配置" 语义相近，但关键词完全不同。

---

## 二、ES 核心概念

### 2.1 基本概念对照

| ES | MySQL | 类比 |
|----|-------|------|
| Index（索引） | Table（表） | 一张表 |
| Document（文档） | Row（行） | 一条记录 |
| Field（字段） | Column（列） | 一个字段 |
| Mapping（映射） | Schema（表结构） | 定义字段类型 |
| Shard（分片） | 分区 | 数据水平拆分 |

你项目里的 `ai_studio_knowledge_v3` 就是一个 ES 索引（相当于 MySQL 的一张表），里面每个 chunk 是一条文档（相当于一行记录）。

### 2.2 倒排索引

MySQL 的 B-tree 索引是"正向"的：给一个值，找到对应行。

ES 用的是倒排索引：给一个词，找到包含这个词的所有文档。

```
原始数据：
  doc1: "Redisson分布式锁"
  doc2: "Redis缓存配置"
  doc3: "分布式事务方案"

倒排索引（分词后建立）：
  "Redis"   → [doc1, doc2]
  "Redisson" → [doc1]
  "分布式"   → [doc1, doc3]
  "锁"      → [doc1]
  "缓存"    → [doc2]
  "配置"    → [doc2]
  "事务"    → [doc3]
  "方案"    → [doc3]
```

用户搜 "Redis" → 直接查倒排索引 → 命中 doc1、doc2，不需要遍历所有文档，O(1) 查找。

---

## 三、ES 字段类型

### 3.1 全部常用字段类型

| 类型 | 用途 | 例子 |
|------|------|------|
| **text** | 全文检索（要分词） | 文档内容、标题、描述 |
| **keyword** | 精确匹配、排序、聚合 | 状态、标签、ID、枚举值 |
| **long / integer / short / byte** | 整数 | 用户ID、数量 |
| **float / double / half_float** | 浮点数 | 价格、分数 |
| **boolean** | 布尔 | enabled: true |
| **date** | 日期 | created_time |
| **object** | JSON 对象 | 嵌套结构 |
| **nested** | JSON 数组对象 | 评论列表（每条评论可独立检索） |
| **geo_point** | 经纬度 | 地理位置 |
| **dense_vector** | 浮点数组（向量） | embedding 向量 |
| **binary** | Base64 二进制 | 文件内容 |
| **ip** | IP 地址 | 192.168.1.1 |
| **completion** | 自动补全 | 搜索建议 |
| **flattened** | 扁平化整个 JSON | 不确定结构的动态字段 |

### 3.2 text 和 keyword 的核心区别

```
text    → 分词后建倒排索引 → 适合 "这段话里有没有这个词"
keyword → 不分词，原样建倒排索引 → 适合 "这个值是不是完全等于xxx"
```

举个例子，值是 `"Redis分布式锁"`：

```
text 字段的倒排索引：
  "redis"   → [doc1]
  "分布式"  → [doc1]
  "锁"      → [doc1]
  → 搜 "分布式" 能命中

keyword 字段的倒排索引：
  "Redis分布式锁" → [doc1]
  → 搜 "分布式" 不命中（因为不等于 "Redis分布式锁"）
  → 搜 "Redis分布式锁" 才命中
```

### 3.3 你项目实际用到的字段

| 字段 | 类型 | 子字段 | 用途 |
|------|------|--------|------|
| id | keyword | - | 文档唯一标识 |
| chunkId | keyword | - | 切片唯一标识 |
| docId | long | - | 原始文档ID |
| userId | long | - | 用户ID，用于过滤 |
| enabled | boolean | - | 是否启用 |
| title | text (standard) | exact (keyword), ngram (text, ngram) | 标题 |
| content | text (standard) | ngram (text, ngram) | 文档内容 |
| tags | text (standard) | exact (keyword), ngram (text, ngram) | 标签 |
| summary | text (standard) | ngram (text, ngram) | 摘要 |
| keywords | text (standard) | - | 关键词 |
| fileName | text (standard) | - | 文件名 |
| createdAt | date | - | 创建时间 |
| updatedAt | date | - | 更新时间 |

---

## 四、分词器（Analyzer）

### 4.1 分词器结构

一个完整的 analyzer 由三部分组成：

```
原始文本 → [Character Filter] → [Tokenizer] → [Token Filter] → 最终词项
```

| 组件 | 作用 | 例子 |
|------|------|------|
| Character Filter | 预处理文本（去HTML标签、替换字符） | `<b>Redis</b>` → `Redis` |
| Tokenizer | 切词（核心） | `Redisson分布式锁` → `Redisson`, `分布式`, `锁` |
| Token Filter | 后处理（转小写、去停用词、同义词） | `Redisson` → `redisson` |

### 4.2 你项目用的三种分词方式

#### ① keyword — 不分词，原样存储

```json
{ "type": "keyword" }
```

什么都不做，把整个字段值当一个整体存进倒排索引。

```
"Redisson分布式锁" → 倒排索引里只有一项：
  "Redisson分布式锁" → [doc1]
```

查询时必须完全一致才命中：

```
搜 "Redisson"           → ❌ 不命中（不等于 "Redisson分布式锁"）
搜 "Redisson分布式锁"    → ✅ 命中
```

#### ② standard 分词器 — ES 默认分词器

```json
{ "type": "text", "analyzer": "standard" }
```

ES 内置的默认分词器，规则：
- 按空格和标点切词
- 转小写
- 去掉大部分标点

```
"Redisson分布式锁配置" → ["redisson分布式锁配置"]
```

问题：中文没有空格，standard 分词器会把整段中文当一个词。

#### ③ ngram 分词器 — 项目自定义的

```json
{
  "tokenizer": {
    "ai_studio_ngram_tokenizer": {
      "type": "ngram",
      "min_gram": 2,
      "max_gram": 4,
      "token_chars": ["letter", "digit"]
    }
  }
}
```

ngram 不管语义，纯机械地按固定窗口滑动切词：

```
"Redisson分布式锁"

2-gram（2个字符一组）：
  Re, ed, di, is, ss, so, on, n分, 分布, 布式, 式锁

3-gram：
  Red, edi, dis, iss, sso, son, on分, n分布, 分布式, 布式锁

4-gram：
  Redi, edis, diss, issu, sson, son分, on分布, n分布式, 分布式锁
```

`min_gram=2, max_gram=4` 意味着每个位置都会生成 2字、3字、4字 的片段。

`token_chars: ["letter", "digit"]` 意味着只对字母和数字做 ngram，标点符号作为分隔符。

**ngram 的好处**：用户只搜 "Redisson" 这一个词，也能匹配到，因为 ngram 片段有大量重叠。

**ngram 的坏处**：索引体积膨胀（一个字段生成了 2+3+4 倍的词项），占用磁盘和内存。

### 4.3 三种分词方式对比

用 `title = "Redisson分布式锁"` 举例：

| 索引类型 | 存储的词项 | 搜 "Redisson" | 搜 "Redisson分布式锁" | 搜 "分布式" |
|---------|-----------|:---:|:---:|:---:|
| keyword (exact) | `["Redisson分布式锁"]` | ❌ | ✅ | ❌ |
| standard (text) | `["redisson分布式锁"]` | ❌ | ❌（小写不匹配） | ❌ |
| ngram | `["Re","ed","di",...,"分布式","布式锁",...]` | ✅ | ✅ | ✅ |

### 4.4 项目自定义 vs ES 内置

| 组件 | 项目自定义还是 ES 内置 |
|------|----------------------|
| `keyword` 字段类型 | ES 内置 |
| `text` 字段类型 | ES 内置 |
| `standard` 分词器 | ES 内置 |
| `ngram` tokenizer | ES 内置 |
| `ai_studio_ngram_tokenizer` | 项目自定义（基于内置 ngram，配了参数） |
| `ai_studio_ngram` analyzer | 项目自定义（包装了上面的 tokenizer + lowercase filter） |

---

## 五、ES 索引结构设计

### 5.1 索引名

```
ai_studio_knowledge_v3   ← 相当于 MySQL 的表名
```

### 5.2 自定义分词器定义

```json
{
  "settings": {
    "index.max_ngram_diff": 2,
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

### 5.3 字段映射设计

每个 text 字段实际上建了多种索引：

```
title 字段
├── title          (text, standard分词)    → 短语匹配、分词匹配
├── title.exact    (keyword, 不分词)       → 精确匹配
└── title.ngram    (text, ngram分词)       → 模糊匹配

content 字段
├── content        (text, standard分词)
└── content.ngram  (text, ngram分词)

tags 字段
├── tags           (text, standard分词)
├── tags.exact     (keyword, 不分词)
└── tags.ngram     (text, ngram分词)
```

### 5.4 完整 Mapping

```json
{
  "mappings": {
    "properties": {
      "id":          { "type": "keyword" },
      "chunkId":     { "type": "keyword" },
      "chunk_id":    { "type": "keyword" },
      "docId":       { "type": "long" },
      "document_id": { "type": "long" },
      "userId":      { "type": "long" },
      "user_id":     { "type": "long" },
      "enabled":     { "type": "boolean" },
      "createdAt":   { "type": "date" },
      "updatedAt":   { "type": "date" },
      "updated_time": { "type": "date" },

      "title": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "exact": { "type": "keyword", "ignore_above": 256 },
          "ngram": { "type": "text", "analyzer": "ai_studio_ngram", "search_analyzer": "standard" }
        }
      },
      "content": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "ngram": { "type": "text", "analyzer": "ai_studio_ngram", "search_analyzer": "standard" }
        }
      },
      "tags": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "exact": { "type": "keyword", "ignore_above": 256 },
          "ngram": { "type": "text", "analyzer": "ai_studio_ngram", "search_analyzer": "standard" }
        }
      },
      "summary": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "ngram": { "type": "text", "analyzer": "ai_studio_ngram", "search_analyzer": "standard" }
        }
      },
      "keywords": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "exact": { "type": "keyword", "ignore_above": 256 },
          "ngram": { "type": "text", "analyzer": "ai_studio_ngram", "search_analyzer": "standard" }
        }
      },
      "fileName": {
        "type": "text",
        "analyzer": "standard",
        "fields": {
          "exact": { "type": "keyword", "ignore_above": 256 }
        }
      }
    }
  }
}
```

### 5.5 子字段命名

`exact` 和 `ngram` 都是项目自己起的名字，不是 ES 的固定语法。

```java
"fields", Map.of(
    "exact", Map.of("type", "keyword"),      // ← "exact" 是自定义名字
    "ngram", Map.of("type", "text", "analyzer", "ai_studio_ngram")  // ← "ngram" 也是自定义名字
)
```

完全可以换成别的名字，ES 只认 `"type": "keyword"` 和 `"analyzer": "ai_studio_ngram"`，不关心字段叫什么。项目起名 `exact` 和 `ngram` 只是为了代码可读性。

查询时用点号访问子字段：

```json
{ "term": { "title.exact": "Redisson分布式锁" } }   // 查 keyword 子字段
{ "match": { "title.ngram": "Redisson" } }           // 查 ngram 子字段
```

---

## 六、写入链路

### 6.1 触发流程

```
用户创建知识文档（保存到 MySQL）
    ↓
Kafka 发送 KNOWLEDGE_CREATED 事件
    ↓
KnowledgeEventConsumer 消费事件
    ↓
调用 ElasticsearchRagService.indexDocument(userId, documentId)
```

### 6.2 写入过程

```java
// ElasticsearchRagService.java

public void indexDocument(Long userId, Long documentId) {
    // 1. 从 MySQL 加载文档
    KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);

    // 2. 切片
    List<DocumentChunk> chunks = documentChunker.chunk(document.getContent());

    // 3. 先删旧数据（防止重复）
    deleteDocument(userId, documentId);

    // 4. 每个 chunk 写入 ES
    for (DocumentChunk chunk : chunks) {
        String chunkId = document.getId() + "_" + chunk.index();  // "123_0"
        Map<String, Object> payload = Map.of(
            "id", chunkId,
            "chunkId", chunkId,
            "docId", document.getId(),
            "userId", document.getUserId(),
            "title", document.getTitle(),
            "content", chunk.content(),    // 切片后的内容
            "tags", document.getTags(),
            "enabled", true
        );
        // PUT /ai_studio_knowledge_v3/_doc/123_0
        restTemplate.exchange(documentUrl(chunkId), HttpMethod.PUT,
            new HttpEntity<>(payload, headers()), String.class);
    }
}
```

每个 chunk 是 ES 里的一条独立文档，可以被单独检索到。

### 6.3 发送的 HTTP 请求

```
PUT http://es:9200/ai_studio_knowledge_v3/_doc/123_0
Content-Type: application/json
Authorization: ApiKey xxx

{
  "id": "123_0",
  "chunkId": "123_0",
  "docId": 123,
  "userId": 1,
  "title": "Redisson分布式锁配置",
  "content": "在Spring Boot中使用Redisson实现分布式锁...",
  "tags": "Redis,分布式锁",
  "enabled": true
}
```

ES 收到后自动对每个 text 字段分词、建倒排索引。

### 6.4 删除文档

```java
public void deleteDocument(Long userId, Long documentId) {
    Map<String, Object> payload = Map.of(
        "query", Map.of(
            "bool", Map.of(
                "filter", List.of(
                    Map.of("term", Map.of("user_id", userId)),
                    Map.of("term", Map.of("document_id", documentId))
                )
            )
        )
    );
    // POST /ai_studio_knowledge_v3/_delete_by_query
    restTemplate.exchange(indexUrl() + "/_delete_by_query", HttpMethod.POST,
        new HttpEntity<>(payload, headers()), String.class);
}
```

用 `_delete_by_query` 按条件批量删除该文档的所有 chunk。

---

## 七、查询链路

### 7.1 触发流程

```
用户提问 "Redisson怎么配置"
    ↓
HybridSearchService.search()
    ↓
并行执行：
  ├── VectorRagService（向量检索）
  └── ElasticsearchKeywordSearchService.search()（关键词检索）
```

### 7.2 查询预处理（在调用 ES 之前）

```java
// HybridSearchService.java

// 第1步：去噪（Java 正则规则，无 LLM）
String rewritten = queryRewriteService.rewriteForRetrieval(query);
// "请问Redisson怎么配置呢" → "Redisson配置"

// 第2步：同义词扩展（查 YAML 表，无 LLM）
EnhancedQuery enhanced = queryEnhancer.enhance(rewritten);
// "Redisson配置" → "Redisson配置 Redisson"

// 第3步：意图分类（正则匹配，无 LLM）
QueryIntent intent = queryIntentAnalyzer.analyze(enhanced.enhanced());
// → EXACT_KEYWORD，vectorWeight=0.8, keywordWeight=1.2
```

整个 RAG 检索链路都不涉及 LLM，LLM 只在最后一步参与：拿到检索结果后，生成自然语言回答。

### 7.3 构建查询 JSON

```java
// ElasticsearchKeywordSearchService.java

Map<String, Object> searchPayload(Long userId, String query, int limit) {

    // ─── 构建 should 列表（7个查询条件）───
    List<Object> should = new ArrayList<>();

    // ① term：精确匹配 title.exact（keyword，不分词）
    should.add(Map.of("term", Map.of("title.exact",
        Map.of("value", query, "boost", 10.0))));
    // 生成 → {"term":{"title.exact":{"value":"Redisson","boost":10.0}}}

    // ② term：精确匹配 tags.exact
    should.add(Map.of("term", Map.of("tags.exact",
        Map.of("value", query, "boost", 5.0))));

    // ③ match_phrase：短语匹配 title（standard 分词后连续出现）
    should.add(Map.of("match_phrase", Map.of("title",
        Map.of("query", query, "boost", 8.0))));

    // ④ match_phrase：短语匹配 tags
    should.add(Map.of("match_phrase", Map.of("tags",
        Map.of("query", query, "boost", 4.0))));

    // ⑤ match_phrase：短语匹配 content
    should.add(Map.of("match_phrase", Map.of("content",
        Map.of("query", query, "boost", 2.0))));

    // ⑥ multi_match：多字段 standard 分词匹配
    should.add(Map.of("multi_match", Map.of(
        "query", query,
        "fields", List.of("title^5", "keywords^4", "tags^3", "summary^2"),
        "type", "best_fields",
        "operator", "or",
        "minimum_should_match", "60%"
    )));

    // ⑦ multi_match：多字段 ngram 模糊匹配
    should.add(Map.of("multi_match", Map.of(
        "query", query,
        "fields", List.of("title.ngram^4", "keywords.ngram^3", "tags.ngram^2",
                          "summary.ngram^1.5", "content.ngram"),
        "type", "best_fields",
        "operator", "or",
        "minimum_should_match", "75%"
    )));

    // ─── 组装完整查询体 ───
    return Map.of(
        "size", limit,                     // ES 内置：返回几条
        "query", Map.of(                   // ES 固定字段
            "bool", Map.of(                // ES 固定字段：布尔组合
                "filter", List.of(         // ES 固定字段：硬性过滤，不算分
                    Map.of("term", Map.of("userId", userId)),   // 只查该用户
                    Map.of("term", Map.of("enabled", true))     // 只查启用的
                ),
                "should", should,          // ES 固定字段：至少命中一个
                "minimum_should_match", 1  // ES 内置：至少命中1个 should 条件
            )
        ),
        "highlight", Map.of(              // ES 内置：返回高亮片段
            "pre_tags", List.of(""),       // 高亮前缀（空=不加标签）
            "post_tags", List.of(""),      // 高亮后缀
            "fields", Map.of(
                "content", Map.of("fragment_size", 180, "number_of_fragments", 1),
                "title", Map.of("number_of_fragments", 0),
                "tags", Map.of("number_of_fragments", 0)
            )
        )
    );
}
```

### 7.4 ES 查询语法说明

#### bool 查询

`bool` 是 ES 的布尔组合查询：

```json
{
  "bool": {
    "must": [...],      // 必须全部命中（AND）
    "should": [...],    // 至少命中一个（OR），命中越多分越高
    "must_not": [...]   // 必须全不命中（NOT）
    "filter": [...]     // 必须命中，但不算分（用于过滤）
  }
}
```

#### 三种查询类型

| 查询类型 | ES 内置 | 分词吗 | 查几个字段 | 匹配要求 |
|---------|---------|--------|-----------|---------|
| term | ✅ | 不分词，原样比 | 1个 | 完全一致 |
| match_phrase | ✅ | 分词后匹配 | 1个 | 词连续出现 |
| multi_match | ✅ | 分词后匹配 | 多个 | 取决于 type 参数 |

**term** — 精确匹配，一个字都不能差：

```json
{ "term": { "title.exact": { "value": "Redisson分布式锁配置指南" } } }
```

**match_phrase** — 短语匹配，分词后必须连续出现：

```json
{ "match_phrase": { "title": { "query": "Redisson分布式锁" } } }
```

ES 处理过程：
1. 对文档分词（standard）：title → ["redisson分布式锁配置指南"]
2. 对查询分词（standard）："Redisson分布式锁" → ["redisson分布式锁"]
3. 检查：["redisson分布式锁"] 在文档分词结果里是不是连续出现？→ 是 → 命中

**multi_match** — 多字段匹配，查多个字段取最高分：

```json
{ "multi_match": { "query": "Redisson", "fields": ["title^5", "tags^3", "content"], "type": "best_fields" } }
```

`^` 是 ES 的权重语法，`title^5` 表示 title 字段的分数乘以 5。

`best_fields` 策略：每个字段独立算分，取最高分那个字段的分数。

#### 参数说明

| 参数 | ES 内置 | 含义 |
|------|---------|------|
| `query` | ✅ | 查询文本 |
| `value` | ✅ | term 查询的匹配值 |
| `boost` | ✅ | 命中后分数乘以这个系数 |
| `fields` | ✅ | multi_match 查哪些字段 |
| `type` | ✅ | multi_match 的匹配策略 |
| `operator` | ✅ | 词之间的逻辑关系（or / and） |
| `minimum_should_match` | ✅ | 最少命中多少比例的词 |
| `size` | ✅ | 返回几条结果 |

### 7.5 发送 HTTP 请求

```java
ResponseEntity<JsonNode> response = restTemplate.exchange(
    indexUrl() + "/_search",                        // POST /ai_studio_knowledge_v3/_search
    HttpMethod.POST,
    new HttpEntity<>(payload, headers()),            // body = Map（自动序列化成 JSON）
    JsonNode.class                                   // 返回值解析成 JSON 树
);
```

Spring 的 `RestTemplate` 会自动把 `Map` 对象序列化成 JSON 字符串，加上 `Content-Type: application/json` 请求头，发给 ES。

发给 ES 的完整 HTTP 请求：

```
POST http://es:9200/ai_studio_knowledge_v3/_search
Content-Type: application/json
Authorization: ApiKey xxx

{
  "size": 50,
  "query": {
    "bool": {
      "filter": [
        { "term": { "userId": 1 } },
        { "term": { "enabled": true } }
      ],
      "should": [
        { "term":        { "title.exact":  { "value": "Redisson", "boost": 10.0 } } },
        { "term":        { "tags.exact":   { "value": "Redisson", "boost": 5.0 } } },
        { "match_phrase": { "title":        { "query": "Redisson", "boost": 8.0 } } },
        { "match_phrase": { "tags":         { "query": "Redisson", "boost": 4.0 } } },
        { "match_phrase": { "content":      { "query": "Redisson", "boost": 2.0 } } },
        { "multi_match":  { "query": "Redisson", "fields": ["title^5","keywords^4","tags^3","summary^2"],
                            "type":"best_fields","operator":"or","minimum_should_match":"60%" } },
        { "multi_match":  { "query": "Redisson", "fields": ["title.ngram^4","tags.ngram^2","content.ngram"],
                            "type":"best_fields","operator":"or","minimum_should_match":"75%" } }
      ],
      "minimum_should_match": 1
    }
  },
  "highlight": {
    "pre_tags": [""], "post_tags": [""],
    "fields": {
      "content": { "fragment_size": 180, "number_of_fragments": 1 },
      "title": { "number_of_fragments": 0 },
      "tags": { "number_of_fragments": 0 }
    }
  }
}
```

### 7.6 ES 返回结果

ES 返回的 JSON：

```json
{
  "hits": {
    "total": { "value": 3 },
    "hits": [
      {
        "_id": "123_0",
        "_score": 27.0,
        "_source": {
          "id": "123_0",
          "chunkId": "123_0",
          "docId": 123,
          "userId": 1,
          "title": "Redisson分布式锁配置",
          "content": "在Spring Boot中使用Redisson实现分布式锁...",
          "tags": "Redis,分布式锁",
          "enabled": true
        },
        "highlight": {
          "content": ["在Spring Boot中使用 Redisson 实现分布式锁..."]
        }
      },
      {
        "_id": "456_1",
        "_score": 12.0,
        "_source": {
          "id": "456_1",
          "chunkId": "456_1",
          "docId": 456,
          "title": "Redis缓存配置",
          "content": "配置Redis连接池...",
          "tags": "Redis,缓存",
          "enabled": true
        },
        "highlight": {
          "content": ["配置Redis连接池..."]
        }
      }
    ]
  }
}
```

| 返回字段 | 含义 |
|---------|------|
| `hits.total.value` | 总共命中几条 |
| `hits.hits` | 命中结果数组 |
| `_id` | 文档 ID（即 chunkId） |
| `_score` | ES 计算的相关性分数（命中越多层、权重越高，分数越高） |
| `_source` | 文档原始数据（写入时存的那些字段） |
| `highlight.content` | 高亮片段（匹配的词被标记出来） |

### 7.7 解析返回结果

```java
// ElasticsearchKeywordSearchService.java

private List<SearchResult> toResults(JsonNode root) {
    JsonNode hits = root.path("hits").path("hits");  // 取 hits.hits 数组
    List<SearchResult> results = new ArrayList<>();
    int rank = 1;
    for (JsonNode hit : hits) {
        JsonNode source = hit.path("_source");
        results.add(SearchResult.builder()
            .chunkId(source.path("chunkId").asText())       // "123_0"
            .docId(source.path("docId").asLong())            // 123
            .title(source.path("title").asText())            // "Redisson分布式锁配置"
            .content(snippet(hit, source))                   // 高亮片段或截断内容
            .score(hit.path("_score").asDouble())            // 27.0
            .rank(rank++)                                    // 1, 2, 3...
            .source("elasticsearch")                         // 来源标记
            .build());
    }
    return results;
}

private String snippet(JsonNode hit, JsonNode source) {
    JsonNode highlight = hit.path("highlight");
    // 优先用高亮片段
    if (highlight.has("content") && highlight.get("content").size() > 0) {
        return highlight.get("content").get(0).asText();
    }
    // 没有高亮就截断
    String content = source.path("content").asText();
    return content.length() > 180 ? content.substring(0, 180) + "..." : content;
}
```

---

## 八、查询层次打分机制

### 8.1 四层查询结构

| 层 | 查询类型 | 查的字段 | 分词方式 | 权重 | 匹配要求 |
|----|---------|---------|---------|------|---------|
| ① | term | title.exact | 不分词 | 10 | 完全一致 |
| ② | term | tags.exact | 不分词 | 5 | 完全一致 |
| ③ | match_phrase | title | standard | 8 | 词连续出现 |
| ④ | match_phrase | tags | standard | 4 | 词连续出现 |
| ⑤ | match_phrase | content | standard | 2 | 词连续出现 |
| ⑥ | multi_match | title/keywords/tags/summary | standard | 5/4/3/2 | 至少60%词命中 |
| ⑦ | multi_match | title.ngram/tags.ngram/content.ngram | ngram | 4/2/1 | ngram片段重叠 |

### 8.2 打分示例

用户输入 "Redisson"：

```
① term(title.exact)        boost=10  → ❌ "Redisson" ≠ "Redisson分布式锁配置指南"
② term(tags.exact)         boost=5   → ❌
③ match_phrase(title)       boost=8   → ✅ +8
④ match_phrase(tags)        boost=4   → ❌
⑤ match_phrase(content)     boost=2   → ❌
⑥ multi_match(standard)    title^5   → ✅ +5
⑦ multi_match(ngram)       title.ngram^4 → ✅ +4
                                        ─────
                                         = 17分
```

用户输入 "Redisson分布式锁配置指南"（完全一致）：

```
① term(title.exact)        boost=10  → ✅ +10
③ match_phrase(title)       boost=8   → ✅ +8
⑥ multi_match(standard)    title^5   → ✅ +5
⑦ multi_match(ngram)       title.ngram^4 → ✅ +4
                                        ─────
                                         = 27分 ← 最高，排第一
```

用户输入 "Docker部署"（完全不相关）：

```
所有层都不命中 → 不返回这条结果
```

### 8.3 bool.should 规则

```json
{
  "bool": {
    "should": [...],
    "minimum_should_match": 1
  }
}
```

- `minimum_should_match: 1` → 7 层里至少命中一层才会返回这条文档
- 每层命中都会加分，命中越多分越高
- 不同层的 `boost` 权重不同，精确匹配加分多，模糊匹配加分少
- ES 内部把所有命中层的分数加权求和，返回按总分排序的结果

---

## 九、命名总结

| 名字 | 自定义还是 ES 内置 | 含义 |
|------|-------------------|------|
| `term` | ES 内置 | 精确匹配查询类型 |
| `match_phrase` | ES 内置 | 短语匹配查询类型 |
| `multi_match` | ES 内置 | 多字段匹配查询类型 |
| `bool` / `should` / `filter` / `must` | ES 内置 | 布尔查询结构 |
| `boost` | ES 内置 | 分数权重参数 |
| `best_fields` | ES 内置 | multi_match 的策略 |
| `minimum_should_match` | ES 内置 | 最少命中比例 |
| `operator` | ES 内置 | 词之间的逻辑关系（or/and） |
| `query` / `value` / `size` / `fields` / `type` | ES 内置 | 查询参数 |
| `highlight` / `pre_tags` / `post_tags` | ES 内置 | 高亮配置 |
| `hits` / `_score` / `_source` / `_id` | ES 内置 | 返回结果字段 |
| `title` | 项目自定义 | 文档标题字段 |
| `content` | 项目自定义 | 文档内容字段 |
| `tags` | 项目自定义 | 文档标签字段 |
| `exact` | 项目自定义 | keyword 子字段名 |
| `ngram` | 项目自定义 | ngram 子字段名 |
| `ai_studio_ngram` | 项目自定义 | 自定义 analyzer 名字 |
| `ai_studio_ngram_tokenizer` | 项目自定义 | 自定义 tokenizer 名字 |
| `keywords` / `summary` / `fileName` | 项目自定义 | 其他文档字段 |
| `ai_studio_knowledge_v3` | 项目自定义 | ES 索引名 |

---

## 十、完整流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                        写入链路                                  │
│                                                                  │
│  用户创建文档 → MySQL → Kafka 事件                               │
│                              ↓                                   │
│                  DocumentChunker.chunk() 切片                    │
│                              ↓                                   │
│                  每个 chunk → PUT _doc/{chunkId}                 │
│                              ↓                                   │
│                  ES 自动分词建倒排索引                            │
│                  title     → standard 分词                       │
│                  title.exact → 不分词（keyword）                 │
│                  title.ngram → 2-4字滑动窗口                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                        查询链路                                  │
│                                                                  │
│  用户提问                                                        │
│    ↓                                                             │
│  QueryRewriteService 去噪（Java 正则，无 LLM）                   │
│    ↓                                                             │
│  QueryEnhancer 同义词扩展（YAML 查表，无 LLM）                    │
│    ↓                                                             │
│  QueryIntentAnalyzer 意图分类（正则匹配，无 LLM）                 │
│    ↓                                                             │
│  并行执行：                                                      │
│  ├── PGVector 向量检索                                           │
│  └── ES 关键词检索                                               │
│       ├── 构建 7 层 should 查询（Map.of 嵌套 → JSON）             │
│       ├── POST /_search 发给 ES                                  │
│       ├── ES 在倒排索引里匹配，计算分数，返回 top-K + 高亮        │
│       └── 解析 JSON → List<SearchResult>                         │
│    ↓                                                             │
│  RRF 融合两路结果 → 规则 rerank → top-3                         │
│    ↓                                                             │
│  拼入 prompt → LLM 生成回答                                      │
└─────────────────────────────────────────────────────────────────┘
```
