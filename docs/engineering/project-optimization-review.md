# AI Studio 项目待优化审阅文档

生成日期：2026-06-13  
审阅范围：`chatbot-service`、`file-service`、`gateway`、Docker Compose、数据库迁移、主要测试与前端模板。

> 说明：本文是基于当前代码的静态审阅，不包含线上压测、漏洞扫描、真实流量指标或生产日志验证。优先级按“安全风险、数据丢失风险、生产稳定性、性能瓶颈、长期维护成本”排序。

## 总体判断

项目已经具备比较完整的工程形态：Gateway 统一入口、Nacos 服务发现、Kafka 异步事件、Redis 缓存、MySQL/Flyway、文件服务、Agent 工具治理、Hybrid RAG、PGVector、MCP/WebTools 等能力都已经落地。

当前主要不足不是“功能缺失”，而是工程边界需要继续收紧：

- 安全边界：文件服务下载放行、CORS 过宽、前端 Markdown 渲染未净化、生产默认密钥不够严格。
- 可靠性：聊天事件发送失败会静默丢历史，部分 Kafka 消费失败直接 ACK，缺少 outbox/补偿链路。
- 性能：Redis `KEYS`、RAG 关键词全量扫描、裸 `RestTemplate`、裸 JDBC、摘要刷新触发条件不够精确。
- 架构：认证职责分散，Gateway/service/file-service 信任模型不统一。
- 测试：部分集成测试依赖本机真实 Kafka/Nacos/Gateway，难以稳定纳入 CI。

## P0：建议立即修复

### 1. 文件下载权限边界过松

**证据代码：**

- Gateway 放行下载路径：`gateway/src/main/resources/application.yml:124`

```yaml
- /api/files/download/**
```

- 下载接口允许没有 Header 时从 query 参数取 `userId`：`file-service/src/main/java/com/example/file/controller/FileController.java:76`

```java
Long userId = headerUserId != null ? headerUserId : paramUserId;
```

- 文件权限校验对未登录用户放行：`file-service/src/main/java/com/example/file/service/FileService.java:137`

```java
if (userId == null || userId == 0) return true; // 未登录用户暂时放行（兼容旧调用）
if (record.getUploaderId() == null || record.getUploaderId() == 0) return true;
```

**风险：**

只要知道 `fileKey`，请求经过 Gateway 下载路径时不需要 JWT；如果没有 `X-Auth-UserId`，`canAccess()` 直接放行。知识库文档、聊天图片、Agent workspace 文件都有被未授权访问的风险。

**建议方案：**

短期：

1. 删除 `paramUserId` 信任逻辑，不允许客户端 query 参数声明身份。
2. `canAccess()` 默认拒绝匿名访问，只有明确标记为 public 的文件才允许匿名下载。
3. 下载链接改为短期签名 URL，例如 `GET /api/files/download/{fileKey}?token=...`，签名包含 `fileKey/userId/expireAt`。

示例改法：

```java
public boolean canAccess(FileRecord record, Long userId) {
    if (record == null || userId == null || userId <= 0) {
        return false;
    }
    if (record.getUploaderId() == null || record.getUploaderId() <= 0) {
        return false;
    }
    return record.getUploaderId().equals(userId);
}
```

中期：

- file-service 自己实现 JWT 校验或接入统一内部鉴权 Filter，不只依赖 Gateway 注入 Header。
- Gateway 到下游服务的 `X-Auth-*` Header 先清理再注入，防止客户端伪造。

**验证：**

- 未登录访问 `/api/files/download/{privateFileKey}` 应返回 401/403。
- 登录用户 A 不能下载用户 B 的文件。
- 带伪造 `X-Auth-UserId` 直连 file-service 时仍被拒绝。

### 2. 生产环境默认密钥和基础设施端口需要收紧

**证据代码：**

- 生产 compose 允许空密码或默认 JWT：`docker-compose.prod.yml:11`、`docker-compose.prod.yml:29`、`docker-compose.prod.yml:122`

