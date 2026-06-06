# AGENTS.md - AI Studio 项目快速上下文

这个文件给新的 AI/Codex/Claude 对话使用。目标是让新会话不用重新通读整个项目，也能快速理解项目架构、部署方式、关键约束和常见修改边界。

## 项目概览

AI Studio 是一个基于 Spring Boot 3.2、Spring AI、MyBatis-Plus、Kafka、Redis、MySQL、Nacos、Spring Cloud Gateway 的智能客服系统。

核心能力：
- AI 对话：DeepSeek 云端模型 + Ollama 本地/自部署模型。
- 多模态：图片 + 文本输入时使用 Ollama 的 `llava` 视觉模型。
- RAG：当前是自研关键词评分检索，不是标准 embedding + 向量数据库。
- 用户系统：邮箱验证码、登录注册、JWT Access Token + Refresh Token、RBAC。
- 文件服务：图片/文档上传、下载、缩略图、知识库文档解析。
- 异步可靠性：Kafka 用于聊天记录、通知、知识库缓存刷新等事件驱动场景。
- 微服务入口：生产环境统一走 Gateway，不直接暴露业务服务端口。

## 模块结构

```text
springaI-chatbot/
├── chatbot-service/             # 聊天主服务，内部端口 8080
│   ├── src/main/java/com/example/chatbot/
│   │   ├── controller/           # 页面、认证、聊天、知识库、管理接口
│   │   ├── service/              # ChatbotService、RagService、AuthService 等
│   │   ├── kafka/                # Kafka 生产者、消费者、Topic、错误处理
│   │   ├── security/             # JWT、RBAC、拦截器
│   │   ├── config/               # Redis、WebMvc、AI、MyBatis 等配置
│   │   └── entity/ mapper/ dto/
│   └── src/main/resources/
│       ├── application.yml
│       └── templates/            # login.html、chat.html、admin.html
├── file-service/                 # 文件服务，内部端口 8081
│   ├── src/main/java/com/example/file/
│   │   ├── controller/           # FileController、FileManagerController
│   │   ├── service/              # FileService、ImageProcessor 等
│   │   ├── storage/              # FileStorage、LocalStorage
│   │   └── entity/ mapper/ config/
│   └── src/main/resources/
│       ├── application.yml
│       └── templates/file-manager.html
├── gateway/                      # Spring Cloud Gateway，对外端口 9000
│   ├── src/main/java/com/example/chatbot/gateway/
│   │   ├── filter/               # AuthGlobalFilter、RequestLoggingFilter
│   │   └── config/
│   └── src/main/resources/application.yml
├── docker-compose.yml            # 本地/通用 Docker 编排
├── docker-compose.prod.yml       # 远程服务器生产编排
├── pom.xml                       # Maven 父工程
├── README.md
├── CLAUDE.md                     # Claude 专用上下文，可能存在编码显示问题
└── md/                           # 项目学习、面试复习、技术文档
```

## 当前架构约定

生产环境只允许用户从 Gateway 进入：

```text
浏览器
  -> http://111.229.127.171:9000
  -> gateway
  -> lb://chatbot-service 或 lb://file-service
```

端口约定：
- `gateway`: `9000`，对外公开入口。
- `chatbot-service`: `8080`，只在 Docker 网络内部暴露，不映射宿主机端口。
- `file-service`: `8081`，只在 Docker 网络内部暴露，不映射宿主机端口。
- MySQL、Redis、Kafka、Nacos 当前 compose 仍有端口映射；如果做生产安全加固，优先收紧这些基础设施端口。

不要再让前端硬编码访问 `:8080` 或 `:8081`。页面里的 API、上传、下载、文件管理入口都应走 Gateway 路径，例如：
- `/api/chat/**`
- `/api/auth/**`
- `/api/knowledge/**`
- `/api/admin/**`
- `/api/files/**`
- `/admin/files`

## Gateway 路由重点

文件：`gateway/src/main/resources/application.yml`

