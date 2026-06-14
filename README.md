# AI Studio — AI Agent 智能客服平台

基于 Spring Boot 3.2 + Spring AI + Spring Cloud Gateway 搭建的 AI Agent 智能客服系统，具备**工具调用治理**、**三模式 RAG 检索增强**、**多模型对话**、**微服务部署**等能力。

## 目录

- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [核心功能](#核心功能)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [环境变量](#环境变量)
- [API 文档](#api-文档)
- [安全设计](#安全设计)
- [部署](#部署)

---

## 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 | 主框架 |
| AI 集成 | Spring AI + DeepSeek + Ollama | 多模型对话、流式输出（SSE）、Tool Calling |
| 微服务 | Spring Cloud Gateway + Nacos | 统一入口、JWT 鉴权、服务注册发现 |
| 消息队列 | Kafka (KRaft 模式) | 事件驱动异步通信，手动 ACK + 死信队列 |
| 向量数据库 | PGVector | 语义向量检索（HNSW 索引 + 余弦相似度） |
| 搜索引擎 | Elasticsearch 8.15 | RAG 关键词召回，生产默认启用，可自动降级 |
| ORM | MyBatis-Plus 3.5 | 分页、Lambda 查询 |
| 数据库 | MySQL 8.0 + Flyway | 持久化 + 版本管理 |
| 缓存 | Redis 7 | 聊天历史缓存、Token 存储、验证码 |
| 认证安全 | JWT + BCrypt + RBAC | 双 Token 刷新、角色权限控制 |
| 容器化 | Docker + Docker Compose | 一键部署 7 个服务 |
| CI/CD | GitHub Actions | 自动构建、Docker 镜像 |

---

## 系统架构

```
                         ┌──────────────────────────────────────────────┐
                         │              Docker Compose                  │
                         │                                              │
 用户浏览器               │  ┌───────────────┐  ┌────────────────────┐  │
 :9000 ─────────────────┼─►│   gateway     │  │                    │  │
                         │  │   :9000       │  │  chatbot-service   │  │
                         │  │ 统一入口+鉴权  │─►│    :8080           │  │
                         │  └───────────────┘  │ AI对话/Agent/RAG   │  │
                         │                     └──┬────┬────┬──────┘  │
                         │                        │    │    │         │
                         │  ┌────────────────────┐│    │    │         │
                         │  │   file-service     ││    │    │         │
                         │  │     :8081          │◄┘    │    │         │
                         │  │ 文件上传/解析/存储  │      │    │         │
                         │  └────────────────────┘      │    │         │
                         │                              ▼    ▼         │
                         │  ┌──────┐ ┌──────┐ ┌────────────────┐     │
                         │  │MySQL │ │Redis │ │     Kafka      │     │
                         │  │:3307 │ │:6379 │ │ 事件驱动通信    │     │
                         │  └──────┘ └──────┘ └────────────────┘     │
                         │                         ┌────────────┐    │
                         │                         │   Nacos    │    │
                         │                         │ 服务注册发现 │    │
                         │                         └────────────┘    │
                         │                         ┌────────────┐    │
                         │                         │  PGVector  │    │
                         │                         │ 向量检索    │    │
                         │                         └────────────┘    │
                         │                         ┌────────────┐    │
                         │                         │ElasticSearch│   │
                         │                         │关键词召回   │    │
                         │                         └────────────┘    │
                         └──────────────────────────────────────────────┘
```

### 模型访问

| 模型 | 运行位置 | 说明 |
|------|---------|------|
| **DeepSeek** | 云端 API（OpenAI 兼容） | 默认文本模型 |
| **Ollama** (qwen2.5) | 本地/服务器 | 可切换的本地文本模型 |
| **Ollama** (llava) | 本地/服务器 | 视觉模型，多模态图文分析 |

---

## 核心功能

### 一、AI Agent 工具治理框架

基于 Spring AI `@Tool` 注解实现的 Agent 框架，具备完整的工具治理能力：

- **三级工具风险分级**：READ_ONLY / LOW_RISK_WRITE / REQUIRE_CONFIRMATION，每次调用自动写入审计日志
- **Pending Action 两阶段确认**：危险操作（如删除）创建待确认记录，用户手动确认后才执行，10 分钟过期自动失效
- **SSE 实时工具状态推送**：工具执行生命周期（started / completed / failed）实时推送到前端
- **7 类 Agent 工具**：知识读写、文件读取、聊天历史、时间查询、工作区操作、网页抓取

### 二、三模式 RAG 检索增强

- **关键词评分**：自研三层评分模型（标题 40 / 正文 30 / 标签 20）+ 中文 2-gram 子词切分
- **向量检索**：PGVector HNSW 索引 + 余弦相似度，边界感知分块（chunk_size=800, overlap=100）
- **Elasticsearch 关键词召回**：优先使用 ES `multi_match` 检索标题、标签、正文，标题权重最高
- **混合模式**：PGVector 语义召回 + Elasticsearch/MySQL 关键词召回 + HybridRanker 融合排序
- **自动降级**：ES 不可用时回退 MySQL FULLTEXT；向量检索失败时 fallback 到关键词模式
- **事件驱动索引**：文档变更通过 Kafka 异步触发 PGVector 与 Elasticsearch 索引更新

### 三、多模型对话

- **双模型切换**：DeepSeek（云端）+ Ollama（本地），运行时通过 `model` 参数切换
- **多模态图文**：支持 jpg/png/gif/webp 最大 10MB 图片，自动路由到 llava 视觉模型
- **流式输出**：SSE 协议，打字机效果，180 秒超时
- **并发控制**：Semaphore(1) + 120s 超时解决 Ollama 单线程推理排队

### 四、微服务架构

- **Gateway**：Spring Cloud Gateway 统一入口，JWT 鉴权 + 路由转发
- **Nacos**：服务注册与发现
- **Kafka**：事件驱动异步通信，手动 ACK + 死信队列（3 次重试后路由 DLT）
- **CI/CD**：GitHub Actions 自动构建 Docker 镜像

### 五、安全防御

- **JWT 双令牌**：Access Token（30 分钟）+ Refresh Token（7 天），Redis 原子轮转
- **双重鉴权**：Gateway 层 + Service 层双重 JWT 校验
- **Anti-SSRF**：WebTools URL 校验，禁止访问 localhost / loopback / link-local
- **路径穿越防护**：工作区路径校验，阻止 `..` 遍历、保留目录名、文件扩展名白名单
- **RBAC**：@RequireRole 注解驱动，双角色（USER / ADMIN）

### 六、工作区系统

- 每用户每会话独立虚拟文件工作区，支持版本控制
- Agent 可创建、读取、编辑、追加文件
- 工作区文件可一键保存到知识库

### 七、MCP Server

- 基于 REST 的 Model Context Protocol 服务器，暴露 Agent 工具给外部客户端
- 可配置工具白名单，feature toggle 控制开关

---

## 项目结构

```
springai-chatbot/
├── docker-compose.yml              # Docker 一键部署编排
├── docker-compose.prod.yml         # 生产环境（内存调优）
├── pom.xml                         # 父 POM（多模块管理）
├── .env                            # 环境变量（不提交到 Git）
│
├── chatbot-service/                # AI 对话 + Agent 主服务（:8080）
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/chatbot/
│       ├── agent/                  # Agent 工具治理框架
│       │   ├── tool/               # 7 类 Agent 工具
│       │   ├── AgentToolLevel      # 工具风险分级
│       │   ├── AgentToolAuditService  # 审计日志
│       │   ├── AgentToolNotifier   # SSE 实时推送
│       │   └── AgentPendingActionService  # 两阶段确认
│       ├── config/                 # 全局配置、模型配置
│       ├── controller/             # REST API
│       ├── kafka/                  # Kafka 生产者/消费者
│       ├── mcp/                    # MCP Server
│       ├── rag/                    # 三模式 RAG
│       ├── security/               # JWT、RBAC、拦截器
│       ├── service/                # 业务逻辑
│       ├── webtools/               # 网页抓取工具
│       └── workspace/              # 工作区系统
│
├── file-service/                   # 文件管理服务（:8081）
│   ├── Dockerfile
│   └── src/main/java/com/example/file/
│       ├── controller/             # 文件 API
│       ├── service/                # 文件服务、文档解析、图片处理
│       └── storage/                # 存储抽象（LocalStorage）
│
└── gateway/                        # API 网关（:9000）
    ├── Dockerfile
    └── src/main/java/com/example/chatbot/gateway/
        └── filter/                 # JWT 鉴权过滤器
```

---

## 快速开始

只需 Docker Desktop + 一条命令：

```bash
git clone https://github.com/yyx758/springai-chatbot.git
cd springai-chatbot

# 配置环境变量
cp .env.example .env
# 编辑 .env，填入你的 DeepSeek API Key 和 SMTP 邮箱信息

# 一键启动
docker compose up -d
```

启动后访问：

| 地址 | 功能 |
|------|------|
| http://localhost:9000 | 统一入口（推荐） |
| http://localhost:8080 | 聊天服务（直接访问） |
| http://localhost:8081/admin/files | 文件管理 |

**停止服务：**

```bash
docker compose down           # 停止容器，保留数据
```

---

## 环境变量

在项目根目录创建 `.env` 文件：

| 变量 | 说明 | 必填 |
|------|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 | ✅ |
| `APP_JWT_SECRET` | JWT 签名密钥（≥ 32 位） | ✅ |
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | ✅ |
| `REDIS_PASSWORD` | Redis 访问密码 | ✅ |
| `SMTP_HOST` | SMTP 服务器地址 | ✅ |
| `SMTP_PORT` | SMTP 端口 | ✅ |
| `SMTP_USERNAME` | 发件邮箱地址 | ✅ |
| `SMTP_PASSWORD` | 发件邮箱授权码 | ✅ |
| `SPRING_AI_OPENAI_ENABLED` | 是否启用 DeepSeek | 否 |
| `SPRING_AI_OLLAMA_BASE_URL` | Ollama 地址 | 否 |
| `APP_RAG_MODE` | RAG 模式：keyword / vector / hybrid | 否 |
| `APP_RAG_ELASTICSEARCH_ENABLED` | 是否启用 Elasticsearch 关键词召回，生产默认 true | 否 |
| `APP_RAG_ELASTICSEARCH_BASE_URL` | Elasticsearch 地址，生产默认 http://elasticsearch:9200 | 否 |
| `APP_RAG_ELASTICSEARCH_INDEX` | Elasticsearch 索引名，默认 ai_studio_knowledge | 否 |
| `APP_AGENT_ENABLED` | 是否启用 Agent | 否 |
| `APP_MCP_SERVER_ENABLED` | 是否启用 MCP Server | 否 |

---

## API 文档

### 认证接口 `/api/auth`

```
POST /api/auth/send-code         发送邮箱验证码
POST /api/auth/register          注册新用户
POST /api/auth/login             用户登录
POST /api/auth/refresh           刷新令牌
POST /api/auth/forgot-password   发送重置密码验证码
POST /api/auth/reset-password    重置密码
GET  /api/auth/me                获取当前用户信息
```

### 聊天接口 `/api/chat`

```
POST /api/chat/stream               SSE 流式聊天
POST /api/chat/stream/multipart     SSE 流式聊天 + 图片
POST /api/chat/stream/file-key      SSE 流式聊天 + 图片 fileKey
GET  /api/chat/history/{sessionId}  会话历史
DELETE /api/chat/{sessionId}        删除会话
```

### Agent 接口

```
POST /api/chat/agent/stream              Agent 流式对话（SSE）
POST /api/chat/agent/actions/{id}/confirm 确认待执行操作
```

### 工作区接口

```
GET    /api/workspace/files              工作区文件列表
POST   /api/workspace/files              创建工作区文件
GET    /api/workspace/files/content      读取工作区文件
PUT    /api/workspace/files/content      更新工作区文件
POST   /api/workspace/files/append       追加工作区文件
POST   /api/workspace/files/save-to-kb   保存到知识库
GET    /api/workspace/files/local-sync   本地文件夹同步
```

### 知识库 `/api/knowledge`

```
POST   /api/knowledge/documents              创建文档
GET    /api/knowledge/documents              文档列表
GET    /api/knowledge/documents/{id}         文档详情
DELETE /api/knowledge/documents/{id}         删除文档
POST   /api/knowledge/search                 检索测试
POST   /api/knowledge/reindex                重建当前用户的 PGVector/ES 索引
```

### 文件接口 `/api/files`

```
POST   /api/files/upload              上传文件
GET    /api/files/download/{fileKey}  下载文件
GET    /api/files/{fileKey}/info      文件信息
DELETE /api/files/{fileKey}           删除文件
POST   /api/files/batch               批量查询
```

### MCP 接口 `/api/mcp`

```
GET    /api/mcp/tools                 可用工具列表
POST   /api/mcp/call                  调用工具
```

### 管理接口 `/api/admin`

```
GET    /api/admin/stats               系统统计
GET    /api/admin/users               用户列表
PUT    /api/admin/users/{id}/role     修改角色
PUT    /api/admin/users/{id}/enabled  启/禁用用户
DELETE /api/admin/users/{id}          删除用户
```

---

## 安全设计

| 措施 | 实现 |
|------|------|
| 密码加密 | BCrypt |
| 双 Token | Access Token (30min) + Refresh Token (7天) |
| Token 轮转 | Redis 原子 getAndDelete 防重放 |
| 双重鉴权 | Gateway JWT + Service AuthInterceptor |
| Agent 工具分级 | READ_ONLY / LOW_RISK_WRITE / REQUIRE_CONFIRMATION |
| Agent 审计日志 | 全链路工具调用记录（参数、状态、耗时） |
| 危险操作确认 | Pending Action 两阶段确认 + 过期失效 |
| Anti-SSRF | URL 校验禁止访问内网地址 |
| 路径穿越防护 | 工作区路径 normalization + 扩展名白名单 |
| RBAC | @RequireRole 注解驱动 |
| 并发控制 | Ollama Semaphore(1) + 120s 超时 |

---

## 部署

### 本地开发

```bash
# 启动所有服务
docker compose up -d

# 代码修改后重建
docker compose up -d --build

# 只重建某个服务
docker compose up -d --build chatbot-service

# 查看日志
docker compose logs -f chatbot-service
```

### 远程服务器部署

```bash
# 传源码到服务器
scp -r chatbot-service/src user@your-server:/opt/springai-chatbot/
scp -r file-service/src user@your-server:/opt/springai-chatbot/
scp -r gateway/src user@your-server:/opt/springai-chatbot/

# SSH 到服务器重建
ssh user@your-server
cd /opt/springai-chatbot
docker compose up -d --build chatbot-service
```

### Elasticsearch RAG

生产配置 `docker-compose.prod.yml` 已默认启动 Elasticsearch，并默认开启 ES 关键词召回：

```yaml
APP_RAG_ELASTICSEARCH_ENABLED: ${APP_RAG_ELASTICSEARCH_ENABLED:-true}
APP_RAG_ELASTICSEARCH_BASE_URL: ${APP_RAG_ELASTICSEARCH_BASE_URL:-http://elasticsearch:9200}
APP_RAG_ELASTICSEARCH_INDEX: ${APP_RAG_ELASTICSEARCH_INDEX:-ai_studio_knowledge}
```

确认是否已开启：

```bash
docker compose -f docker-compose.prod.yml ps elasticsearch
curl -sS http://127.0.0.1:9200/_cluster/health
docker exec chatbot-service sh -c 'printenv | grep APP_RAG_ELASTICSEARCH'
```

已有知识库文档需要登录后触发一次索引重建：

```http
POST /api/knowledge/reindex
Authorization: Bearer <access-token>
```

4GB 单机上 ES heap 配置为 384m。若线上内存压力过大，可以在 `.env` 中设置 `APP_RAG_ELASTICSEARCH_ENABLED=false` 后重启 `chatbot-service`，链路会自动回退到 MySQL FULLTEXT 关键词召回。

### 生产环境

生产配置 `docker-compose.prod.yml` 针对小内存服务器优化：
- JVM SerialGC + 堆限制（chatbot 256MB, gateway/file-service 128MB）
- Redis maxmemory 32MB + allkeys-lru
- MySQL 关闭 performance schema
- Elasticsearch 单节点运行，heap 384MB，仅监听宿主机 `127.0.0.1:9200`
- 所有密码通过环境变量注入，不硬编码

---

## License

MIT
