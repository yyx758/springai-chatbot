# CLAUDE.md - AI Studio 项目上下文

## 项目概述

AI 智能客服系统，Spring Boot 3.2 + Spring AI，Docker 容器化微服务架构。

**3 个微服务：**
- `chatbot-service` (:8080，仅 Docker 内部暴露) — AI 对话、知识库、聊天历史
- `file-service` (:8081，仅 Docker 内部暴露) — 文件上传/下载、文档解析、缩略图
- `gateway` (:9000，对外统一入口) — 统一入口、JWT 鉴权、Nacos 服务发现

**4 个基础设施：**
- MySQL (:3307) — 数据持久化
- Redis (:6379) — 缓存聊天历史
- Kafka (:9092) — 事件驱动（聊天记录持久化、邮件通知、知识库缓存刷新）
- Nacos (:8848) — 服务注册与发现

---

## 本地开发

```bash
# 启动所有服务（首次会自动构建镜像）
docker compose up -d

# 代码修改后重新部署（必须加 --build）
docker compose up -d --build

# 只重建某个服务
docker compose up -d --build chatbot-service

# 查看日志
docker compose logs -f chatbot-service

# 停止所有服务（数据不丢，Volume 保留）
docker compose down

# 停止并删除数据（慎用！MySQL/Redis/Kafka 数据全删）
docker compose down -v
```

---

## 远程服务器部署

**服务器：** 111.229.127.171（腾讯云轻量，4GB 内存）
**用户：** ubuntu
**SSH：** 密钥认证（~/.ssh/id_rsa）

### 部署流程

```bash
# 1. 传源码到服务器（在本地 PowerShell 执行）
scp -i ~/.ssh/id_rsa -r chatbot-service/src ubuntu@111.229.127.171:/opt/springai-chatbot/
# 如果改了 file-service 或 gateway，同样传对应目录
scp -i ~/.ssh/id_rsa -r file-service/src ubuntu@111.229.127.171:/opt/springai-chatbot/
scp -i ~/.ssh/id_rsa -r gateway/src ubuntu@111.229.127.171:/opt/springai-chatbot/

# 2. SSH 到服务器重建
ssh -i ~/.ssh/id_rsa ubuntu@111.229.127.171
cd /opt/springai-chatbot
docker compose up -d --build chatbot-service
```

### 注意事项

- **`--build` 必须加**：否则 Docker 用缓存的旧镜像，代码改了不会生效
- **容器重启数据不丢**：MySQL/Redis/Kafka 数据存在 Docker Volume 里
- **不要运行 `docker compose down -v`**：会删除所有数据
- **不要删除任意服务**：每个服务都是系统必需的，未经确认不要删除
- **端口开放**：对外只需开放 9000 端口；8080/8081 仅 Docker 内部访问，8848 仅调试 Nacos 时开放
- **Maven 构建在 Docker 内完成**：不需要手动 mvn package，Docker 自动编译

### Docker 构建流程

```
Dockerfile 第一阶段：maven:3.9-eclipse-temurin-17
  → COPY 源码 → mvn package → 得到 jar

Dockerfile 第二阶段：eclipse-temurin:17-jre-alpine
  → COPY jar → ENTRYPOINT java -jar app.jar
```

所以只需要传源码，Docker 自动完成编译+打包+构建镜像。

---

## 安全约束

- **不要删除任意服务**：MySQL、Redis、Kafka、Nacos、chatbot-service、file-service、gateway 都不能删
- **高风险操作需确认**：docker-compose down -v、删除容器、修改数据库等操作需要用户确认
- **不要直接操作生产数据库**：除非用户明确要求

---

## 关键技术点

- **Redis 缓存策略**：Cache-Aside + Write-Behind，CompletableFuture 异步写回
- **Kafka 事件驱动**：替代 @Async 线程池，手动 ACK 防止无限重试
- **JWT 双令牌**：Access Token (30min) + Refresh Token (7天)，原子轮转
- **Semaphore 并发控制**：Ollama 单线程推理排队，120s 超时
- **文件用户隔离**：X-Auth-UserId header + canAccess() 权限校验
- **RAG 关键词评分**：自研三层评分模型 + 中文 2-gram 分词，无向量数据库

---

## 代码结构

```
springaI-chatbot/
├── chatbot-service/          # AI 对话主服务
│   ├── src/main/java/com/example/chatbot/
│   │   ├── kafka/            # Kafka 生产者/消费者
│   │   ├── security/         # JWT、AuthInterceptor
│   │   ├── service/          # ChatbotService, RagService
│   │   └── controller/       # REST API
│   └── src/main/resources/
│       ├── application.yml
│       └── templates/        # chat.html, admin.html, login.html
├── file-service/             # 文件管理微服务
│   ├── src/main/java/com/example/file/
│   │   ├── controller/       # FileController
│   │   ├── service/          # FileService, KnowledgeDocParser
│   │   └── storage/          # FileStorage 接口, LocalStorage
│   └── src/main/resources/
│       └── templates/        # file-manager.html
├── gateway/                  # Spring Cloud Gateway
├── docker-compose.yml        # 开发环境编排
├── docker-compose.prod.yml   # 生产环境（内存调优）
└── .env                      # 环境变量（不提交 git）
```
