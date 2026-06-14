# review.md 改进结果测试报告

生成日期：2026-06-13

## 执行范围

本次按 `docs/project-optimization-review.md` 的高优先级问题做代码改造，重点覆盖安全边界、Kafka 可靠性、Redis 生产风险、RAG 检索性能和 HTTP 客户端稳定性。

## review.md 条目状态

| 编号 | 问题 | 本次状态 |
| --- | --- | --- |
| 1 | 文件下载权限边界过松 | 已完成 |
| 2 | 生产环境默认密钥和基础设施端口需要收紧 | 已完成主要配置，缺失变量失败需在无 `.env` 环境复验 |
| 3 | 聊天记录投递失败会静默丢失 | 已完成 outbox 补偿和 eventId 幂等 |
| 4 | 前端 Markdown 渲染存在 XSS 风险 | 已完成 DOMPurify 净化，CSP 后续补强 |
| 5 | CORS 策略过宽 | 已完成 |
| 6 | Redis `KEYS` 会阻塞生产 Redis | 已完成 |
| 7 | HTTP 客户端缺少统一超时和连接池 | 已完成统一超时；HTTP 连接池可后续换 Apache/OkHttp |
| 8 | Kafka 不同事件的可靠性策略不一致 | 已完成 DLT 和异常重试策略 |
| 9 | 上下文摘要刷新触发条件不精确 | 已完成按最新记录 id 增量刷新 |
| 10 | 多模态图片仍保留 Base64 入库分支 | 已完成，Base64 分支不再入库 |
| 11 | RAG 关键词检索仍是内存全量扫描 | 已完成 MySQL FULLTEXT 候选和有限扫描降级 |
| 12 | PGVector 使用裸 JDBC，缺少连接池 | 已完成 HikariCP |
| 13 | SSE 流式链路重复逻辑较多，缺少取消与资源治理 | 后续治理，涉及流式协议和前端交互重构 |
| 14 | file-service 信任 `X-Auth-UserId`，缺少服务间鉴权 | 部分完成，Gateway 清理伪造 Header，file-service 内部 JWT/签名后续补强 |
| 15 | 认证职责需要统一 | 后续治理，需统一 Gateway/service 权限模型 |
| 16 | Auth 与验证码需要统一限流和风控 | 后续治理 |
| 17 | Token 存储方式可进一步安全化 | 后续治理，涉及 HttpOnly Cookie/CSRF 前后端联动 |
| 18 | 前端模板体积和职责过大 | 后续治理 |
| 19 | RAG 索引链路建议异步化和可观测 | 部分完成，检索候选优化已完成，索引任务状态和指标后续补强 |
| 20 | 数据模型需要补用户隔离索引 | 部分完成，聊天记录补 `user_id/event_id`，其他业务表后续按查询补齐 |
| 21 | 可观测性不足 | 后续治理 |
| 22 | 集成测试依赖外部环境，CI 不稳定 | 部分完成，新增单元测试；Testcontainers 后续补强 |
| 23 | 文档存在历史架构描述和当前实现不一致 | 部分完成，新增本报告；历史文档统一校准后续处理 |

## 已完成改进

### 1. 文件下载权限收紧

- `file-service` 下载接口不再信任 query 参数里的 `userId`，只使用 Gateway 注入的 `X-Auth-UserId`。
- `FileService.canAccess(...)` 改为默认拒绝匿名用户、`0` 用户和未绑定上传者的文件。
- `chat.html` 和 `file-manager.html` 的下载、预览改为携带认证头 `fetch`，不再通过 `window.open(...?userId=...)` 暴露身份参数。
- `Gateway` 注入 `X-Auth-*` 前先移除客户端伪造的同名 Header。

### 2. 生产配置收紧

- `docker-compose.prod.yml` 中 MySQL、Redis、Kafka、Nacos 改为 Docker 内网 `expose`，不再直接公开映射到宿主机。
- Redis、MySQL、JWT 等生产关键变量改为 `${VAR:?message}` 形式，避免生产缺少配置时静默使用弱默认值。
- `file-service` Redis 密码配置修正为 `SPRING_DATA_REDIS_PASSWORD`。
- Gateway 和 file-service 的 CORS 来源改为明确白名单配置，不再使用 `* + allowCredentials`。
- 移除了业务 Controller 上的 `@CrossOrigin(origins = "*")`。

### 3. 聊天事件可靠性

- 新增 `chat_event_outbox` 本地消息表，Kafka 投递最终失败后写入 outbox，等待后台补偿。
- 新增 `ChatEventOutboxService` 定时补投，支持 `PENDING/FAILED_RETRY/SENT` 状态和指数退避。
- `ChatEvent` 和 `ChatRecord` 新增 `eventId`，消费者按 `eventId` 幂等去重，避免重复入库。
- `ChatbotApplication` 启用 `@EnableScheduling`。
- `ChatEventConsumer` 持久化时写入 `eventId/userId`，并继续在 Redis 失败时降级成功。

### 4. 前端 XSS 风险降低

- `chat.html` 引入 DOMPurify。
- 对 `marked.parse(...)` 的输出统一走 `sanitizeHtml(...)` 后再写入 `innerHTML`。

### 5. Kafka 消费失败处理