```yaml
MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-}
command: redis-server --requirepass ${REDIS_PASSWORD:-}
APP_JWT_SECRET: ${APP_JWT_SECRET:-change-this-to-a-random-string-at-least-32-chars}
```

- 生产 compose 仍公开 MySQL/Redis/Kafka/Nacos：`docker-compose.prod.yml:9`、`:28`、`:42`、`:70`

```yaml
- "3307:3306"
- "6379:6379"
- "9092:9092"
- "8848:8848"
- "9848:9848"
```

- 应用配置也有默认 JWT：`chatbot-service/src/main/resources/application.yml:146`、`gateway/src/main/resources/application.yml:109`

```yaml
jwt-secret: ${APP_JWT_SECRET:change-this-dev-secret-key-at-least-32-chars}
```

**风险：**

生产环境如果 `.env` 缺失或配置错误，服务仍可能用空密码/默认密钥启动。基础设施端口公开会扩大攻击面，尤其 Redis、Kafka、Nacos 不应该直接暴露到公网。

**建议方案：**

1. 生产 compose 对必填变量使用 `:?`，缺失时拒绝启动。
2. 生产只暴露 Gateway 或 Nginx，基础设施端口改为 `expose` 或绑定 `127.0.0.1`。
3. Nacos 开启认证，Redis 禁止空密码，Kafka 只允许 Docker 内网访问。
4. 应用启动时检测默认 JWT 值，如果 profile 是 prod 直接失败。

示例：

```yaml
MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}
SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD:?REDIS_PASSWORD is required}
APP_JWT_SECRET: ${APP_JWT_SECRET:?APP_JWT_SECRET is required}
```

**验证：**

- 删除 `.env` 后 `docker compose -f docker-compose.prod.yml config` 应直接报错。
- 公网 `ss -tlnp` 只看到 80/443 或 9000，不看到 3307/6379/9092/8848。

### 3. 聊天记录投递失败会静默丢失

**证据代码：**

`chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventProducer.java:50`

```java
log.error("【Kafka Producer】消息发送最终失败，SessionId: {}，消息将丢失。生产环境应写入本地消息表做补偿",
        event.getSessionId());
```

**风险：**

用户已经收到 AI 回复，但 Kafka 发送失败后聊天记录没有入库，也不会进入 DLT。后续上下文、审计、统计都会缺这条记录。

**建议方案：**

优先采用 **Outbox Pattern**，不建议第一阶段直接引入 Canal/CDC。

#### Outbox Pattern vs Canal/CDC 评估

| 方案 | 适配度 | 优点 | 缺点 | 对当前项目的结论 |
|------|--------|------|------|------------------|
| Outbox Pattern | 高 | 只依赖现有 MySQL；代码可控；不增加中间件；可以精确表达重试、状态、错误信息；适合当前单机/低资源部署 | 需要新增表和后台 worker；需要自己处理重试和幂等 | 推荐第一阶段采用 |
| Canal/CDC | 中 | 基于 binlog 捕获变更；业务代码可以少写投递逻辑；后续多系统订阅扩展性更好 | 需要 Canal 或 Debezium 等额外组件；依赖 MySQL binlog 配置；运维复杂；本项目 2GB 单机资源压力更大 | 可作为后续 outbox 表的投递实现，不建议直接作为第一阶段 |

关键判断点：

1. 当前问题发生在 `ChatEventProducer.sendChatEvent(...)`：Kafka 投递失败后，聊天事件没有写入任何本地持久化位置。没有本地落库，就没有 binlog 变更，Canal 也没有东西可以捕获。
2. 如果为了使用 Canal，仍然需要先把聊天完成事件写入 MySQL 某张表。这个表本质上就是 outbox 表。
3. Canal 更适合“数据库已经是事实源，需要把变更广播到其他系统”的场景；当前更核心的问题是“先把待持久化事件可靠保存下来”。
4. 项目当前生产 compose 是低内存单机优化，引入 Canal/Debezium 会增加部署、监控和排障复杂度。

推荐分阶段：

第一阶段采用应用内 outbox worker：