关键点：
- Gateway 注册到 Nacos。
- Gateway 开启服务发现路由：`spring.cloud.gateway.discovery.locator.enabled=true`。
- 手写路由使用 `lb://chatbot-service` 和 `lb://file-service`。
- `AuthGlobalFilter` 统一做 JWT 校验。
- 页面和部分公开接口在 `app.auth.exclude-paths` 中放行。

重要路由：
- `/api/auth/**` -> `chatbot-service`
- `/api/chat/**` -> `chatbot-service`
- `/api/knowledge/**` -> `chatbot-service`
- `/api/admin/**` -> `chatbot-service`
- `/api/files/**` -> `file-service`
- `/admin/files` -> `file-service`
- `/`、`/login`、`/chat`、`/admin` -> `chatbot-service`

## Nacos 的作用

本项目中 Nacos 主要用于服务注册与发现：
- `chatbot-service`、`file-service`、`gateway` 启动后注册到 Nacos。
- Gateway 通过 `lb://服务名` 从 Nacos 获取服务实例。
- 当前不是用 Nacos 做复杂配置中心，也不是业务服务之间都强依赖 Nacos。

面试表达可以说：
> 我引入 Nacos 是为了让 Gateway 不再写死下游服务地址，而是通过服务名进行路由。这样服务端口、容器 IP、实例数量变化时，Gateway 仍然通过服务发现找到可用实例，为后续多实例扩容和负载均衡留出口。

## Kafka 约定

Kafka 镜像：`confluentinc/cp-kafka:7.5.0`。

当前 Kafka 使用 KRaft 模式：
- compose 中配置了 `KAFKA_PROCESS_ROLES=broker,controller`。
- 配置了 `KAFKA_CONTROLLER_QUORUM_VOTERS`。
- 没有 ZooKeeper 服务，这是符合 Kafka 3.x/4.x 之后 KRaft 演进方向的。

重点代码位置：
- `chatbot-service/src/main/java/com/example/chatbot/kafka/KafkaTopicConfig.java`
- `chatbot-service/src/main/java/com/example/chatbot/kafka/KafkaConsumerConfig.java`
- `chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventProducer.java`
- `chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventConsumer.java`
- `chatbot-service/src/test/java/com/example/chatbot/kafka/KafkaReliabilityTest.java`

消费者可靠性要点：
- 使用手动 ACK：`spring.kafka.listener.ack-mode=manual_immediate`。
- 消费成功后才确认 offset。
- 消费失败由 `DefaultErrorHandler` 处理重试。
- 达到重试上限后进入 DLT/死信主题，避免消息无限阻塞主消费。

面试表达：
> 我没有依赖自动 ACK，因为自动提交 offset 在异常场景下容易造成消息已经提交但业务没落库。这里改成手动 ACK，业务逻辑成功后再确认 offset；失败时交给 Spring Kafka 的 DefaultErrorHandler 做退避重试，重试仍失败再进入死信队列，保证主链路不被坏消息一直卡住。

## Redis 约定

Redis 主要用途：
- 聊天历史缓存。
- 验证码 TTL 与发送频率限制。
- Refresh Token 存储与轮转。
- 部分热点数据和会话状态缓存。

聊天历史可用 Cache-Aside 思路理解：
- 读取时优先读 Redis。
- 未命中再读数据库。
- 写入时可以先更新缓存，再通过 Kafka/异步流程做持久化或补偿。

如果提到“90% 命中率”，需要有日志或统计支撑；没有埋点时不要硬说精确数字，应该说“从本地测试和重复会话场景观察，热点会话大多能命中缓存，但生产上应增加 hit/miss 指标再量化”。

## JWT 与刷新机制

本项目是双 Token：
- Access Token：短期令牌，默认 30 分钟，用于接口鉴权。
- Refresh Token：长期令牌，默认 7 天，存 Redis，用于换取新的 Access Token。

刷新触发：
- 前端请求接口收到 Access Token 过期/无效响应。
- `authFetch()` 尝试调用 `/api/auth/refresh`。
- 前端用 `refreshPromise` 防止同一个浏览器会话里多个并发请求同时刷新。