- `knowledge.events`、`notification.events` 增加 DLT topic。
- `KnowledgeConsumerConfig`、`NotificationConsumerConfig` 增加 `DefaultErrorHandler + DeadLetterPublishingRecoverer`。
- 知识库和通知消费者对系统异常不再直接 ACK，而是抛出异常交给重试和 DLT。

### 6. Redis KEYS 风险移除

- `RefreshTokenStore.revokeAllForUser(...)` 改为用户 token Set 索引，不再使用 `redisTemplate.keys(...)`。
- `ChatContextService` 增加 session 索引，支持按用户清理上下文缓存和摘要缓存。
- 知识库缓存失效改为调用 `ChatContextService.evictUserContext(userId)`。

### 7. HTTP 客户端和 PGVector 稳定性

- 新增统一 `RestTemplate` Bean，配置连接超时 3 秒、读取超时 15 秒。
- `FileServiceClient`、`EmbeddingClient`、`WebToolService` 改为注入统一 `RestTemplate`。
- `PgVectorClient` 改为 HikariCP 连接池，避免每次检索直接 `DriverManager.getConnection(...)`。

### 8. RAG 轻量优化

- 新增 `V8__add_knowledge_fulltext_index.sql`，为 `knowledge_document(title, content, tags)` 增加 MySQL FULLTEXT 索引。
- `KnowledgeDocumentMapper` 增加 `searchFulltextCandidates(...)`。
- `HybridSearchService` 关键词检索先查 FULLTEXT 候选，失败或无结果时降级为最近 200 条文档有限扫描，避免按用户全量扫描。

### 9. 多模态历史体积控制

- `ChatEventConsumer` 不再把 `imageBytes` Base64 写入聊天历史。
- 持久化图片历史优先使用 `fileKey` 文本引用，降低 MySQL 和 Redis 上下文缓存膨胀风险。

## 新增测试

- `FileServiceAccessTest`
  - 验证匿名用户、`0` 用户、无上传者文件全部拒绝。
  - 验证只有上传者可以访问自己的文件。
- `HybridSearchServiceCandidateTest`
  - 验证关键词检索优先使用 FULLTEXT 候选。
  - 验证 FULLTEXT 异常时降级为有限扫描。
- 更新 `KafkaReliabilityTest`
  - 验证 Kafka 最终投递失败会写 outbox。
  - 验证 Base64 图片不再入库。
- 更新 `KnowledgeEventVectorIndexTest`
  - 验证知识库事件会触发用户上下文缓存清理。
- 更新 `WebToolsSecurityTest`
  - 适配统一 `RestTemplate` 注入。

## 验证结果

### 单元测试

命令：

```bash
mvn -q -pl chatbot-service,file-service "-Dtest=KafkaReliabilityTest,ChatContextServiceTest,HybridSearchServiceCandidateTest,FileServiceAccessTest" test
```

结果：通过。

说明：测试输出中出现 Kafka 发送失败堆栈是 `KafkaReliabilityTest` 主动模拟的失败重试场景，不是测试失败。

### 构建验证

命令：

```bash
mvn -q -pl chatbot-service,file-service,gateway -DskipTests package
```

结果：通过。

命令：

```bash
mvn -q -DskipTests package
```

结果：通过。

### Docker Compose 配置验证

命令：

```bash
docker compose config --quiet
```

结果：通过。

命令：

```bash
docker compose -f docker-compose.prod.yml config --quiet
```

结果：当前工作区存在 `.env`，生产 compose 可以解析通过。因为 `.env` 会补齐变量，未在当前环境证明“完全缺失变量时必然失败”；但 `docker-compose.prod.yml` 已使用 `${VAR:?message}` 必填语法。

### 高风险残留扫描

命令：

```bash
rg -n "redisTemplate\.keys|new RestTemplate\(|DriverManager\.getConnection|@CrossOrigin|allowedOriginPatterns:\s*\"\*\"|/api/files/download/\*\*" chatbot-service file-service gateway docker-compose.prod.yml
```

结果：无命中。

## 未完全闭环或后续建议

- 文件下载本次采用“认证 fetch + Gateway 注入用户”的方式修复直接风险，尚未实现短期签名 URL。若后续需要浏览器直接打开私有图片，建议增加 `fileKey/userId/expireAt` 签名。
- file-service 仍主要信任 Gateway 注入 Header。生产 compose 已隐藏业务端口，但如果未来单独暴露 file-service，需要在 file-service 内增加 JWT 校验或内部服务签名。
- XSS 本次完成前端 DOMPurify 净化，尚未增加 CSP 响应头和 HttpOnly Cookie 改造。
- RAG 本次未引入 Elasticsearch，符合 4GB 单机部署约束；当前先用 MySQL FULLTEXT + 有限扫描降级。中文召回如果不足，下一步优先评估 MySQL ngram parser 或轻量 embedding，不建议直接上 Elasticsearch。
- outbox 已覆盖 Kafka 发送最终失败的补偿，但还需要线上定期观察 `chat_event_outbox` 中 `FAILED_RETRY` 堆积情况，并补一个管理端查看或告警入口。
- `V7/V8` 迁移会修改 `chat_record` 和 `knowledge_document` 索引，生产执行前建议备份数据库；如果历史文档量较大，FULLTEXT 建索引可能需要维护窗口。