1. 新增 `chat_event_outbox` 表。
2. 用户请求结束后先把待保存事件写入 outbox，本地事务成功即认为“待持久化”。
3. 后台 worker 投递 Kafka，成功后标记 `SENT`，失败按次数重试。
4. Kafka 消费端用 `eventId` 做幂等，避免重复入库。

第二阶段如果聊天事件还要被搜索、分析、画像、离线数仓等多个系统订阅，可以把 outbox 投递方式升级为 CDC：

```text
chatbot-service 写 chat_event_outbox
  -> MySQL binlog
  -> Canal/Debezium
  -> Kafka chat.events
  -> ChatEventConsumer / Analytics / Audit
```

也就是说，Canal 不替代 outbox 的“可靠本地落点”，而是可以替代应用内 worker 的“投递 Kafka”部分。

示例表：

```sql
CREATE TABLE chat_event_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL UNIQUE,
  session_id VARCHAR(255) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NULL,
  created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_outbox_status_retry (status, next_retry_time)
);
```

**验证：**

- 停掉 Kafka 后完成一次聊天，应能在 outbox 看到 `PENDING/FAILED_RETRY`。
- Kafka 恢复后 worker 能补投，聊天记录最终入库。
- 重复消费同一个 `eventId` 不产生重复 `chat_record`。

### 4. 前端 Markdown 渲染存在 XSS 风险

**证据代码：**

- 引入 `marked`：`chatbot-service/src/main/resources/templates/chat.html:15`
- AI 回复和知识库内容使用 `marked.parse` 后写入 `innerHTML`：`chat.html:2027`、`:2162`、`:2196`、`:2852`

```javascript
const contentHtml = fullText ? marked.parse(fullText) : (fallbackHtml || '');
targetBubble.innerHTML = toolHtml + contentHtml;
```

**风险：**

AI 回复、知识库文档、workspace 文件内容都可能包含 HTML/脚本片段。如果没有 DOMPurify 或后端净化，存在存储型/反射型 XSS 风险。

**建议方案：**

1. 前端引入 DOMPurify，对所有 `marked.parse()` 输出做 sanitize。
2. 后端对用户上传/Agent 生成的 HTML 类内容做安全策略限制。
3. 增加 CSP 响应头，禁止 inline script，限制外部 CDN。

示例：

```javascript
const unsafeHtml = marked.parse(fullText || '');
const safeHtml = DOMPurify.sanitize(unsafeHtml, {
  USE_PROFILES: { html: true }
});
targetBubble.innerHTML = toolHtml + safeHtml;
```

**验证：**

- 输入 `<img src=x onerror=alert(1)>` 不应执行脚本。
- 知识库文档中包含 `<script>` 时打开预览不执行。

## P1：建议尽快修复

### 5. CORS 策略过宽

**证据代码：**

- Gateway 全局 CORS：`gateway/src/main/resources/application.yml:93`

```yaml
allowedOriginPatterns: "*"
allowCredentials: true
```

- 多个 Controller 标注 `@CrossOrigin(origins = "*")`：`AuthController`、`ChatbotController`、`AdminController`、`AgentController` 等。
- file-service CORS：`file-service/src/main/java/com/example/file/config/CorsConfig.java:15`

```java
config.addAllowedOriginPattern("*");
config.setAllowCredentials(true);
```

**风险：**

允许任意来源携带凭证访问 API，会增加跨站请求和 Token 泄露后的利用面。当前 Token 存在 `localStorage`，如果任意页面发生 XSS，风险更高。

**建议方案：**

- CORS 只在 Gateway 配置，业务服务去掉 `@CrossOrigin`。
- 允许来源改成明确域名，例如 `https://your-domain.com`。
- 若未来改 HttpOnly Cookie，再配合 SameSite/CSRF Token。

### 6. Redis `KEYS` 会阻塞生产 Redis

**证据代码：**

- 刷新令牌全量扫描：`chatbot-service/src/main/java/com/example/chatbot/security/RefreshTokenStore.java:37`

```java
Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
```

- 知识库缓存清理扫描：`chatbot-service/src/main/java/com/example/chatbot/kafka/KnowledgeEventConsumer.java:58`