注意：
- 每个用户、每个登录会话都有自己的 Refresh Token。
- 用户之间不会共享 Refresh Token，也不会互相冲突。
- `refreshPromise` 解决的是同一个前端页面内并发请求的刷新竞争，不是所有用户排队。

## 多模态实现

多模态入口通常在 `chatbot-service`：
- 前端 `chat.html` 上传图片到 `file-service`。
- 文件服务返回 `fileKey`。
- 聊天请求携带文本和图片信息。
- 后端判断存在图片时，切换到视觉模型 `app.chatbot.vision-model`，默认 `llava:latest`。
- 文本-only 对话走普通模型，例如 DeepSeek 或 Ollama qwen。

关键配置：
- `chatbot-service/src/main/resources/application.yml`
  - `spring.ai.ollama.base-url`
  - `app.chatbot.vision-model: llava:latest`
  - `file.service.url`

面试表达：
> 我不是让所有请求都走视觉模型，而是在请求携带图片时才进入多模态链路。图片先由 file-service 做上传、存储和权限隔离，聊天服务拿到图片引用后构造图文输入，并切换到 llava 视觉模型处理；普通文本请求仍走文本模型，避免视觉模型占用过高导致整体吞吐下降。

## RAG 实现

当前项目 RAG 不是标准 embedding + 向量数据库，而是自研关键词检索评分。

特点：
- 标题、正文、标签分别加权。
- 中文场景下使用关键词/子串/2-gram 等方式增强召回。
- Top-K 可配置：`app.chatbot.rag-top-k`。
- 检索失败或无结果时降级为普通对话。

适合面试解释：
> 这个项目的数据规模和语义复杂度还没有到必须引入向量数据库的程度。我先用轻量关键词评分做可解释、低成本、容易部署的 RAG，优点是没有额外中间件，调试简单，召回结果可解释。后续如果文档规模扩大、问法更开放，再演进到 embedding + 向量数据库，比如 Milvus、Qdrant 或 Elasticsearch dense vector。

## 文件服务与安全

`file-service` 负责：
- 文件上传。
- 文件下载。
- 缩略图生成。
- 文件元数据入库。
- 文件管理页面 `/admin/files`。

安全约定：
- 文件记录保存上传者 `uploaderId`。
- 前端请求附带 `Authorization: Bearer <token>` 和/或 `X-Auth-UserId`。
- 后端应通过 `canAccess()` 等逻辑校验用户是否有权限访问文件。
- 下载路径经过 Gateway：`/api/files/download/**`。

注意：浏览器直接打开图片/下载链接时不方便携带 Authorization header，所以项目中对下载路径有特殊放行。要重点确认 file-service 自身仍有用户隔离校验，不能只依赖 Gateway 放行。

## 部署方式

远程服务器：
- IP：`111.229.127.171`
- 用户：`ubuntu`
- 项目路径：`/opt/springai-chatbot`
- Docker 在远程服务器上，不在本机。

常用部署命令：

```bash
ssh ubuntu@111.229.127.171
cd /opt/springai-chatbot
docker compose -f docker-compose.prod.yml up -d --build
docker compose -f docker-compose.prod.yml ps
```

如果只改了某个服务，可以重建单个服务：

```bash
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
docker compose -f docker-compose.prod.yml up -d --build file-service
docker compose -f docker-compose.prod.yml up -d --build chatbot-gateway
```

严禁在未确认的情况下执行：

```bash
docker compose down -v
docker volume rm ...
rm -rf /opt/springai-chatbot
```

这些操作可能删除 MySQL、Redis、Kafka、Nacos 或上传文件数据。

## 本地验证命令

常用：

```bash
mvn -q -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
```

Kafka 可靠性测试：

```bash
mvn -q -pl chatbot-service -Dtest=KafkaReliabilityTest test
```

检查是否还有硬编码端口：

```bash
rg "8080|8081" chatbot-service/src/main/resources gateway/src/main/resources file-service/src/main/resources
```

注意：`README.md` 或旧文档里可能仍有历史端口说明。判断当前行为时优先看 compose、application.yml 和实际代码。

## 面试最值得讲的项目亮点

优先讲这三点：

