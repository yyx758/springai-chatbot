# 混合 RAG 检索增强生成指南

## 概述

AI Studio 默认启用 **混合 RAG（Hybrid Retrieval-Augmented Generation）**，结合关键词匹配和语义向量检索两种方式，兼顾精确匹配与语义理解。

## 架构设计

```
用户查询
    │
    ▼
┌─────────────────────────────────────┐
│         RagService.retrieveReferences()         │
│         模式: hybrid (默认)                      │
└──────────────┬──────────────────────────────────┘
               │
       ┌───────┴───────┐
       │               │
       ▼               ▼
┌──────────────┐ ┌──────────────────┐
│  关键词检索   │ │  向量语义检索     │
│  Keyword     │ │  Vector           │
│              │ │                    │
│ MySQL 全量   │ │ PGVector HNSW    │
│ 加载文档     │ │ 余弦相似度        │
│ 2-gram 分词  │ │ DashScope        │
│ String.contains() │ text-embedding-v4 │
│ 评分: 标题40/正文30/标签20 │ │
└──────┬───────┘ └────────┬─────────┘
       │                  │
       ▼                  ▼
┌─────────────────────────────────────┐
│         结果融合 + 去重 + 排序                     │
│         按 score 降序, 取 top-K                   │
└─────────────────────────────────────┘
```

## 两种检索模式对比

| 维度 | 关键词检索 | 向量语义检索 |
|------|-----------|-------------|
| 实现 | Java 内存, `String.contains()` | PGVector + DashScope Embedding API |
| 匹配方式 | 字面文本匹配 | 语义相似度 (余弦) |
| 优势 | 精确匹配快, 零外部依赖 | 理解同义词, 处理模糊查询 |
| 劣势 | 无法处理同义词 | 依赖外部 API, 有网络延迟 |
| 评分 | 标题40 + 正文30 + 标签20 + 2-gram加权 | cosine similarity × 100 |
| 数据源 | MySQL 全量加载 | PGVector 向量表 |

## 混合模式工作流程

### 1. 查询阶段

```java
// RagService.java
if ("hybrid".equals(mode)) {
    // 并行执行两种检索
    List<RagReference> keywordResults = retrieveKeywordReferences(userId, query, topK);
    List<RagReference> vectorResults = vectorRagService.retrieve(userId, query, topK);
    return mergeReferences(vectorResults, keywordResults, topK);
}
```

### 2. 关键词检索

```java
// 从 MySQL 加载用户所有启用的文档
List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
    new LambdaQueryWrapper<KnowledgeDocument>()
        .eq(KnowledgeDocument::getUserId, userId)
        .eq(KnowledgeDocument::getEnabled, true)
);

// 对每篇文档计算关键词评分
for (KnowledgeDocument document : documents) {
    int score = calculateScore(document, query, keywords);
    // 评分规则:
    // - 标题包含整个查询: +40
    // - 正文包含整个查询: +30
    // - 标签包含整个查询: +20
    // - 2-gram 词命中标题: +8~14
    // - 2-gram 词命中正文: +4~8
    // - 2-gram 词命中标签: +5
}
```

### 3. 向量语义检索

```java
// VectorRagService.java
List<Double> embedding = embeddingClient.embed(query);  // 调用 DashScope API
String vector = pgVectorClient.vectorLiteral(embedding);
return pgVectorClient.search(userId, vector, topK, threshold);

// PGVector SQL:
// SELECT document_id, title, content,
//        1 - (embedding <=> ?::vector) AS score
// FROM ai_studio_knowledge_vectors
// WHERE user_id = ?
//   AND (1 - (embedding <=> ?::vector)) >= 0.55
// ORDER BY embedding <=> ?::vector
// LIMIT 5
```

### 4. 结果融合

```java
// RagService.mergeReferences()
Map<Long, RagReference> merged = new LinkedHashMap<>();

// 向量结果优先放入
for (RagReference ref : vectorResults) {
    merged.put(ref.getDocumentId(), ref);
}

// 关键词结果合并（同文档取高分）
for (RagReference ref : keywordResults) {
    merged.merge(ref.getDocumentId(), ref, (existing, incoming) ->
        incoming.getScore() > existing.getScore() ? incoming : existing
    );
}

// 按分数降序排列，取 top-K
return merged.values().stream()
    .sorted(comparingInt(r -> r.getScore()).reversed())
    .limit(topK)
    .collect(toList());
```