```java
java.util.Set<String> keys = redisTemplate.keys(pattern);
```

**风险：**

`KEYS` 是 O(N) 操作，生产 Redis key 多时会阻塞主线程，影响验证码、Token、上下文缓存等所有 Redis 读写。

**建议方案：**

Refresh Token：

- 存储 `refresh_token:{token} -> userId` 的同时，维护 `refresh_token_user:{userId}` Set。
- 注销用户时只读这个 Set 删除对应 token。

示例：

```java
redisTemplate.opsForSet().add("refresh_token_user:" + userId, token);
redisTemplate.expire("refresh_token_user:" + userId, refreshTokenExpireMs, TimeUnit.MILLISECONDS);
```

知识库缓存：

- 维护 `chat:sessions:{userId}` Set，创建会话/写历史时把 sessionId 加进去。
- 清理时按 session set 精准删除 `chat:history:{sessionId}` 和 `chat:summary:{sessionId}`。

### 7. HTTP 客户端缺少统一超时和连接池

**证据代码：**

- 文件服务客户端：`chatbot-service/src/main/java/com/example/chatbot/service/FileServiceClient.java:34`
- Embedding 请求：`chatbot-service/src/main/java/com/example/chatbot/rag/EmbeddingClient.java:65`
- WebTools 请求：`chatbot-service/src/main/java/com/example/chatbot/webtools/WebToolService.java:107`

```java
new RestTemplate()
```

**风险：**

默认 `RestTemplate` 无统一连接池/超时/重试/熔断策略。file-service、embedding 服务、Firecrawl 变慢时可能拖垮业务线程。

**建议方案：**

1. 提供统一 `RestTemplate` 或 `WebClient` Bean，配置 connect/read timeout。
2. 对外部 API 加 Resilience4j：timeout、retry、circuit breaker、bulkhead。
3. 为 file-service、embedding、webtools 分别配置不同超时。

示例：

```java
@Bean
RestTemplate restTemplate(RestTemplateBuilder builder) {
    return builder
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15))
            .build();
}
```

### 8. Kafka 不同事件的可靠性策略不一致

**证据代码：**

- 聊天消费有 `DefaultErrorHandler + DLT`。
- 通知消费者失败后直接 ACK：`NotificationEventConsumer.java:45`、`:49`、`:54`
- 知识库消费者异常后直接 ACK：`KnowledgeEventConsumer.java:47`

```java
catch (Exception e) {
    log.error(...);
    ack.acknowledge();
}
```

**风险：**

邮件发送失败、知识库索引失败可能只打日志，没有进入 DLT，也没有明确补偿入口。知识库向量索引失败会影响 RAG 召回质量。

**建议方案：**

- 为 `notification.events` 和 `knowledge.events` 也配置独立 DLT。
- 业务异常和系统异常分开处理：频率限制可 ACK，SMTP 故障/向量库故障应 retry + DLT。
- 管理后台增加 DLT 查看与重放入口。

### 9. 上下文摘要刷新触发条件不精确

**证据代码：**

`chatbot-service/src/main/java/com/example/chatbot/service/ChatContextService.java:219`

```java
Long size = redisTemplate.opsForList().size(historyKey(sessionId));
if (historySize < trigger || (historySize - trigger) % refreshEvery != 0) {
    return;
}
```

**风险：**

Redis List 被裁剪到 `redis-cache-size=30` 后，长度会长期保持 30。如果 `(30 - trigger) % refreshEvery == 0`，后续每次追加都可能触发摘要刷新，导致 LLM 摘要调用过频。

**建议方案：**

- 新增 `chat:summary:meta:{sessionId}`，记录 `lastSummarizedRecordId`。
- 只有最新 `recordId - lastSummarizedRecordId >= refreshEvery` 才刷新。
- 摘要任务放入专用线程池或队列，避免 `CompletableFuture.runAsync()` 使用公共 ForkJoinPool。

示例：

