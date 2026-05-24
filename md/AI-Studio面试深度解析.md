# AI Studio 项目面试深度解析

## 目录
- [1. Docker 容器化 + 一键部署](#1-docker-容器化--一键部署)
- [2. Kafka 事件驱动 + 异步解耦](#2-kafka-事件驱动--异步解耦)
- [3. 文件管理微服务 + 文档解析](#3-文件管理微服务--文档解析)
- [4. Redis 缓存 + 异步写回](#4-redis-缓存--异步写回)
- [5. Semaphore 信号量 + Ollama 并发排队](#5-semaphore-信号量--ollama-并发排队)
- [6. JWT 双令牌 + Token 原子轮转](#6-jwt-双令牌--token-原子轮转)
- [7. Spring Cloud Gateway 统一网关](#7-spring-cloud-gateway-统一网关)
- [8. 自研 RAG 关键词评分算法](#8-自研-rag-关键词评分算法)
- [9. 架构容错与模型降级](#9-架构容错与模型降级)
- [10. 多模态图文混合输入](#10-多模态图文混合输入)

---

## 1. Docker 容器化 + 一键部署

### 做了什么

将单体 Spring Boot 应用拆分为 3 个微服务 + 4 个基础设施，全部 Docker 容器化。一条 `docker compose up -d` 完成从编译到启动的全流程。Maven 多模块项目，每个子模块独立 Dockerfile，多阶段构建将编译环境和运行环境分离。

**核心文件：**
- [docker-compose.yml](docker-compose.yml) — 编排 7 个服务
- [chatbot-service/Dockerfile](chatbot-service/Dockerfile) — 主服务构建
- [file-service/Dockerfile](file-service/Dockerfile) — 文件服务构建
- [gateway/Dockerfile](gateway/Dockerfile) — 网关构建

### 关键设计决策

**Q: 为什么用多阶段构建？**

第一阶段 `FROM maven:3.9-eclipse-temurin-17 AS builder`，在带 Maven + JDK 的镜像里编译打包。第二阶段 `FROM eclipse-temurin:17-jre-alpine`，只装 JRE。第一阶段编译完，只把 jar 复制到第二阶段。最终镜像从 763MB 降到 400MB。编译工具、源码、target 目录全部丢掉。

**Q: 为什么 build context 是项目根目录而不是子模块目录？**

Maven 多模块项目，子模块 pom.xml 引用父 POM：
```xml
<parent>
    <groupId>com.example</groupId>
    <artifactId>springai-chatbot</artifactId>
</parent>
```
如果 build context 只给 `./chatbot-service/`，Docker 看不到 `./pom.xml`（父 POM），Maven 报 `Non-resolvable parent POM`。所以 context 设为 `.`（根目录），Dockerfile 里分两步 COPY：先 COPY 父 POM，再 COPY 子模块内容。

### 环境变量注入机制

docker-compose.yml 的 `environment` → 容器环境变量 → Spring Boot relaxed binding 自动映射：

```
docker-compose.yml          容器内环境变量           Spring 属性
─────────────────          ──────────────          ────────────────
SMTP_HOST=smtp.qq.com  →  SMTP_HOST=smtp.qq.com  → ${SMTP_HOST:smtp.qq.com}
```

Spring Boot 的 `OriginTrackedSystemEnvironmentPropertySource` 支持 relaxed binding：大写+下划线 ↔ 小写+点。`FILE_SERVICE_URL` 自动映射到 `file.service.url`。

### Q: 2GB 服务器怎么跑 7 个服务？

- MySQL: `--innodb-buffer-pool-size=32M --performance-schema=OFF --skip-log-bin`
- Redis: `--maxmemory 32mb --save ""`
- Kafka: `KAFKA_HEAP_OPTS=-Xms64m -Xmx150m`
- Nacos: `JVM_XMS=96m JVM_XMX=128m`
- chatbot-service: `-Xms128m -Xmx256m -XX:+UseSerialGC`
- file-service: `-Xms64m -Xmx128m`
- gateway: `-Xms64m -Xmx128m`

核心手段：SerialGC 替代 G1GC（单线程 GC，内存开销更低），关闭 MySQL binlog 和 performance_schema，Redis 用 allkeys-lru 淘汰策略。

### 面试追问

**Q: Docker 容器间怎么通信？**
通过服务名（docker-compose 的 service name）作为 DNS。容器内访问 `mysql:3306`、`kafka:29092`、`nacos:8848` 就行，Docker 内置 DNS 自动解析为容器 IP。不能写 localhost——容器内 localhost 指向自己。

**Q: Kafka advertised listener 是什么问题？**
Kafka broker 对外宣称自己的地址。如果 advertised listener 是 `localhost:9092`，容器内的 consumer 拿到这个地址后去连自己的 localhost，本地没有 Kafka。修复：使用 INTERNAL 监听器 `kafka:29092`，它广播的是 Docker 内部可达的地址。

**Q: 数据持久化怎么保证？**
Docker Volume。`docker-compose down` 不会删 Volume，只删容器。`-v` 才会删数据。MySQL、Redis、Kafka 的数据都存在独立 Volume 里，容器重建不丢数据。

---

## 2. Kafka 事件驱动 + 异步解耦

### 做了什么

引入 3 个 Kafka Topic：`chat.events`（聊天记录持久化）、`notification.events`（邮件通知）、`knowledge.events`（RAG 缓存刷新）。每个 Topic 有独立的 Consumer Group 和自定义 ConsumerFactory。

**核心文件：**
- [KafkaTopicConfig.java](chatbot-service/src/main/java/com/example/chatbot/kafka/KafkaTopicConfig.java) — Topic 定义
- [ChatEventProducer.java](chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventProducer.java) — 聊天事件生产者
- [NotificationEventConsumer.java](chatbot-service/src/main/java/com/example/chatbot/kafka/NotificationEventConsumer.java) — 邮件消费者
- [KnowledgeEventConsumer.java](chatbot-service/src/main/java/com/example/chatbot/kafka/KnowledgeEventConsumer.java) — 知识库消费者

### 为什么用 Kafka 而不是 @Async

最初用 `@Async` + 线程池直接在应用内异步写 MySQL。问题：
1. 应用重启时线程池里的任务丢失，聊天记录永久丢失
2. 线程池大小有限，高并发时任务积压或拒绝
3. 紧耦合——邮件发送失败会阻塞线程池

换成 Kafka 后：
1. 消息持久化在 Broker，应用重启不丢
2. 消费者独立部署/重启，不影响生产者
3. 邮件发送失败只影响自己，不阻塞聊天

### Q: 手动 ACK 为什么重要？

Spring Kafka 默认自动 ACK。但消费者处理异常时，如果抛异常不 ACK，Kafka 会无限重试——导致日志刷屏。改为手动 ACK：正常处理 → `ack.acknowledge()`；异常也 → `ack.acknowledge()`，放弃这条消息或记录到死信表。

```java
// NotificationEventConsumer.java:49-55
} catch (Exception e) {
    // 系统异常，记录日志后 ACK（避免无限重试）
    log.error("事件处理失败...", e.getMessage(), e);
    ack.acknowledge();  // 不抛异常，ACK 掉
}
```

### 面试追问

**Q: Kafka 怎么保证消息不丢？**
在 Producer 端设置 `acks=1`（Leader 确认即可），Consumer 端手动 ACK 保证至少处理一次。但当前项目更关注可用性（邮件丢了可以重发），所以异常时直接 ACK，不阻塞后续消息。

**Q: Consumer Group 怎么设计的？**
3 个 Consumer Group 对应 3 个 Topic。每个 Group 独立消费，互不影响。如果邮件消费者挂了，聊天和知识库仍正常工作。

**Q: 为什么不直接用 RabbitMQ？**
Kafka 的持久化更强（磁盘顺序写入），适合事件溯源场景。而且单机 KRaft 模式（去 ZooKeeper）部署简单，和项目已有的技术栈（Spring Cloud Stream）集成方便。

---

## 3. 文件管理微服务 + 文档解析

### 做了什么

新建独立的 file-service 微服务（端口 8081），统一管理所有文件上传、下载、缩略图生成。抽象 `FileStorage` 接口，目前实现 `LocalStorage`（本地磁盘），可切换到 `MinioStorage`（对象存储）。集成 Apache PDFBox + POI 实现 PDF/DOCX/TXT 自动解析。

**核心文件：**
- [FileController.java](file-service/src/main/java/com/example/file/controller/FileController.java) — REST API
- [FileService.java](file-service/src/main/java/com/example/file/service/FileService.java) — 核心逻辑
- [KnowledgeDocParser.java](file-service/src/main/java/com/example/file/service/KnowledgeDocParser.java) — 文档解析
- [FileStorage.java](file-service/src/main/java/com/example/file/storage/FileStorage.java) — 存储抽象接口

### 为什么单独拆一个服务

1. **职责单一：** chatbot-service 管 AI 对话和 RAG，file-service 管文件存储和解析
2. **独立扩展：** 未来文件量大可以独立加磁盘、换 MinIO
3. **解耦部署：** 改文件服务不影响聊天服务

### 文档解析流程

```
用户上传 PDF → POST /api/files/upload/knowledge
  → FileService.uploadKnowledgeDoc()
    → docParser.parse(inputStream, contentType, fileName)
      → PDFBox Loader.loadPDF() 读取
      → PDFTextStripper 提取文本
    → 存入 file_record（元数据）+ 磁盘（原始文件）
  → 返回 { fileKey, parsedContent, charCount }
  → 前端自动填入知识库表单标题+内容
```

### Q: 为什么不用 Elasticsearch 做全文检索？

项目体量小，用户级知识库文档数量有限（几十到几百篇），RAG 关键词评分已经够用，不需要引入额外的 ES 集群增加运维复杂度。未来如果文档量到万级再考虑。

### 面试追问

**Q: 大文件上传怎么处理？**
当前限制 10MB。如果要支持大文件，需要：前端分片上传（chunked upload）、后端合并分片、断点续传（Redis 记录上传进度）。

**Q: PDF 解析失败怎么办？**
`KnowledgeDocParser.parse()` 的 catch 块会抛出 `RuntimeException`，FileController 统一捕获返回 `{success: false, error: "..."}`。不会把半截数据写到数据库。

---

## 4. Redis 缓存 + 异步写回

### 做了什么

用 Redis List 存储聊天历史，读取时先查 Redis，命中直接返回（微秒级）；未命中查 MySQL，查到后通过 `CompletableFuture.runAsync()` 异步写回 Redis。

**核心代码位置：** [ChatbotService.java:147-199](chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java)

### 缓存策略详解（Cache-Aside + Write-Behind 混合）

```java
// 1. 先查 Redis（带性能计时）
long redisStart = System.nanoTime();
List<Object> rawHistory = redisTemplate.opsForList().range(key, 0, -1);

if (rawHistory != null && !rawHistory.isEmpty()) {
    // 场景 A：Redis 命中 → 微秒级返回
    history = rawHistory.stream()...;
    log.info("Redis 命中！耗时: {} 微秒", redisTimeUs);
} else {
    // 场景 B：Redis 未命中 → 降级查 MySQL
    history = chatRecordMapper.selectList(...);

    // 异步补偿写回 Redis（CompletableFuture，不阻塞 HTTP 响应）
    CompletableFuture.runAsync(() -> {
        redisTemplate.opsForList().rightPushAll(key, finalHistory.toArray());
    });
}
```

### Q: 为什么用 Redis List 不是 String？

聊天历史是**有序的、追加的**消息列表。List 天然支持 `rightPushAll`（批量尾部追加）和 `range`（按索引范围读取），完美映射"多轮对话"这个数据结构。用 String 的话需要手动序列化/反序列化整个列表。

### Q: Redis 突然挂了怎么办？

```java
} catch (Exception e) {
    log.warn("Redis 不可用，已降级到 MySQL 查询: {}", e.getMessage());
}
```

不走缓存，直接查 MySQL。写回也包了 try-catch，写失败只记日志不抛异常。保证对话主流程不受影响。

### 面试追问

**Q: 缓存一致性怎么保证？**
目前是最终一致性。新消息到达时通过 Kafka 异步持久化到 MySQL。有 Kafka `KnowledgeEventConsumer` 在知识库更新时主动清掉相关缓存（`redisTemplate.delete(keys)`），下次查询会重新从 MySQL 加载。不是强一致性，但对话场景对一致性要求不高。

**Q: 为什么用 CompletableFuture 而不是 @Async？**
`CompletableFuture.runAsync()` 更轻量，不依赖 `@EnableAsync` 和线程池配置。用 ForkJoinPool.commonPool() 默认线程池。这种"顺手写回"的场景不需要专门的线程池管理。

**Q: 缓存的 TTL 为什么是 2 小时？**
用户一次对话通常不会超过 2 小时。超过 2 小时 Redis 自动过期，下次读取从 MySQL 重新加载。避免长期不活跃的会话占用 Redis 内存。

---

## 5. Semaphore 信号量 + Ollama 并发排队

### 做了什么

Ollama 本地模型是单线程推理，多个请求同时发出直接 `Connection refused`。用 `Semaphore(1)` 串行化请求，`tryAcquire(120s)` 排队等待，SSE 推送排队状态给前端。

**核心代码位置：** [ChatbotService.java:275-283, 384-392, 560-568](chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java)

### 并发控制机制

```java
private final Semaphore ollamaSemaphore = new Semaphore(1);

// 检查排队状态
int available = ollamaSemaphore.availablePermits();
if (available == 0) {
    emitter.send(SseEmitter.event().name("status")
        .data(Map.of("status", "queued", "message", "AI 正在处理其他请求，请稍候…")));
}

// 尝试获取信号量，最多等 120 秒
boolean acquired = ollamaSemaphore.tryAcquire(120, TimeUnit.SECONDS);
if (!acquired) {
    sendStreamError(emitter, "当前排队人数过多，请稍后重试");
    return;
}

try {
    // ... Ollama 推理 ...
} finally {
    ollamaSemaphore.release();  // 无论如何释放信号量
}
```

### Q: 为什么不用队列（BlockingQueue）？

Semaphore 更简单——只有一个许可就是互斥锁。BlockingQueue 适合生产者-消费者模式，但这里没有"排队消息"的需求，只需要"一个一个来"。而且 Semaphore 的 `tryAcquire(timeout)` 天然支持超时控制。

### Q: 为什么 finally 里 release？

```java
} finally {
    ollamaSemaphore.release();
}
```

流式中断（用户点停止按钮）时 `SseEmitter` 抛异常，如果不 release，信号量永远不归还，后续请求全部阻塞。finally 保证无论是正常结束、异常、中断，都释放信号量。

### 面试追问

**Q: Semaphore(1) 和 synchronized 有什么区别？**
Semaphore 支持 `tryAcquire(timeout)` 带超时的等待（synchronized 无限阻塞），可以中断等待（synchronized 不可中断），还可以在运行时动态调整许可数。但 Semaphore(1) 不是可重入的。

**Q: 分布式场景怎么处理？**
单实例 Semaphore 只能控制本进程的并发。如果 chatbot-service 部署多实例，需要用 Redis 分布式锁（SETNX + TTL）或 Redisson 的 RPermitExpirableSemaphore。

**Q: 120 秒超时合理吗？**
Ollama 推理耗时和模型大小、输入长度强相关。120 秒覆盖了大部分场景（小文本几秒，大文本几十秒），极端超时给用户明确反馈"请稍后重试"比无限等待体验好。

---

## 6. JWT 双令牌 + Token 原子轮转

### 做了什么

Access Token（30 分钟）+ Refresh Token（7 天）。Refresh Token 存储在 Redis，刷新时用 `getAndDelete` 原子操作实现 Token 轮转——旧 RefreshToken 删除，新 RefreshToken 写入。前端 `refreshPromise` 互斥锁防止并发刷新。

**核心文件：**
- [JwtTokenProvider.java](chatbot-service/src/main/java/com/example/chatbot/security/JwtTokenProvider.java) — JWT 签发和解析
- [RefreshTokenStore.java](chatbot-service/src/main/java/com/example/chatbot/security/RefreshTokenStore.java) — Redis 刷新令牌存储
- [AuthInterceptor.java](chatbot-service/src/main/java/com/example/chatbot/security/AuthInterceptor.java) — 请求拦截器

### Token 轮转机制

```
登录成功 → 返回 AccessToken(30min) + RefreshToken(7天)
请求 API  → 带 AccessToken
    ↓ 401
前端自动 → POST /api/auth/refresh { refreshToken }
    ↓
服务端: oldToken = Redis.getAndDelete(refreshToken) // 原子删除旧Token
    ↓ 返回新的 Token 对
前端: 用新 AccessToken 重试原请求
```

### Q: 为什么用 getAndDelete 不用 get + delete？

两步操作不是原子的：
```java
// ❌ 非原子，两步之间可能被另一个请求读到
String val = redisTemplate.opsForValue().get(key);
redisTemplate.delete(key);

// ✓ 原子操作
String val = (String) redisTemplate.opsForValue().getAndDelete(key);
```
防止 RefreshToken 重放攻击——同一个 RefreshToken 只能用一次。

### Q: refreshPromise 互斥锁怎么工作？

```javascript
async function tryRefreshToken() {
    if (refreshPromise) {
        await refreshPromise;  // 别人在刷新，等着用结果
        return true;
    }
    refreshPromise = (async () => {
        // 实际刷新请求
        const resp = await fetch('/api/auth/refresh', ...);
        // 更新 localStorage
    })();
    await refreshPromise;
    refreshPromise = null;  // 释放锁
}
```

N 个并发的 401 请求只触发 1 次刷新，其他的等着用同一个结果。

### 面试追问

**Q: JWT 为什么不用 RSA 非对称加密？**
HMAC-SHA256 足够安全且性能高。RSA 适合多服务验证同一个 Token（用公钥验证），当前项目只有 Gateway 需要验证 Token，HMAC 就够了。

**Q: 用户改密码后，旧 Token 怎么失效？**
重置密码时调用 `refreshTokenStore.invalidateAll(userId)` 删除所有 RefreshToken。AccessToken 只有 30 分钟有效期，过期后无法刷新，自然失效。最坏情况 30 分钟内旧 Token 仍可用——这是设计权衡，不想每次请求都查 Redis 验证 Token。

**Q: Token 存在 localStorage 安全吗？**
localStorage 容易受 XSS 攻击。更安全的方式是 httpOnly Cookie。但本项目是学习项目，localStorage 实现简单，前端也做了 XSS 防护（escapeHtml）。生产环境建议 httpOnly Cookie + CSRF Token。

---

## 7. Spring Cloud Gateway 统一网关

### 做了什么

引入 Spring Cloud Gateway（端口 9000）作为统一入口，所有 `/api/**` 请求经过 Gateway → Nacos 服务发现 → 路由到对应服务。Gateway 层做 JWT 鉴权，未登录直接返回 401。

**核心文件：**
- [AuthGlobalFilter.java](gateway/src/main/java/com/example/chatbot/gateway/filter/AuthGlobalFilter.java) — JWT 全局过滤器
- [JwtConfig.java](gateway/src/main/java/com/example/chatbot/gateway/config/JwtConfig.java) — JWT 配置

### 路由设计

```
/api/auth/**     → 不校验（登录、注册、刷新 Token）
/api/chat/health → 不校验
/api/files/**    → 透明转发（file-service 有自己的 CORS）
/api/**          → 需要 JWT Token
```

### Q: 为什么不用 Nginx？之前不是用的 Nginx 吗？

旧版单机部署用 Nginx。切换到 Docker 微服务后，Gateway 更合适：
1. **服务发现：** Nacos 挂了某个服务，Gateway 自动感知，Nginx 需要手动 reload
2. **动态路由：** 新增服务自动注册，不需要改 nginx.conf
3. **代码级鉴权：** Gateway Filter 用 Java 解析 JWT，比 Nginx Lua 方便
4. **统一技术栈：** 运维只需维护一种配置（docker-compose.yml）

### 面试追问

**Q: Gateway 本身挂了怎么办？**
单点问题。生产环境可以 Gateway 多实例 + Nginx 做负载均衡。或者用 Nginx 作为最外层统一入口，后面 Gateway 集群。

**Q: 为什么鉴权不放业务代码里？**
微服务理念——横切关注点抽到网关。业务服务只关注业务逻辑，不用在每个 Controller 里写 Token 校验。新增一个服务也不用配置认证逻辑。

---

## 8. 自研 RAG 关键词评分算法

### 做了什么

不用向量数据库，纯文本匹配评分实现检索增强。三层评分模型 + 中文 2-gram 子词切分。

**核心代码位置：** [RagService.java:170-200](chatbot-service/src/main/java/com/example/chatbot/service/RagService.java)

### 评分模型

```java
private int calculateScore(KnowledgeDocument document, String query, List<String> keywords) {
    // 第一层：完整查询匹配
    if (title.contains(query))   score += 40;   // 标题匹配最值钱
    if (content.contains(query)) score += 30;   // 正文匹配次之
    if (tags.contains(query))    score += 20;   // 标签匹配

    // 第二层：关键词子词匹配
    for (String keyword : keywords) {
        if (title.contains(keyword))   score += 8 + Math.min(keyword.length(), 6);
        if (content.contains(keyword)) score += 4 + Math.min(keyword.length(), 4);
        if (tags.contains(keyword))    score += 5;
    }
    return score;
}
```

### 关键词分词的巧妙之处

```java
// 中文 2-gram 切分
// 输入: "智能客服"
// 切分: ["智能","能客","客服"]
// 这样即使用户输入不完全精确也能匹配到
for (int i = 0; i < normalized.length() - 1; i++) {
    keywords.add(normalized.substring(i, i + 2));
}
```

### Q: 为什么不用向量数据库？

1. **零运维成本：** 不需要额外的 Elasticsearch/Milvus/Weaviate
2. **体量合适：** 用户级知识库（几十到几百篇文档），关键词匹配精度足够
3. **可解释：** 评分结果可直接看到为什么匹配（标题/正文/标签），比向量相似度透明
4. **快速：** 单文档评分亚毫秒，全量扫描一百篇文档也只需几十毫秒

### 面试追问

**Q: 什么时候会考虑换成向量检索？**
文档量级到万级以上，或者需要语义理解（同义词、跨语言）。可以渐进式替换：先加向量索引，混合检索（关键词 + 向量），逐步过渡。

**Q: 怎么确定评分权重（40/30/20）的？**
经验值 + 实验调参。标题权重最高（标题是内容的最浓缩表达），标签次之，正文最低但面积大。可以通过 A/B 测试方式调优。

**Q: 检索失败怎么办？**
```java
catch (Exception e) {
    log.warn("RAG 检索失败，已降级为普通对话");
}
```
不抛异常，降级为普通对话，AI 用自身知识回答。并在 prompt 里要求 AI 说明"以下信息来自知识库"——检索兜底。

---

## 9. 架构容错与模型降级

### 做了什么

两个容错机制：
1. `ObjectProvider.getIfAvailable()` — 模型 Bean 按需获取，不存在不崩溃
2. Kafka 替代 `@Async` 线程池 — 异步事件不掉消息

**核心代码位置：** [ChatbotService.java:69-106](chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java)

### 为什么不用 @Autowired？

```java
// ChatbotService 构造器
public ChatbotService(
    ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
    ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
    ...
) {
    this.openAi = openAiChatModelProvider.getIfAvailable();  // 可能为 null
    this.ollama = ollamaChatModelProvider.getIfAvailable();
}
```

`@Autowired(required=false)` 的问题：你不知道 Bean 为什么不存在，也无法优雅处理。`ObjectProvider.getIfAvailable()` 返回 null，业务代码可以判断"这个模型不可用"并降级。比如 DeepSeek API Key 没配置，应用照常启动，只是 DeepSeek 模型不可选。

### Q: 为什么不用 @Async 了？

最初用 `@Async` + 线程池异步写 MySQL。但 DMP 线程池在容器化环境有线程泄漏风险，Kafka 天然支持消息持久化和重试。而且 Kafka 的 Topic 可以独立监控（消息积压、消费速率），`@Async` 的线程池状态不透明。

### 面试追问

**Q: 如果 Kafka 也挂了呢？**
`chatEventProducer.sendChatEvent()` 内部用 `try-catch`，Kafka 发送失败只记日志不抛异常。聊天主流程不受影响——如果设计为同步等待 Kafka，Kafka 故障会阻塞所有对话。

**Q: 这种容错策略有风险吗？**
有——Kafka 故障期间聊天记录丢失（没有持久化到 MySQL）。但这是有意的设计权衡：**可用性 > 一致性**。对话场景下，偶尔丢几条聊天记录远好于整个系统不可用。故障恢复后可以手动补录。

---

## 10. 多模态图文混合输入

### 做了什么

用户上传图片后，Spring AI 构建多模态消息 `UserMessage(text + Media)`，OllamaOptions 在请求级别覆盖模型（从 qwen 切换到 llava）。

**核心代码位置：** [ChatbotService.java:530-560](chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java)

### 上传流程

```
用户选择/粘贴/拖拽图片
  → 前端调用 file-service POST /api/files/upload → 获得 fileKey
  → 前端调用 chatbot-service POST /api/chat/stream/filekey { message, imageFileKey }
  → ChatbotController → streamChatWithFileKey()
    → FileServiceClient.getFileBytes(fileKey)    // 从 file-service 获取图片
    → FileServiceClient.getFileInfo(fileKey)     // 获取 MIME 类型
    → streamChatWithImageBytes() → 构建多模态 UserMessage
    → ChatClient(ollamaOptions → model("llava")).stream()
```

### Q: 为什么图片不存 MySQL 了？

旧方案把图片 Base64 编码后存 `chat_record.image_data`（LONGTEXT）。问题：
1. Base64 编码膨胀 33%
2. Base64 字符串不可检索、不可缩略图预览
3. 查聊天记录时把图片 Base64 也拉出来，网络传输大

改为 file-service 管理——图片文件独立存储，MySQL 只存元数据（fileKey, originalName, fileSize）。

### Q: 怎么判断用哪个模型？

ChatbotService 里判断：有图片 → llava（视觉模型），纯文本 → 用户选择（deepseek/ollama/qwen）。请求级别覆盖通过 `OllamaOptions.create().withModel("llava:latest")` 实现，不修改 Bean 的全局默认模型。

### 面试追问

**Q: 多模态消息怎么构建的？**
```java
UserMessage userMessage = new UserMessage(
    new Media(new MimeType(contentType), imageBytes),  // 图片
    request.getMessage()  // 文本
);
```

Spring AI 的 Media 类型把图片字节和 MIME 打包，底层适配 OpenAI 和 Ollama 的 API 格式。

**Q: 前端怎么实现粘贴和拖拽上传？**
- 粘贴：监听 `paste` 事件，`event.clipboardData.items` 取图片
- 拖拽：监听 `drop` 事件，`event.dataTransfer.files` 取图片
- 都走同一个 `selectImage(file)` 函数，上传到 file-service 获得 fileKey

---

## 模拟面试流程

### 开场
"请用 3 分钟介绍这个项目，重点讲技术亮点和你遇到的挑战"

**建议回答思路：**
1. 一句话概括项目（AI 智能客服，双模型 + RAG + 微服务）
2. 选 2-3 个最体现技术深度的点深入讲（比如 Kafka 事件驱动、Docker 多阶段构建、Redis 缓存策略）
3. 每个点讲清楚"为什么这样做"而不是"做了什么"
4. 收尾提一个学到的东西或踩过的坑

### 深挖环节（面试官可能追问的方向）

**系统设计类：**
- "如果日活 10 万，哪些地方会先崩？怎么优化？"
  - 瓶颈：Ollama 单线程 → 多实例 + 队列；MySQL 单机 → 读写分离；Redis 单机 → 哨兵/集群
  - 优化：Gateway 加限流；file-service 换 MinIO 减少本地 IO；Kafka 加分区并行消费

- "怎么实现灰度发布？"
  - Nacos 配置中心做流量路由：`version=blue → chatbot-service:8080, version=green → chatbot-service:8081`
  - Gateway 加 Header 路由规则

**数据库类：**
- "MySQL 怎么优化查询？"
  - Explain 分析慢查询，看有没有走索引（`idx_session_id`, `idx_created_time`）
  - 聊天记录按 sessionId 查 → 联合索引 `(session_id, created_time)`

**中间件类：**
- "Redis 内存满了怎么办？"
  - allkeys-lru 淘汰最少使用的 Key
  - 聊天历史 Key 有 TTL 2 小时自动过期
  - 最坏情况可以 FLUSHALL 清空缓存，所有流量打到 MySQL（会慢但不会挂）

**安全类：**
- "有人伪造 JWT 怎么办？"
  - HMAC-SHA256 需要一个 secret key 签名，key 在不泄露的情况下无法伪造
  - Token 有过期时间，即使泄露影响窗口有限
  - 紧急情况改 secret key → 所有 Token 立即失效

## 关键代码索引

| 技术点 | 文件 | 行号 |
|--------|------|------|
| Redis 缓存策略 | ChatbotService.java | 147-199 |
| Semaphore 并发控制 | ChatbotService.java | 53, 275, 384, 560 |
| 模型容错注入 | ChatbotService.java | 69-106 |
| RAG 评分算法 | RagService.java | 170-200 |
| Token 原子轮转 | RefreshTokenStore.java | 全文件 |
| 手动 ACK 策略 | NotificationEventConsumer.java | 28-56 |
| 多模态消息构建 | ChatbotService.java | 530-560 |
| JWT 网关过滤器 | AuthGlobalFilter.java | 全文件 |
| 文档解析 | KnowledgeDocParser.java | 全文件 |
| Docker 多阶段构建 | chatbot-service/Dockerfile | 全文件 |
