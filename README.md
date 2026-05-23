# AI Studio — 智能客服聊天机器人

基于 Spring Boot + Spring AI + MyBatis-Plus 的微服务智能客服系统，支持多模型 AI 对话、多模态图文混合输入、RAG 检索增强、邮箱验证码注册、RBAC 权限管理和管理后台。

## 目录

- [版本演进](#版本演进)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [功能列表](#功能列表)
- [项目结构](#项目结构)
- [快速开始（Docker 一键部署）](#快速开始docker-一键部署)
- [本地开发](#本地开发)
- [环境变量](#环境变量)
- [API 文档](#api-文档)
- [数据库设计](#数据库设计)
- [安全设计](#安全设计)
- [远程 Linux 服务器部署](#远程-linux-服务器部署)

---

## 版本演进

| 版本 | 关键变更 |
|------|---------|
| v19 | Docker 容器化部署 + 文件管理微服务 + Maven 多模块 + Kafka/Nacos 集成 |
| v18 | 多模态图文混合输入（Ollama llava 视觉模型） |
| v17 | 忘记密码找回 + 多人并发 Ollama 排队控制 |
| v16 | RBAC + RefreshToken + 邮箱验证码 + 管理后台 + RAG |
| v15 | 登录注册 + 管理员管理 |
| v14 | RAG 检索增强 |

---

## 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 | 主框架 |
| 微服务 | Spring Cloud Gateway + Nacos | 网关路由 + 服务注册发现 |
| 消息队列 | Kafka (KRaft 模式) | 事件驱动异步通信 |
| AI 集成 | Spring AI + DeepSeek + Ollama | 多模型对话、流式输出（SSE） |
| ORM | MyBatis-Plus 3.5 | 分页、Lambda 查询 |
| 数据库 | MySQL + Flyway 迁移 | 持久化 + 版本管理 |
| 缓存 | Redis | 会话缓存、Token 存储、验证码 |
| 认证安全 | JWT + BCrypt + RBAC | 双 Token 刷新、角色权限控制 |
| 邮件 | Spring Mail（SMTP） | 邮箱验证码注册 |
| 模板引擎 | Thymeleaf + Bootstrap 5 | 服务端渲染页面 |
| 容器化 | Docker + Docker Compose | 一键部署 7 个服务 |
| CI/CD | GitHub Actions | 自动构建、Docker 镜像 |

---

## 系统架构

### Docker 微服务架构（v19）

```
                    ┌─────────────────────────────────────────┐
                    │           Docker Compose                 │
                    │                                          │
用户浏览器            │  ┌───────────────┐  ┌────────────────┐ │
:8080/:9000 ─────────┼─►│chatbot-service│  │  file-service  │ │
                    │  │    :8080      │  │    :8081       │ │
                    │  │  聊天主服务    │  │  文件管理服务   │ │
                    │  └───┬───────┬───┘  └──┬────┬────────┘ │
                    │      │       │         │    │          │
                    │      ▼       ▼         │    │          │
                    │  ┌──────┐ ┌──────┐     │    │          │
                    │  │MySQL │ │Redis │◄────┘    │          │
                    │  │:3306 │ │:6379 │          │          │
                    │  └──────┘ └──────┘          │          │
                    │      │                      │          │
                    │      ▼                      │          │
                    │  ┌──────────────────────┐   │          │
                    │  │       Kafka          │◄──┘          │
                    │  │ 事件驱动异步通信       │              │
                    │  └──────────────────────┘              │
                    │                                        │
                    │  ┌──────────────────────┐              │
                    │  │       Nacos          │              │
                    │  │  服务注册 + 配置中心   │              │
                    │  └──────────────────────┘              │
                    │           ▲                            │
                    │           │                            │
                    │  ┌──────────────────────┐              │
                    │  │  chatbot-gateway     │              │
                    │  │     :9000            │              │
                    │  │  统一路由 + JWT 鉴权  │              │
                    │  └──────────────────────┘              │
                    └─────────────────────────────────────────┘
```

### 模型访问

| 模型 | 运行位置 | 说明 |
|------|---------|------|
| **Ollama** (qwen2.5) | 远程 Linux 服务器 | 本地文本模型 |
| **Ollama** (llava) | 远程 Linux 服务器 | 本地视觉模型，多模态图文分析 |
| **DeepSeek** | 云端 API | 云端大模型 |

---

## 功能列表

### AI 对话

- **双模型支持**：DeepSeek（云端）+ Ollama（本地），运行时切换
- **多模态图文输入**：支持图片+文字混合输入，自动路由到 Ollama llava 视觉模型
- **流式对话**：POST /api/chat/stream，SSE 协议，打字机效果
- **上下文记忆**：多轮对话上下文，可配置最大历史轮数
- **会话管理**：多会话隔离，会话历史查询与删除
- **图片存储**：Base64 存储改为 file-service 独立管理

### RAG 检索增强

- **关键词匹配评分**：标题 +40、正文 +30、标签 +20、子词加权
- **可配置 Top-K**：支持 1/3/5 个召回结果
- **降级保护**：检索失败时自动降级为普通对话

### 文件管理（file-service）

- **文件上传/下载/删除**：REST API，支持图片/文档
- **缩略图生成**：上传图片自动生成 200x200 缩略图
- **文件管理后台**：Web UI 查看、预览、管理所有文件
- **存储抽象**：支持本地磁盘 / MinIO 切换

### 用户认证

- **邮箱验证码注册**：6 位验证码，5 分钟有效，60 秒发送间隔
- **忘记密码重置**：邮箱验证后重置 + 强制所有设备重新登录
- **JWT 双令牌**：Access Token（30 分钟）+ Refresh Token（7 天）
- **Token 轮转**：Redis 原子操作防重放
- **静默刷新**：前端 authFetch() 自动刷新，用户无感知

### RBAC 权限管理

- **双角色**：USER / ADMIN
- **注解驱动**：@RequireRole("ADMIN")
- **拦截器检查**：不引入 Spring Security

### 管理后台

- 用户管理（角色切换、启用/禁用、删除）
- 文档管理（全部知识文档查看/删除）
- 系统统计（用户总数、对话总数、文档数）

---

## 项目结构

```
springai-chatbot/
├── docker-compose.yml              # Docker 一键部署编排
├── DOCKER.md                       # Docker 部署说明
├── pom.xml                         # 父 POM（多模块管理）
├── .env                            # 环境变量（不提交到 Git）
│
├── chatbot-service/                # 聊天主服务（:8080）
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/chatbot/
│       ├── ChatbotApplication.java
│       ├── config/                 # 全局异常、MyBatis-Plus、Redis、Security、Vision、WebMvc
│       ├── controller/             # Admin、Auth、Chatbot、Knowledge、Page
│       ├── dto/                    # 请求/响应 DTO
│       ├── entity/                 # ChatRecord、KnowledgeDocument、UserAccount
│       ├── kafka/                  # Kafka Producer / Consumer / Config
│       ├── mapper/                 # MyBatis-Plus Mapper
│       ├── security/               # JWT、RBAC、拦截器
│       └── service/                # Admin、Auth、Chatbot、Email、Rag、FileServiceClient
│
├── file-service/                   # 文件管理服务（:8081）
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/file/
│       ├── FileServiceApplication.java
│       ├── config/                 # CORS、Storage
│       ├── controller/             # FileController、FileManagerController
│       ├── entity/                 # FileRecord
│       ├── mapper/                 # FileRecordMapper
│       ├── service/                # FileService、ImageProcessor
│       └── storage/                # FileStorage、LocalStorage
│
└── gateway/                        # API 网关（:9000）
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/example/chatbot/gateway/
        ├── GatewayApplication.java
        ├── config/                 # JwtConfig
        └── filter/                 # AuthGlobalFilter、RequestLoggingFilter
```

---

## 快速开始（Docker 一键部署）

只需 Docker Desktop + 一条命令：

```bash
git clone https://github.com/yyx758/springai-chatbot.git
cd springai-chatbot

# 配置 .env 文件
# 编辑 .env，填入 DeepSeek API Key 和 SMTP 邮箱信息

# 一键启动
docker-compose up -d
```

启动后访问：

| 地址 | 功能 |
|------|------|
| http://localhost:8080 | 聊天主页面 |
| http://localhost:8080/admin | 管理后台 |
| http://localhost:8081/admin/files | 文件管理 |
| http://localhost:9000 | 网关入口 |

**停止服务：**

```bash
docker-compose down           # 停止容器，保留数据
docker-compose down -v        # 停止容器，清除所有数据
```

---

## 本地开发

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.8+

### 1. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS chatbot DEFAULT CHARSET utf8mb4;
```

### 2. 配置环境变量

```bash
export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
export SPRING_AI_OPENAI_ENABLED=true
export APP_JWT_SECRET="你的JWT密钥-至少32个字符"
export SMTP_HOST="smtp.qq.com"
export SMTP_PORT="587"
export SMTP_USERNAME="你的邮箱@qq.com"
export SMTP_PASSWORD="QQ邮箱授权码"
```

### 3. 启动

```bash
# 启动聊天主服务
mvn -pl chatbot-service spring-boot:run

# 启动文件服务（另一个终端）
mvn -pl file-service spring-boot:run

# 启动网关（另一个终端）
mvn -pl gateway spring-boot:run
```

### 4. 设置管理员

```sql
UPDATE user_account SET role = 'ADMIN' WHERE username = '你的用户名';
```

---

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPSEEK_API_KEY` | — | DeepSeek API 密钥 |
| `SPRING_AI_OPENAI_ENABLED` | false | 是否启用 DeepSeek |
| `APP_JWT_SECRET` | — | JWT 签名密钥（生产环境必须修改，≥ 32 位） |
| `APP_TOKEN_EXPIRE_MS` | 1800000 | Access Token 过期时间（毫秒） |
| `APP_REFRESH_TOKEN_EXPIRE_MS` | 604800000 | Refresh Token 过期时间（毫秒） |
| `SMTP_HOST` | smtp.qq.com | SMTP 服务器地址 |
| `SMTP_PORT` | 587 | SMTP 端口 |
| `SMTP_USERNAME` | — | 发件邮箱地址 |
| `SMTP_PASSWORD` | — | 发件邮箱授权码 |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | localhost:9092 | Kafka 地址（Docker 内自动设为 kafka:29092） |
| `NACOS_SERVER_ADDR` | localhost:8848 | Nacos 地址 |

---

## API 文档

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| chatbot-service | 8080 | 聊天 + 认证 + 知识库 + 管理 |
| file-service | 8081 | 文件上传/下载/管理 |
| gateway | 9000 | 统一入口 + 路由转发 |

### 认证接口 `/api/auth`

```
POST /api/auth/send-code         发送邮箱验证码
POST /api/auth/register          注册新用户
POST /api/auth/login             用户登录
POST /api/auth/refresh           刷新令牌
POST /api/auth/forgot-password   发送重置密码验证码
POST /api/auth/reset-password    重置密码
GET  /api/auth/me                获取当前用户信息（需认证）
```

### 聊天接口 `/api/chat`

```
POST /api/chat/message              同步聊天（需认证）
POST /api/chat/stream               SSE 流式聊天（需认证）
POST /api/chat/stream/multipart     SSE 流式聊天 + 图片（需认证）
GET  /api/chat/records              聊天记录分页（需认证）
GET  /api/chat/history/{sessionId}  会话历史（需认证）
DELETE /api/chat/{sessionId}        删除会话（需认证）
GET  /api/chat/health               健康检查
```

### 文件接口 `/api/files`（file-service :8081）

```
POST   /api/files/upload              上传文件
GET    /api/files/download/{fileKey}  下载文件
GET    /api/files/{fileKey}/info      文件信息
DELETE /api/files/{fileKey}           删除文件
POST   /api/files/batch               批量查询
```

### 知识库 `/api/knowledge`

```
POST   /api/knowledge/documents       创建文档（需认证）
GET    /api/knowledge/documents       文档列表（需认证）
DELETE /api/knowledge/documents/{id}  删除文档（需认证）
POST   /api/knowledge/search          检索测试（需认证）
```

### 管理接口 `/api/admin`

```
GET    /api/admin/stats               系统统计（需 ADMIN）
GET    /api/admin/users               用户列表（需 ADMIN）
PUT    /api/admin/users/{id}/role     修改角色（需 ADMIN）
PUT    /api/admin/users/{id}/enabled  启/禁用用户（需 ADMIN）
DELETE /api/admin/users/{id}          删除用户（需 ADMIN）
```

---

## 数据库设计

### user_account

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT PK | 主键 |
| username | VARCHAR(64) UNIQUE | 用户名 |
| email | VARCHAR(128) UNIQUE | 邮箱 |
| password_hash | VARCHAR(255) | BCrypt 密码哈希 |
| display_name | VARCHAR(64) | 显示名称 |
| role | VARCHAR(16) | USER / ADMIN |
| enabled | TINYINT(1) | 是否启用 |
| created_time | TIMESTAMP | 创建时间 |
| updated_time | TIMESTAMP | 更新时间 |

### chat_record

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT PK | 主键 |
| user_message | TEXT | 用户消息 |
| bot_response | TEXT | AI 回复 |
| image_data | LONGTEXT | 图片数据（Base64） |
| session_id | VARCHAR(255) | 会话 ID |
| created_time | DATETIME | 创建时间 |

### knowledge_document

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT PK | 主键 |
| user_id | BIGINT | 所属用户 |
| title | VARCHAR(128) | 标题 |
| content | TEXT | 正文 |
| tags | VARCHAR(256) | 标签 |
| enabled | TINYINT(1) | 是否启用 |
| created_time | TIMESTAMP | 创建时间 |
| updated_time | TIMESTAMP | 更新时间 |

### file_record（file-service）

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT PK | 主键 |
| file_key | VARCHAR(255) UNIQUE | 文件唯一标识 |
| original_name | VARCHAR(500) | 原始文件名 |
| content_type | VARCHAR(100) | MIME 类型 |
| file_size | BIGINT | 文件大小 |
| storage_path | VARCHAR(1000) | 存储路径 |
| thumbnail_key | VARCHAR(255) | 缩略图键 |
| uploader_id | BIGINT | 上传者 ID |
| biz_type | VARCHAR(50) | 业务类型 |
| created_time | DATETIME | 创建时间 |

---

## 安全设计

| 措施 | 实现 |
|------|------|
| 密码加密 | BCrypt |
| 验证码有效期 | Redis TTL 5 分钟 |
| 发送频率限制 | Redis SETNX 60 秒 |
| Token 轮转 | Redis getAndDelete 原子操作 |
| 角色检查 | @RequireRole + Interceptor |
| 密码重置 | 邮箱验证 + 吊销所有已签发 Token |
| 用户禁用 | 登录被拒 + RefreshToken 可被吊销 |
| Ollama 并发 | Semaphore(1) + 120s 超时 |
| 防并发刷新 | 前端 refreshPromise 互斥锁 |
| Gateway 鉴权 | JWT 统一校验，未认证拒绝转发 |

---

## 远程 Linux 服务器部署

### 环境准备

```bash
# JDK 17
sudo apt install openjdk-17-jdk -y

# MySQL 8.0 + Redis
sudo apt install mysql-server redis-server -y

# Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Cloudflare Tunnel
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o /usr/local/bin/cloudflared
chmod +x /usr/local/bin/cloudflared
```

### 初始化

```bash
sudo mysql -e "CREATE DATABASE IF NOT EXISTS chatbot DEFAULT CHARSET utf8mb4;"
ollama pull qwen2.5:0.5b
ollama pull llava:latest
```

### Docker 部署

```bash
git clone https://github.com/yyx758/springai-chatbot.git
cd springai-chatbot
# 编辑 .env 配置密钥和邮箱
docker-compose up -d
```

### JAR 包部署

```bash
mvn clean package -DskipTests
export DEEPSEEK_API_KEY="你的密钥"
export APP_JWT_SECRET="随机32位字符串"
export SMTP_USERNAME="你的邮箱"
export SMTP_PASSWORD="授权码"
nohup java -jar chatbot-service/target/chatbot-service-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```