```java
if (latestRecordId - lastSummarizedRecordId < refreshEvery) {
    return;
}
summaryExecutor.submit(() -> refreshSummary(sessionId, latestRecordId));
```

### 10. 多模态图片仍保留 Base64 入库分支

**证据代码：**

- 表结构：`chatbot-service/src/main/resources/db/migration/V1__init_schema.sql:35`

```sql
image_data LONGTEXT NULL
```

- Base64 分支：`chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventConsumer.java:80`

```java
String base64 = Base64.getEncoder().encodeToString(event.getImageBytes());
return "data:" + mime + ";base64," + base64;
```

**风险：**

大图片进入 MySQL LONGTEXT 会放大表体积，影响查询、备份、恢复。当前 fileKey 模式已经存在，应尽快统一。

**建议方案：**

- 强制图片先上传 file-service，聊天事件只保存 `imageFileKey`。
- 数据库字段改名或新增 `image_file_key`，逐步废弃 `image_data`。
- 历史 Base64 数据可迁移到文件服务后回填 fileKey。

### 11. RAG 关键词检索仍是内存全量扫描

**证据代码：**

`chatbot-service/src/main/java/com/example/chatbot/rag/HybridSearchService.java:86`

```java
List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
        new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getUserId, userId)
                .eq(KnowledgeDocument::getEnabled, true));
```

**风险：**

用户文档多时，每次检索都把所有文档拉进内存做评分。随着知识库数量增长，会成为明显性能瓶颈。

**建议方案：**

#### 4G 服务器下是否引入 Elasticsearch

结论：**当前 4G 单机不优先推荐引入 Elasticsearch**。不是 ES 不适合 RAG，而是它和当前部署形态的资源预算不匹配。

当前生产形态大致需要同时运行：

- `chatbot-service`
- `file-service`
- `gateway`
- MySQL
- Redis
- Kafka
- Nacos
- 可选 PGVector
- 可选 Ollama/llava/qwen

在 4G 服务器上，Kafka、Nacos、MySQL、JVM 服务本身已经会占用不少内存。如果再加入 Elasticsearch，通常还要给 ES JVM heap 单独预留至少几百 MB 到 1GB 以上，同时还要留操作系统 page cache。结果很容易变成“所有组件都能启动，但整体频繁 GC、磁盘抖动、查询延迟不稳定”。

ES 的价值在于：

- 文档规模较大，比如单用户/全站达到数万到百万级 chunk。
- 需要复杂全文检索、分词、高亮、过滤、排序、聚合。
- 有独立资源或独立搜索服务节点。

当前项目更像单机演示/低并发工程化项目，RAG 优化应该先解决“全量扫描”和“索引状态治理”，而不是直接增加一个重型中间件。

#### 推荐 RAG 演进路线

第一阶段：保留现有 MySQL + PGVector，修掉全量扫描。

- 给 `knowledge_document` 增加 MySQL FULLTEXT 索引，中文场景可评估 `ngram` parser。
- 关键词检索先由 SQL 粗筛候选集，再进入 Java 的 `KeywordExtractor/HybridRanker` 精排。
- 候选集加硬上限，例如 100 到 300 条，避免一次性加载用户全部文档。
- `content` 很长时不要直接整篇参与关键词评分，优先基于 chunk 检索。

示例方向：

```sql
ALTER TABLE knowledge_document
  ADD FULLTEXT INDEX ft_knowledge_title_content_tags (title, content, tags);
```

然后把当前：

```java
selectList(eq(userId).eq(enabled))
```

改成：

```sql
WHERE user_id = ?
  AND enabled = 1
  AND MATCH(title, content, tags) AGAINST (? IN NATURAL LANGUAGE MODE)
LIMIT 200
```

第二阶段：强化 Hybrid RAG。

- PGVector 负责语义召回。
- MySQL FULLTEXT/ngram 负责关键词召回。
- `HybridRanker` 统一融合排序。
- 增加 rerank，可先用轻量规则 rerank，后续再接模型 reranker。

第三阶段：满足阈值后再引入搜索引擎。

建议达到下面任一条件时再考虑 Elasticsearch/OpenSearch/Meilisearch：