## 配置说明

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `APP_RAG_MODE` | `hybrid` | RAG 模式: keyword / vector / hybrid |
| `APP_RAG_VECTOR_ENABLED` | `true` | 向量检索总开关 |
| `APP_RAG_VECTOR_JDBC_URL` | `jdbc:postgresql://pgvector:5432/chatbot_vector` | PGVector 连接地址 |
| `APP_RAG_VECTOR_USERNAME` | `postgres` | PGVector 用户名 |
| `APP_RAG_VECTOR_PASSWORD` | `postgres` | PGVector 密码 |
| `APP_RAG_VECTOR_DIMENSIONS` | `1024` | 向量维度 |
| `APP_RAG_VECTOR_TOP_K` | `5` | 向量检索默认返回数量 |
| `APP_RAG_VECTOR_SIMILARITY_THRESHOLD` | `0.55` | 相似度阈值 |
| `APP_RAG_EMBEDDING_PROVIDER` | `openai-compatible` | Embedding 提供商 |
| `APP_RAG_EMBEDDING_MODEL` | `text-embedding-v4` | Embedding 模型 |
| `APP_RAG_EMBEDDING_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` | Embedding API 地址 |
| `APP_RAG_EMBEDDING_API_KEY` | — | DashScope API Key |
| `APP_RAG_EMBEDDING_PATH` | `/v1/embeddings` | Embedding API 路径 |

### application.yml

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
      jdbc-url: ${APP_RAG_VECTOR_JDBC_URL:jdbc:postgresql://pgvector:5432/chatbot_vector}
      username: ${APP_RAG_VECTOR_USERNAME:postgres}
      password: ${APP_RAG_VECTOR_PASSWORD:postgres}
      table-name: ai_studio_knowledge_vectors
      dimensions: ${APP_RAG_VECTOR_DIMENSIONS:1024}
      top-k: ${APP_RAG_VECTOR_TOP_K:5}
      similarity-threshold: ${APP_RAG_VECTOR_SIMILARITY_THRESHOLD:0.55}
    embedding:
      provider: ${APP_RAG_EMBEDDING_PROVIDER:openai-compatible}
      model: ${APP_RAG_EMBEDDING_MODEL:text-embedding-v4}
      base-url: ${APP_RAG_EMBEDDING_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}
      embeddings-path: ${APP_RAG_EMBEDDING_PATH:/v1/embeddings}
      api-key: ${APP_RAG_EMBEDDING_API_KEY:}
      encoding-format: float
      timeout-ms: 20000
```

## 向量索引流程

### 文档创建时自动索引

```
文档创建 → Kafka KNOWLEDGE_CREATED 事件
    │
    ▼
KnowledgeEventConsumer 消费事件
    │
    ▼
VectorIndexingService.indexDocument()
    │
    ├── DocumentChunker: 文档切块 (800字/块, 重叠100字)
    │
    ├── EmbeddingClient: 调用 DashScope API 生成向量
    │
    └── PgVectorClient: 写入 PGVector 向量表
```

### 手动重建索引

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

用于从关键词模式切换到混合模式时，为旧文档补建向量索引。

## 降级策略

混合模式内置 fallback 机制：

```
向量检索失败
    │
    ├── fallback-to-keyword = true (默认)
    │   └── 使用关键词检索结果
    │
    └── fallback-to-keyword = false
        └── 返回空结果
```

即使不主动配置 fallback，Hybrid RAG 内部也保留降级：
- Embedding API 超时 → 使用关键词结果
- PGVector 连接失败 → 使用关键词结果
- 向量表不存在 → 使用关键词结果

## 部署步骤

### 本地开发

```bash
# 1. 启动 PGVector
docker compose --profile vector up -d pgvector

# 2. 配置 .env
cp .env.example .env
# 编辑 .env，填入 APP_RAG_EMBEDDING_API_KEY

# 3. 启动所有服务
docker compose up -d

# 4. 为旧文档重建向量索引
curl -X POST http://localhost:9000/api/knowledge/reindex \
  -H "Authorization: Bearer <token>"
```

### 生产部署

```bash
# 1. 启动 PGVector
docker compose -f docker-compose.prod.yml --profile vector up -d pgvector

# 2. 重启 chatbot-service (读取新的 RAG 环境变量)
docker compose -f docker-compose.prod.yml up -d --no-deps chatbot-service

# 3. 验证 embedding 连接
bash scripts/prod-embedding-smoke-test.sh

# 4. 验证 RAG 状态
BASE_URL=http://127.0.0.1 bash scripts/prod-rag-status-check.sh

# 5. 为旧文档重建向量索引
curl -X POST http://127.0.0.1/api/knowledge/reindex \
  -H "Authorization: Bearer <token>"
```

## 回退到关键词模式

```bash
# .env 中修改
APP_RAG_MODE=keyword
APP_RAG_VECTOR_ENABLED=false

# 重启服务
docker compose up -d --build chatbot-service
```

## 故障排查

| 问题 | 排查方法 |
|------|---------|
| 向量检索返回空 | 检查 `index_status` 是否为 `INDEXED` |
| Embedding API 报错 | 运行 `bash scripts/prod-embedding-smoke-test.sh` |
| PGVector 连接失败 | 检查容器状态: `docker ps \| grep pgvector` |
| 相似度太低 | 降低 `APP_RAG_VECTOR_SIMILARITY_THRESHOLD` (如 0.3) |
| 搜索结果不相关 | 重建索引: `POST /api/knowledge/reindex` |