1. 微服务拆分与统一入口
   - 拆分 `chatbot-service`、`file-service`、`gateway`。
   - Gateway + Nacos 实现统一路由和服务发现。
   - 隐藏 8080/8081，避免前端绕过网关访问业务服务。

2. 可靠异步与缓存一致性
   - Kafka 解耦聊天记录、通知、知识库刷新等非主链路任务。
   - 手动 ACK + `DefaultErrorHandler` + 重试 + DLT。
   - Redis 缓存热点会话，结合异步持久化提升响应速度。

3. AI 工程化落地
   - Spring AI 统一接入 DeepSeek 和 Ollama。
   - 文本请求和图文请求分流，图文使用 llava。
   - RAG 先采用轻量可解释检索，后续可演进到 embedding + 向量数据库。

## 常见面试追问答法

### 为什么用 Kafka，不用 RabbitMQ？

可以答：
> 我的场景更偏事件流和异步可靠处理，比如聊天记录持久化、通知、知识库刷新等。Kafka 在高吞吐、顺序日志、重放能力和横向扩展上更适合这种事件驱动架构。RabbitMQ 更适合复杂路由和传统任务队列。如果只是简单发邮件，RabbitMQ 也可以；但考虑后续聊天事件分析、消息重放、日志型数据处理，Kafka 更合适。

### 为什么聊天历史偶尔丢一条也要 Kafka + DLT？

可以答：
> 从用户体验看，偶尔丢一条可能不致命，但从系统设计看，聊天记录会影响上下文、审计、统计和后续 RAG 数据沉淀。Kafka 重试和 DLT 不是为了把简单事情复杂化，而是把失败从主链路剥离出来：用户请求尽快返回，失败消息可重试、可观测、可补偿，不会静默丢失。

### 最大挑战是什么？

建议答“从单体同步调用演进到微服务异步架构后的可靠性问题”：
> 最大挑战是 AI 对话链路本身耗时长，又有聊天记录、文件、知识库、通知等附属流程。如果都同步做，用户等待时间长，失败也会互相影响。我先用 CompletableFuture 做异步化，后来引入 Kafka，把非主链路任务事件化；同时用手动 ACK、重试和死信队列处理失败消息。这个过程中我最大的收获是：异步不是简单开线程，而是要考虑失败重试、幂等、可观测和数据一致性。

### 如何构建上下文？

可以答：
> 上下文由三部分组成：系统提示词、最近 N 轮聊天历史、RAG 检索结果。系统提示词定义助手角色和回复规则；聊天历史来自 Redis/数据库，用 sessionId 和 userId 做隔离；RAG 根据用户问题召回知识文档 Top-K，再拼接到 prompt 中。这样既保留多轮对话连续性，又能把用户自己的知识库内容注入模型。

## 修改代码时的注意事项

- 不要把业务服务重新暴露到宿主机 `8080` 或 `8081`。
- 前端页面不要硬编码 `http://host:8081`，统一使用 Gateway 相对路径。
- 修改 JWT 逻辑时要同时检查 Gateway 和 chatbot-service 的密钥配置是否一致。
- 修改 Kafka 消费逻辑时要检查 ACK、重试、DLT 和测试。
- 修改文件下载放行规则时要重新检查文件权限隔离。
- 不要提交 `.env`、密钥、真实 API Key、邮箱授权码。
- 不要随意改 `.idea`、历史文档或无关文件，除非用户明确要求。
- 远程部署前先本地跑 `mvn -q -DskipTests package` 或至少跑对应模块构建。

## 当前已知风险与后续优化

- 生产 compose 中基础设施端口仍公开映射，建议后续只保留 Gateway 入口。
- Ollama/llava 推理资源受限，当前更适合单机或低并发演示；生产可改为云端 GPU 推理服务或独立模型服务。
- RAG 当前没有向量库，语义召回能力有限；数据规模扩大后可演进到 embedding + vector DB。
- 文件下载路径为了浏览器直开有放行，需要确保 file-service 内部权限校验足够严格。
- Redis 命中率、Kafka DLT 数、AI 请求耗时等指标应继续补埋点和日志，便于面试和生产排障。