- 知识库 chunk 数超过 5 万到 10 万。
- FULLTEXT 查询已经成为明显瓶颈。
- 需要复杂过滤、聚合、高亮、权限过滤、多字段权重调参。
- 搜索服务可以独立部署，或服务器升级到 8G/16G。

#### Elasticsearch、OpenSearch、Meilisearch 对比

| 方案 | 适合场景 | 资源压力 | 当前建议 |
|------|----------|----------|----------|
| MySQL FULLTEXT/ngram | 小中规模、低运维、已有 MySQL | 低 | 优先采用 |
| PGVector | 语义召回、embedding RAG | 中 | 已有，继续完善连接池和索引任务 |
| Meilisearch | 轻量全文检索、部署简单 | 中低 | 如果只想增强关键词检索，可作为 ES 前的备选 |
| Elasticsearch/OpenSearch | 大规模检索、复杂搜索能力 | 高 | 当前 4G 单机不优先推荐 |

如果必须在 4G 单机上试 ES，只建议作为实验 profile：

- 默认不随生产 compose 启动。
- 给 ES 限制 heap，例如 512MB 到 1GB。
- 关闭不必要功能，限制 shard/replica。
- 明确监控内存、GC、磁盘 IO。

但从当前项目收益/成本看，**MySQL FULLTEXT + PGVector + HybridRanker** 是更合理的下一步。

#### 具体改造建议

短期：

- 给关键词检索加候选上限，例如先按更新时间、标题命中、标签命中筛到 200 条。
- 对 `title/tags/content` 加 MySQL FULLTEXT 或额外倒排索引表。

中期：

- 关键词检索交给 MySQL FULLTEXT/ngram 或轻量 Meilisearch。
- Hybrid RAG 保留：向量召回 + 关键词召回 + rerank。

长期：

- 数据规模上来后再引入 Elasticsearch/OpenSearch，并把搜索服务拆成独立部署单元。

### 12. PGVector 使用裸 JDBC，缺少连接池

**证据代码：**

`chatbot-service/src/main/java/com/example/chatbot/rag/PgVectorClient.java:198`

```java
return DriverManager.getConnection(vector.getJdbcUrl(), vector.getUsername(), vector.getPassword());
```

**风险：**

每次索引/查询都新建连接，缺少连接池、连接健康检查和超时控制。并发检索或批量索引时性能和稳定性都较弱。

**建议方案：**

- 为 PGVector 单独配置 `DataSource`/HikariCP。
- SQL 操作改为 `JdbcTemplate`。
- 初始化 schema 从应用启动迁移到 Flyway，避免多实例并发启动时竞争建表/建索引。

### 13. SSE 流式链路重复逻辑较多，缺少取消与资源治理

**证据代码：**

- 三个入口都创建 180 秒 `SseEmitter`：`ChatbotController.java:39`、`:76`、`:117`
- `ChatbotService` 中普通、多模态、fileKey 三条链路都有类似 `model.stream(...).subscribe(...)`。
- Ollama 使用单实例信号量：`ChatbotService.java:46`

```java
private final Semaphore ollamaSemaphore = new Semaphore(1);
```

**风险：**

重复代码增加维护成本；客户端断开时订阅未必及时取消；Ollama 单信号量会把所有 Ollama 请求串行化，吞吐低且缺少队列指标。

**建议方案：**

- 抽出统一 `StreamChatRunner`，封装模型选择、SSE 发送、错误处理、保存事件、资源释放。
- 在 `SseEmitter.onCompletion/onTimeout/onError` 中取消流订阅。
- Ollama 并发数配置化，并暴露排队长度/等待耗时指标。

### 14. file-service 信任 `X-Auth-UserId`，缺少服务间鉴权

**证据代码：**

`file-service/src/main/java/com/example/file/controller/FileController.java` 多处直接读取 `X-Auth-UserId`，例如第 45、76、155 行。

**风险：**

如果 file-service 被直连，调用方可以伪造 `X-Auth-UserId`。即使当前 Docker 只 expose 8081，生产网络或内网穿透配置变化时仍有风险。

