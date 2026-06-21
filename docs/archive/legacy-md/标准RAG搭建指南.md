# 标准 RAG 搭建指南

> Retrieval-Augmented Generation（检索增强生成）完整流程详解

---

## 目录

- [什么是 RAG](#什么是-rag)
- [完整流程概览](#完整流程概览)
- [第一步：Chunk 分块](#第一步chunk-分块)
- [第二步：Embedding 向量化](#第二步embedding-向量化)
- [第三步：向量数据库](#第三步向量数据库)
- [第四步：写入流程](#第四步写入流程indexing)
- [第五步：查询流程](#第五步查询流程retrieval)
- [第六步：生成回复](#第六步生成回复generation)
- [完整架构图](#完整架构图)
- [Spring AI 集成示例](#spring-ai-集成示例)
- [部署方案对比](#部署方案对比)

---

## 什么是 RAG

大模型的知识来自训练数据，无法回答私有文档中的问题。RAG 的核心思想是：**先从知识库中检索相关内容，再把检索结果拼进 Prompt 喂给大模型**，让模型基于真实资料回答。

```
没有 RAG：用户提问 → 大模型（靠训练数据猜测） → 可能编造答案
有了 RAG：用户提问 → 检索知识库 → 大模型（参考资料 + 问题） → 有据可依的回答
```

---

## 完整流程概览

```
写入阶段（Indexing）：
  文档 → Chunk 分块 → Embedding 向量化 → 存入向量数据库

查询阶段（Retrieval + Generation）：
  用户提问 → Embedding 问题 → 向量相似度搜索 → Top-K 结果 → 拼接 Prompt → 大模型生成
```

---

## 第一步：Chunk 分块

### 为什么不能整篇塞进去？

1. embedding 模型有 token 上限（通常 512~8192 token）
2. 整篇 embedding 会丢失细节，小段 embedding 语义更精确
3. 检索时需要精确定位到具体段落，而不是整篇返回

### 分块策略

```
原始文档（3000字）
  ↓ chunk(chunk_size=500, overlap=50)
Chunk 1: [第1~500字]
Chunk 2: [第450~950字]   ← 与 Chunk1 重叠 50 字，防止语义断裂
Chunk 3: [第900~1400字]
...
```

### 参数选择

| 参数 | 推荐值 | 说明 |
|------|--------|------|
| chunk_size | 200~500 token | 太小丢失上下文，太大不精确 |
| overlap | 10%~20% of chunk_size | 重叠窗口，防止句子被截断 |

### 代码示例

```java
/**
 * 按固定长度分块，带重叠窗口
 * @param text      原始文本
 * @param chunkSize 每块字符数
 * @param overlap   重叠字符数
 */
public List<String> chunk(String text, int chunkSize, int overlap) {
    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
        int end = Math.min(start + chunkSize, text.length());
        chunks.add(text.substring(start, end));
        start += chunkSize - overlap;
    }
    return chunks;
}

// 示例
chunk("这是一篇很长的文档内容...", 500, 50)
// → ["第1段(1~500字)", "第2段(450~950字)", "第3段(900~1400字)", ...]
```

### 进阶分块策略

| 策略 | 说明 | 适用场景 |
|------|------|---------|
| 固定长度 | 按字符/token 数切分 | 通用 |
| 按句子 | 以句号为界切分 | 保持语义完整 |
| 按段落 | 以换行/标题为界切分 | 结构化文档 |
| 递归分割 | 先按段落，超长再按句子，再按字符 | 最常用 |
| Markdown 感知 | 按标题层级切分 | 技术文档 |

---

## 第二步：Embedding 向量化

### 原理

embedding 模型把文本映射到一个高维浮点数组（向量）。语义相近的文本在向量空间中距离更近。

```
"Spring Boot是一个框架"  → embedding → [0.023, -0.156, 0.891, ..., 0.042]（1536维）
"猫"                     → embedding → [0.912, 0.034, -0.221, ..., 0.567]
"狗"                     → embedding → [0.897, 0.041, -0.198, ..., 0.543]
"汽车"                   → embedding → [-0.312, 0.756, 0.102, ..., -0.445]
```

向量空间中的距离：
- "猫" ↔ "狗" = 0.05（很近，都是宠物）
- "猫" ↔ "汽车" = 0.89（很远，完全不同领域）

### 常用 Embedding 模型

| 模型 | 维度 | 价格 | 说明 |
|------|------|------|------|
| OpenAI `text-embedding-3-small` | 1536 | $0.02/1M tokens | 最常用，性价比高 |
| OpenAI `text-embedding-3-large` | 3072 | $0.13/1M tokens | 更精确 |
| BGE-M3 (BAAI) | 1024 | 免费本地部署 | 中文效果最好 |
| Ollama `nomic-embed-text` | 768 | 免费本地部署 | 轻量级，适合入门 |
| Ollama `bge-m3` | 1024 | 免费本地部署 | 中文优化 |

### 本地部署 Embedding（Ollama）

```bash
# 安装 Ollama 后拉取模型
ollama pull nomic-embed-text

# 测试
curl http://localhost:11434/api/embeddings -d '{
  "model": "nomic-embed-text",
  "prompt": "Spring Boot 配置 Redis"
}'
# 返回: {"embedding": [0.023, -0.156, ...]}
```

---

## 第三步：向量数据库

### 主流选择

| 数据库 | 部署方式 | 适合场景 | 特点 |
|--------|---------|---------|------|
| **PGVector** | PostgreSQL 扩展 | 最简单入门 | 已有 PG 的项目直接加扩展 |
| **Milvus** | Docker 独立部署 | 生产级，亿级向量 | 功能最全 |
| **Chroma** | Docker 或 pip | 轻量级原型 | 最简单 |
| **Pinecone** | 云服务 | 零部署 | 按量付费 |
| **Weaviate** | Docker 或云 | 功能丰富 | 支持混合搜索 |
| **Qdrant** | Docker | 高性能 | Rust 实现，速度快 |

### PGVector 部署（推荐入门）

**一行 Docker 启动：**

```bash
docker run -d --name pgvector \
  -p 5432:5432 \
  -e POSTGRES_PASSWORD=123456 \
  pgvector/pgvector:pg16
```

**初始化数据库：**

```sql
-- 启用向量扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建向量表
CREATE TABLE knowledge_chunks (
    id SERIAL PRIMARY KEY,
    doc_id BIGINT NOT NULL,                  -- 关联原始文档 ID
    chunk_text TEXT NOT NULL,                 -- 分块文本
    embedding VECTOR(1536) NOT NULL,          -- 向量，维度取决于模型
    metadata JSONB DEFAULT '{}',              -- 元数据（标题、标签等）
    created_at TIMESTAMP DEFAULT NOW()
);

-- 创建 HNSW 索引（加速检索，必须建）
CREATE INDEX ON knowledge_chunks
USING hnsw (embedding vector_cosine_ops);

-- 可选：按 doc_id 建索引
CREATE INDEX ON knowledge_chunks (doc_id);
```

### Docker 如何与 Spring Boot 项目连接？

很多人疑惑：Docker pull 下来一个向量数据库，项目代码怎么用到它？完整链路如下：

```
Docker 容器（PGVector）
  │
  │ -p 5432:5432  ← 端口映射，宿主机5432 → 容器5432
  ▼
localhost:5432（宿主机可访问的 PostgreSQL 服务）
  │
  │ JDBC 连接  jdbc:postgresql://localhost:5432/postgres
  ▼
Spring DataSource（配置在 application.yml）
  │
  │ Spring AI 自动装配：检测到 PGVector 依赖 + DataSource 配置
  │ → 自动创建 PgVectorStore Bean
  ▼
VectorStore Bean（Spring 容器管理的对象）
  │
  │ @Autowired 注入
  ▼
你的业务代码（vectorStore.add / vectorStore.similaritySearch）
```

**本质就是：Docker 跑了一个数据库服务 → Spring Boot 通过网络端口连上去 → Spring AI 封装了读写 API。** 跟现在连 MySQL（`localhost:3306`）和 Redis（`localhost:6379`）的模式完全一样。

**配置示例：**

```yaml
# application.yml
spring:
  datasource:
    pgvector:                                          # 自定义数据源名称
      url: jdbc:postgresql://localhost:5432/postgres   # 就是连 Docker 暴露的端口
      username: postgres
      password: 123456
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 768
```

**依赖示例：**

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

**Spring Boot 启动时自动完成：**
1. 检测到 classpath 有 `spring-ai-starter-vector-store-pgvector` 依赖
2. 读取 `spring.datasource.pgvector` 配置
3. 通过 JDBC 连接 `localhost:5432`
4. 创建 `PgVectorStore` 对象（实现 `VectorStore` 接口）
5. 注册为 Spring Bean，代码中 `@Autowired` 即可使用

### Milvus 部署（生产级）

```bash
# docker-compose.yml
version: '3.5'
services:
  etcd:
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000

  minio:
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    command: minio server /minio_data

  milvus:
    image: milvusdb/milvus:v2.3.3
    ports:
      - "19530:19530"
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
```

```bash
docker-compose up -d
```

### Chroma 部署（最轻量）

```bash
# 一行启动
docker run -d --name chroma \
  -p 8000:8000 \
  chromadb/chroma
```

```bash
# API 测试
curl http://localhost:8000/api/v1/heartbeat
```

---

## 第四步：写入流程（Indexing）

### 完整流程

```
用户上传文档 "Spring Boot 配置指南.md"
  ↓
1. 读取文档内容
  ↓
2. Chunk 分块
   → ["Spring Boot 配置 Redis 需要在 application.yml...",
      "Lettuce 是 Spring Boot 默认的 Redis 客户端...",
      "连接池配置建议 max-active 设为 8..."]
  ↓
3. Embedding 每个 chunk
   → [[0.023, -0.156, ...], [-0.089, 0.445, ...], [0.167, -0.234, ...]]
  ↓
4. 写入向量数据库
   INSERT INTO knowledge_chunks (doc_id, chunk_text, embedding, metadata)
   VALUES
     (1, 'Spring Boot 配置 Redis...', '[0.023, -0.156, ...]', '{"title":"配置指南"}'),
     (1, 'Lettuce 是 Spring Boot...', '[-0.089, 0.445, ...]', '{"title":"配置指南"}'),
     (1, '连接池配置建议...', '[0.167, -0.234, ...]', '{"title":"配置指南"}');
```

### 代码示例（Spring AI）

```java
@Service
public class VectorIndexingService {

    @Autowired
    private VectorStore vectorStore;

    /**
     * 将文档分块后写入向量数据库
     */
    public void indexDocument(Long docId, String title, String content, String tags) {
        // 1. 分块
        List<String> chunks = chunk(content, 500, 50);

        // 2. 构建 Document 对象（Spring AI 会自动 embedding）
        List<Document> documents = chunks.stream()
            .map(chunkText -> {
                Map<String, Object> metadata = Map.of(
                    "docId", docId,
                    "title", title,
                    "tags", tags != null ? tags : ""
                );
                return new Document(chunkText, metadata);
            })
            .toList();

        // 3. 写入向量数据库（自动 embedding + 存储）
        vectorStore.add(documents);
    }

    /**
     * 删除文档的所有 chunk
     */
    public void deleteDocument(Long docId) {
        vectorStore.delete(
            FilterExpression.builder()
                .eq("docId", docId)
                .build()
        );
    }

    private List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start += chunkSize - overlap;
        }
        return chunks;
    }
}
```

---

## 第五步：查询流程（Retrieval）

### 完整流程

```
用户提问："Spring Boot 怎么配置 Redis？"
  ↓
1. Embedding 问题
   → [0.031, -0.142, 0.887, ...]
  ↓
2. 向量相似度搜索（cosine similarity）
   SELECT chunk_text, metadata,
          1 - (embedding <=> '[0.031, -0.142, ...]') AS score
   FROM knowledge_chunks
   ORDER BY embedding <=> '[0.031, -0.142, ...]'
   LIMIT 3;
  ↓
3. 返回 Top-3 结果
   ┌─────────────────────────────────────────────────────────────┐
   │ score: 0.92  "Spring Boot 配置 Redis 需要在 application.yml..." │
   │ score: 0.85  "Redis 是一个开源的内存数据结构存储..."              │
   │ score: 0.78  "Lettuce 是 Spring Boot 默认的 Redis 客户端..."    │
   └─────────────────────────────────────────────────────────────┘
```

### 相似度算法

| 算法 | 公式 | 特点 | 适用场景 |
|------|------|------|---------|
| **Cosine Similarity** | cos(θ) = A·B / (‖A‖·‖B‖) | 最常用，忽略向量长度 | 文本相似度 |
| Euclidean Distance | ‖A-B‖ | 直觉距离 | 聚类 |
| Dot Product | A·B | 快速，需归一化 | 已归一化向量 |

```
Cosine Similarity 示例：

向量A: [0.1, 0.3, 0.5]
向量B: [0.2, 0.4, 0.4]

cos(θ) = (0.1×0.2 + 0.3×0.4 + 0.5×0.4) / (√(0.01+0.09+0.25) × √(0.04+0.16+0.16))
       = 0.34 / (0.592 × 0.6)
       = 0.34 / 0.355
       = 0.958（非常相似）
```

### 代码示例（Spring AI）

```java
@Service
public class VectorRetrievalService {

    @Autowired
    private VectorStore vectorStore;

    /**
     * 从向量数据库中检索最相关的 chunk
     */
    public List<RagReference> retrieve(String query, int topK) {
        SearchRequest request = SearchRequest.query(query)
            .withTopK(topK)
            .withSimilarityThreshold(0.5);  // 最低相似度阈值

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
            .map(doc -> new RagReference(
                doc.getMetadata().get("title").toString(),
                doc.getText(),
                doc.getScore()
            ))
            .toList();
    }
}
```

---

## 第六步：生成回复（Generation）

### Prompt 拼接

```
System: 你是智能助手，请根据以下参考资料回答用户问题。
        如果参考资料中没有相关信息，请如实说明。

参考资料：
[1] (score:0.92) Spring Boot 配置 Redis 需要在 application.yml 中添加：
    spring.data.redis.host=localhost
    spring.data.redis.port=6379

[2] (score:0.85) Redis 是一个开源的内存数据结构存储，可用作数据库、缓存和消息中间件。

[3] (score:0.78) Lettuce 是 Spring Boot 默认的 Redis 客户端，支持同步和异步操作。

用户问题：Spring Boot 怎么配置 Redis？

AI回复：在 application.yml 中配置即可：

spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: 你的密码

Spring Boot 默认使用 Lettuce 客户端，无需额外引入依赖。
```

### 代码示例

```java
@Service
public class RagService {

    @Autowired
    private VectorRetrievalService retrievalService;

    /**
     * 检索 + 构建 Prompt
     */
    public String buildRagPrompt(String userId, String query, int topK) {
        // 1. 检索
        List<RagReference> refs = retrievalService.retrieve(query, topK);

        if (refs.isEmpty()) {
            return "";  // 无参考资料，走普通对话
        }

        // 2. 拼接 Prompt
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下参考资料回答用户问题：\n\n");

        for (int i = 0; i < refs.size(); i++) {
            RagReference ref = refs.get(i);
            sb.append(String.format("[%d] (score:%.2f) %s\n%s\n\n",
                i + 1, ref.getScore(), ref.getTitle(), ref.getSnippet()));
        }

        return sb.toString();
    }
}
```

---

## 完整架构图

```
                        ┌─────────────────────────────────┐
                        │         写入阶段 (Indexing)       │
                        │                                 │
                        │   文档 → Chunk → Embedding → DB  │
                        └───────────────┬─────────────────┘
                                        │
                                        ▼
┌──────────────────────────────────────────────────────────┐
│                    向量数据库                              │
│              (PGVector / Milvus / Chroma)                 │
│                                                          │
│   ┌─────────────────────────────────────────────────┐    │
│   │  id:1  chunk:"Spring Boot配置..."  vec:[0.023..] │    │
│   │  id:2  chunk:"Lettuce客户端..."    vec:[-0.089..]│    │
│   │  id:3  chunk:"连接池配置..."       vec:[0.167..]  │    │
│   │  ...                                            │    │
│   └─────────────────────────────────────────────────┘    │
└───────────────────────────┬──────────────────────────────┘
                            │
                            │ cosine similarity Top-K
                            ▼
┌──────────────────────────────────────────────────────────┐
│                    查询阶段 (Retrieval)                    │
│                                                          │
│   用户提问 → Embedding → 向量搜索 → Top-3 chunks         │
└───────────────────────────┬──────────────────────────────┘
                            │
                            ▼
┌──────────────────────────────────────────────────────────┐
│                    生成阶段 (Generation)                   │
│                                                          │
│   Prompt = "参考资料：" + chunks + "用户问题：" + query    │
│       ↓                                                  │
│   大模型生成回复                                          │
└──────────────────────────────────────────────────────────┘
```

---

## Spring AI 集成示例

### 1. 添加依赖（pom.xml）

```xml
<!-- PGVector 向量数据库 -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>

<!-- 或者使用 Chroma -->
<!--
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-chroma</artifactId>
</dependency>
-->
```

### 2. 配置（application.yml）

```yaml
spring:
  ai:
    # Embedding 模型配置（使用 Ollama 本地）
    ollama:
      embedding:
        options:
          model: nomic-embed-text

    # 或使用 OpenAI embedding
    # openai:
    #   api-key: ${OPENAI_API_KEY}

    # 向量数据库配置
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 768              # nomic-embed-text 维度
        max-document-batch-size: 1000

  # PGVector 数据源（独立于主 MySQL）
  datasource:
    pgvector:
      url: jdbc:postgresql://localhost:5432/postgres
      username: postgres
      password: 123456
```

### 3. 代码实现

```java
@Service
public class StandardRagService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatModel chatModel;

    /**
     * 写入文档到向量数据库
     */
    public void indexDocument(Long docId, String title, String content) {
        List<String> chunks = chunk(content, 500, 50);

        List<Document> docs = chunks.stream()
            .map(text -> new Document(text, Map.of("docId", docId, "title", title)))
            .toList();

        vectorStore.add(docs);
    }

    /**
     * 检索 + 生成
     */
    public String ragQuery(String question, int topK) {
        // 1. 向量检索
        List<Document> results = vectorStore.similaritySearch(
            SearchRequest.query(question).withTopK(topK)
        );

        // 2. 构建 Prompt
        String context = results.stream()
            .map(Document::getText)
            .collect(Collectors.joining("\n\n"));

        String prompt = String.format(
            "根据以下参考资料回答问题。如果没有相关信息请如实说明。\n\n参考资料：\n%s\n\n问题：%s",
            context, question
        );

        // 3. 调用大模型
        return chatModel.call(prompt);
    }

    private List<String> chunk(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            chunks.add(text.substring(start, end));
            start += size - overlap;
        }
        return chunks;
    }
}
```

---

## 部署方案对比

### 方案一：全本地部署（零成本）

```
Embedding: Ollama nomic-embed-text（本地 GPU/CPU）
向量数据库: PGVector Docker（本地）
大模型: Ollama qwen2.5（本地）
```

**优点：** 完全免费，数据不出服务器
**缺点：** 需要 GPU，embedding 速度较慢

### 方案二：云端 Embedding + 本地数据库

```
Embedding: OpenAI text-embedding-3-small（API 调用）
向量数据库: PGVector Docker（本地）
大模型: DeepSeek API（云端）
```

**优点：** Embedding 质量高，速度快
**缺点：** 需要 OpenAI API Key，文档内容发送到云端

### 方案三：全云端

```
Embedding: OpenAI text-embedding-3-small
向量数据库: Pinecone（云服务）
大模型: DeepSeek API
```

**优点：** 零部署，弹性扩展
**缺点：** 持续付费，数据在云端

### 推荐

| 场景 | 推荐方案 |
|------|---------|
| 学习/原型 | 方案一（全本地） |
| 小型项目 | 方案二（云端 embedding + 本地 DB） |
| 生产环境 | 方案三（全云端）或方案二 |

---

## 与当前关键词方案的对比

| 维度 | 当前关键词方案 | 标准向量 RAG |
|------|--------------|-------------|
| 部署复杂度 | 零依赖 | 需要向量数据库 + embedding 模型 |
| 适合文档量 | 几十~几百 | 几千~几百万 |
| 匹配能力 | 精确关键词 | 语义相似（"苹果"能匹配"iPhone"） |
| 成本 | 免费 | embedding API 按量计费 |
| 可调试性 | 评分逻辑透明 | 向量距离不直观 |
| 中文支持 | 需手动 2-gram | 取决于 embedding 模型 |

**结论：** 文档量小、预算有限时，关键词方案够用；文档量大或需要语义匹配时，升级到向量 RAG。