**建议方案：**

- file-service 自身解析 JWT，或者只接受 Gateway 签名过的内部 Header。
- Gateway 转发前先移除客户端传来的 `X-Auth-*` Header，再注入可信 Header。
- file-service 端校验内部签名，例如 `X-Internal-Signature`。

## P2：中期优化

### 15. 认证职责需要统一

当前 Gateway 和 chatbot-service 都能解析 JWT，而 file-service 主要依赖 Header。建议明确一种模式：

- 模式 A：所有业务服务都自己校验 JWT，Gateway 只做路由、限流、日志。
- 模式 B：Gateway 是唯一外部鉴权点，下游只信任 Gateway 注入的内部身份 Header，并通过网络隔离/内部签名防伪。

目前更建议模式 A，原因是 file-service、chatbot-service 都有敏感业务，服务自身鉴权更稳。

### 16. Auth 与验证码需要统一限流和风控

**证据代码：**

`AuthController` 的 `/send-code`、`/login`、`/forgot-password` 没看到统一接口限流。`EmailService` 内部对同邮箱 60 秒做了发送间隔限制，但没有 IP 维度、账号维度、日次数限制。

**建议方案：**

- Gateway 增加 Redis RateLimiter。
- 登录失败按 username/IP 计数，超过阈值加验证码或短期封禁。
- 验证码每天限制次数，重置密码接口避免枚举邮箱：无论邮箱是否存在都返回相同消息，后台记录审计。

### 17. Token 存储方式可进一步安全化

当前前端把 access token 和 refresh token 存在 `localStorage`：`login.html:1607`、`chat.html:1650`。

**风险：**

一旦页面出现 XSS，Token 可被直接读取。

**建议方案：**

- Refresh Token 放 HttpOnly Secure Cookie。
- Access Token 可短期内存保存，刷新通过 Cookie。
- 配合 CSRF Token 或 SameSite=strict/lax。

### 18. 前端模板体积和职责过大

`chat.html` 是单个 Thymeleaf 大文件，包含大量 CSS、HTML、JS、状态管理、SSE、workspace、RAG、Agent UI。

**风险：**

后期功能增长后可维护性下降，XSS/状态 bug 难定位，构建优化困难。

**建议方案：**

- 拆分为静态资源：`chat.css`、`auth.js`、`chat-stream.js`、`workspace.js`、`knowledge.js`。
- 引入前端构建工具或至少模块化 ES Module。
- 加 CSP 后逐步移除 inline script。

### 19. RAG 索引链路建议异步化和可观测

**证据代码：**

`KnowledgeEventConsumer` 收到事件后直接调用 `vectorIndexingService.indexDocument(...)`。

**风险：**

文档较大、embedding 慢、PGVector 慢时会占用 Kafka consumer 线程。失败后目前主要靠 `index_status` 标记，但缺少重试队列和管理入口。

**建议方案：**

- Kafka 只写索引任务表 `rag_index_task`。
- Worker 处理 embedding/index，支持重试、暂停、重建。
- 管理页展示 `PENDING/INDEXING/FAILED/INDEXED`，支持单文档重试。

### 20. 数据模型需要补用户隔离索引

`chat_record` 依靠 `session_id` 前缀表达用户归属，没有独立 `user_id` 字段。

**风险：**

按用户统计、删除用户数据、权限查询都依赖字符串前缀，后期迁移和审计困难。

**建议方案：**

- `chat_record` 新增 `user_id BIGINT`。
- 写入 `ChatEvent` 时携带 userId。
- 查询由 `session_id like userId_%` 改成 `where user_id = ?`。

### 21. 可观测性不足

当前日志较多，但缺少结构化指标：

- AI 首 token 耗时、总耗时、失败率。
- Redis 上下文 hit/miss。
- Kafka producer 成功率、DLT 数量、消费 lag。
- RAG vector/keyword/hybrid 命中率。
- file-service 上传/下载耗时与失败率。

**建议方案：**

- 引入 Spring Boot Actuator + Micrometer + Prometheus。
- 给核心链路加 timer/counter。
- 日志增加 traceId/sessionId/userId，Gateway 生成并传递 `X-Request-Id`。

## P3：测试与交付优化

### 22. 集成测试依赖外部环境，CI 不稳定

**证据代码：**

- `KafkaIntegrationTest` 硬编码 `localhost:9092`
- `EventDrivenIntegrationTest` 硬编码 `localhost:9092`
- `EndToEndTest` 硬编码 `localhost:9000`、`localhost:8848`
- `GatewayIntegrationTest` 注释要求本地后端/Redis 运行

**建议方案：**

- 单元测试和集成测试分层：
  - `*Test`：纯单元测试，默认 CI 跑。
  - `*IT`：Testcontainers 集成测试，`mvn verify -Pintegration` 跑。
  - `*E2E`：真实环境脚本跑，不进入默认 Maven test。
- Kafka/MySQL/Redis 使用 Testcontainers。
- Gateway 路由测试使用 WireMock 模拟下游服务。

### 23. 文档存在历史架构描述和当前实现不一致

项目 `md/`、`docs/` 中有大量阶段文档，部分仍描述旧端口、旧 Nginx、旧 RAG 方案。README 也保留了直接访问 `localhost:8080/8081` 的说明。

**建议方案：**

- 保留阶段文档，但加 `legacy` 标识。
- README 只描述当前推荐路径：生产统一 Gateway/Nginx。
- 新建 `docs/current-architecture.md` 作为唯一当前架构说明。

## 建议执行顺序

### 第一批：安全修复

1. 修 file-service 下载权限：匿名默认拒绝，移除 query `userId` 信任。
2. 收紧 CORS：只保留 Gateway 指定域名。
3. 生产 compose 必填变量，基础设施端口只内网。
4. 前端 Markdown 引入 DOMPurify 和 CSP。

### 第二批：可靠性修复

1. 聊天事件 outbox，避免 Kafka 发送失败丢历史。
2. notification/knowledge 事件补 DLT 和重试。
3. Redis `KEYS` 改索引 Set 或 SCAN。
4. file-service/embedding/webtools 统一 HTTP 超时和连接池。

### 第三批：性能和架构治理

1. RAG 先做 MySQL FULLTEXT/ngram 候选粗筛，避免 `HybridSearchService.searchKeyword()` 全量扫描。
2. PGVector 改连接池，schema 初始化迁移到 Flyway，索引链路改任务表 + Worker。
3. 保持 Hybrid RAG：FULLTEXT 关键词召回 + PGVector 语义召回 + `HybridRanker` 融合排序。
4. 数据规模超过 5 万到 10 万 chunk，或服务器升级/搜索服务可独立部署后，再评估 Elasticsearch/OpenSearch。
5. chat_record 增加 user_id。
6. SSE 流式逻辑抽象，增加取消和指标。

### 第四批：测试和可观测

1. Testcontainers 改造 Kafka/MySQL/Redis 集成测试。
2. CI 默认只跑稳定单元测试。
3. Actuator + Micrometer + Prometheus 指标。
4. DLT 管理和重放工具。

## 面试表达建议

可以把优化路线概括为：

> 当前项目已经完成了微服务、事件驱动、RAG、Agent 工具治理等核心能力。下一步我会优先补齐生产工程化短板：第一是安全边界，特别是文件下载鉴权、CORS 和前端 XSS；第二是异步可靠性，用 outbox 和 DLT 避免聊天记录、通知、索引事件静默丢失；第三是性能和可观测性，把 Redis KEYS、RAG 全量扫描、无超时 HTTP 调用这些演示阶段可接受但生产不稳的点逐步替换掉。

## 验证命令建议

短期每批修复后至少执行：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,KafkaReliabilityTest,AgentToolSecurityTest,WebToolsSecurityTest" test
mvn -q -pl chatbot-service -DskipTests package
mvn -q -pl file-service -DskipTests package
mvn -q -pl gateway -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
```

完整集成测试建议等 Testcontainers 改造后再纳入 CI。
