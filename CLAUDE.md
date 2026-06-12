# CLAUDE.md - AI Studio 项目上下文

## 项目概述

AI 智能客服平台，Spring Boot 3.2 + Spring AI，Docker 容器化微服务架构。
具备 AI Agent 工具治理、混合 RAG 检索增强、多模型对话、MCP 服务等能力。

**3 个微服务：**
- `chatbot-service` (:8080，仅 Docker 内部暴露) — AI 对话、Agent 工具、RAG 检索、知识库、工作区
- `file-service` (:8081，仅 Docker 内部暴露) — 文件上传/下载、文档解析、缩略图
- `gateway` (:9000，对外统一入口) — 统一入口、JWT 鉴权、Nacos 服务发现

**基础设施（6 个）：**
- MySQL (:3307) — 数据持久化
- Redis (:6379) — 缓存聊天历史、Refresh Token
- Kafka (:9092) — 事件驱动（聊天记录、知识库索引、邮件通知）
- Nacos (:8848) — 服务注册与发现
- PGVector (:5432，可选) — 向量数据库
- Docker — 容器化部署

---

## 核心功能

### 1. AI Agent 工具治理框架

**7 类 18 个工具，三级风险分级：**
- `READ_ONLY`：搜索、列出、查看、时间查询、网页抓取
- `LOW_RISK_WRITE`：创建文档、工作区操作
- `REQUIRE_CONFIRMATION`：删除操作（Pending Action 两阶段确认）

**工具调用链路：**
```
LLM → Spring AI Function Calling → @Tool 方法 → 业务逻辑
```

**关键文件：**
- `agent/AgentService.java` — Agent 主服务，注册工具到 ChatClient
- `agent/AgentToolLevel.java` — 三级风险常量
- `agent/AgentToolAuditService.java` — 审计日志（agent_tool_execution_log 表）
- `agent/AgentToolNotifier.java` — SSE 实时推送（通过 ToolContext 传递 emitter）
- `agent/AgentPendingActionService.java` — Pending Action 两阶段确认
- `agent/tool/` — 7 个工具类（KnowledgeRead/Write、FileRead、ChatHistory、Time、Web、Workspace）

**Pending Action 流程：**
```
LLM 调用 requestDeleteKnowledgeDocument
    → 创建 PendingAction (status=PENDING, expireTime=+10min)
    → 返回 { requiresConfirmation: true, actionId: 456 }
    → 前端渲染 Confirm 按钮
    → 用户点击 → POST /api/chat/agent/actions/456/confirm
    → 后端校验 → 执行删除
```

### 2. 混合 RAG 检索增强

**三模式可切换（默认 hybrid）：**
- `keyword`：自研关键词评分（标题/正文/标签加权 + 2-gram + 短语提取）
- `vector`：PGVector HNSW + DashScope text-embedding-v4
- `hybrid`：加权 RRF 融合 + 规则型 Rerank

**检索流程：**
```
QueryIntentAnalyzer → 识别问题类型，动态调整权重
    ↓
┌─────────────────┐  ┌─────────────────┐
│ 关键词检索       │  │ 向量检索         │
│ KeywordExtractor│  │ Embedding API   │
│ 2-gram+短语+技术词│  │ PGVector cosine │
└────────┬────────┘  └────────┬────────┘
         └────────┬───────────┘
                  ↓
         HybridRanker 加权 RRF
         score = vectorWeight/(k+vectorRank) + keywordWeight/(k+keywordRank)
                  ↓
         规则型 Rerank 过滤（SelectReason）
                  ↓
         top-3 进入 LLM Prompt
```

**关键文件：**
- `rag/QueryIntentAnalyzer.java` — 7 种查询类型识别
- `rag/KeywordExtractor.java` — 2-gram / 短语 / 技术词提取
- `rag/HybridRanker.java` — 加权 RRF + 规则型 Rerank
- `rag/HybridSearchService.java` — 混合检索编排
- `rag/PgVectorClient.java` — PGVector SQL 操作
- `rag/EmbeddingClient.java` — DashScope text-embedding-v4 API
- `rag/DocumentChunker.java` — 文档切片（800 字符 / 100 重叠）
- `rag/HybridRagProperties.java` — 所有阈值配置

### 3. 多模态图文混合

- 支持 jpg/png/gif/webp 最大 10MB
- 两种上传方式：multipart 直接上传 / fileKey 间接上传
- 自动路由到 Ollama llava 视觉模型
- 图片以 data URI 存入 MySQL

### 4. MCP 服务

- REST-based MCP Server（自己实现，非标准 MCP SDK）
- 暴露 9 个工具（默认只暴露 READ_ONLY）
- 白名单控制：`app.mcp.server.allowed-tools`

### 5. 工作区系统

- 每用户每会话独立虚拟文件工作区
- 支持版本控制、乐观锁
- Agent 可创建/读取/编辑/追加文件
- 工作区文件可一键保存到知识库
- 安全：路径 normalization、扩展名白名单、保留目录过滤

---

## 本地开发

```bash
# 启动所有服务（首次会自动构建镜像）
docker compose up -d

# 代码修改后重新部署（必须加 --build）
docker compose up -d --build

# 只重建某个服务
docker compose up -d --build chatbot-service

# 启动含 PGVector（混合检索需要）
docker compose --profile vector up -d

# 查看日志
docker compose logs -f chatbot-service

# 停止所有服务（数据不丢，Volume 保留）
docker compose down
```

---

## 远程服务器部署

**服务器：** 腾讯云轻量，4GB 内存
**用户：** ubuntu
**SSH：** 密钥认证

### 部署流程

```bash
# 1. SSH 到服务器
ssh -i ~/.ssh/id_rsa ubuntu@<server-ip>
cd /opt/springai-chatbot

# 2. 拉取最新代码
git fetch origin && git reset --hard origin/main

# 3. 重建并启动
docker compose -f docker-compose.prod.yml up -d --build chatbot-service
```

### 注意事项

- **`--build` 必须加**：否则 Docker 用缓存旧镜像
- **容器重启数据不丢**：MySQL/Redis/Kafka 数据存在 Docker Volume 里
- **不要运行 `docker compose down -v`**：会删除所有数据
- **不要删除任意服务**：每个服务都是系统必需的
- **端口安全**：对外只需 80（Nginx），8080/8081 仅 Docker 内部，8848 仅调试
- **PGVector 可选**：需要混合检索时用 `--profile vector` 启动

### 生产环境内存调优（2GB/4GB）

| 组件 | 优化 |
|------|------|
| chatbot-service | SerialGC + 堆限制 256MB |
| gateway/file-service | 堆限制 128MB |
| Redis | maxmemory 32MB + allkeys-lru |
| MySQL | 关闭 performance schema + binlog |
| Kafka | 堆限制 200MB |

---

## 安全约束

- **不要删除任意服务**：MySQL、Redis、Kafka、Nacos、chatbot-service、file-service、gateway 都不能删
- **高风险操作需确认**：docker-compose down -v、删除容器、修改数据库等需要用户确认
- **不要直接操作生产数据库**：除非用户明确要求
- **不要提交 .env 到 git**：包含 API Key 和密码

---

## 关键技术点

### Agent 工具治理
- 三级风险分级：READ_ONLY / LOW_RISK_WRITE / REQUIRE_CONFIRMATION
- 审计日志：每次工具调用记录到 agent_tool_execution_log 表
- SSE 实时推送：通过 ToolContext 传递 SseEmitter，工具执行状态实时推送到前端
- Pending Action：删除操作创建待确认记录，10 分钟过期，用户点确认才执行

### 混合 RAG
- QueryIntentAnalyzer：7 种查询类型，动态调整向量/关键词权重
- 加权 RRF：`score = vectorWeight/(k+vectorRank) + keywordWeight/(k+keywordRank)`，k=60
- 规则型 Rerank：8 条规则，SelectReason 枚举记录选中/过滤原因
- 最长匹配抑制：`removeTermsCoveredByLongerTerms()` 消除 2-gram 碎片
- 三层层级评分：technicalTerms > phrases > bigrams（兜底）

### 上下文管理
- Redis 滑动窗口：最近 5 轮对话，RPUSH + LTRIM(-5, -1)
- 2 小时 TTL，过期后从 MySQL 重建
- Kafka 异步写入：Consumer 先写 MySQL，再追加 Redis

### 安全防御
- JWT 双令牌：Access Token (30min) + Refresh Token (7天)
- 双重鉴权：Gateway + Service 两层 JWT 校验
- Anti-SSRF：WebTools URL 校验，禁止内网地址
- 路径穿越防护：工作区路径 normalization + 扩展名白名单
- Prompt Injection 防护：REQUIRE_CONFIRMATION 级别兜底

---

## 代码结构

```
springaI-chatbot/
├── chatbot-service/                    # AI 对话 + Agent 主服务
│   ├── src/main/java/com/example/chatbot/
│   │   ├── agent/                      # Agent 工具治理框架
│   │   │   ├── AgentService.java       # Agent 主服务
│   │   │   ├── AgentToolLevel.java     # 三级风险常量
│   │   │   ├── AgentToolAuditService.java  # 审计日志
│   │   │   ├── AgentToolNotifier.java  # SSE 实时推送
│   │   │   ├── AgentPendingActionService.java  # Pending Action
│   │   │   └── tool/                  # 7 个工具类
│   │   ├── rag/                        # 混合 RAG 检索
│   │   │   ├── HybridSearchService.java  # 混合检索编排
│   │   │   ├── HybridRanker.java     # 加权 RRF + Rerank
│   │   │   ├── QueryIntentAnalyzer.java  # 查询意图识别
│   │   │   ├── KeywordExtractor.java # 关键词提取
│   │   │   ├── PgVectorClient.java   # PGVector SQL
│   │   │   ├── EmbeddingClient.java  # DashScope API
│   │   │   └── DocumentChunker.java  # 文档切片
│   │   ├── mcp/                        # MCP 服务
│   │   ├── workspace/                  # 工作区系统
│   │   ├── webtools/                   # 网页抓取（Anti-SSRF）
│   │   ├── kafka/                      # Kafka 生产者/消费者
│   │   ├── security/                   # JWT、AuthInterceptor
│   │   ├── service/                    # ChatbotService, RagService
│   │   └── controller/                 # REST API
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/               # Flyway V1~V6
│       └── templates/                  # chat.html, admin.html, login.html
├── file-service/                       # 文件管理微服务
│   ├── src/main/java/com/example/file/
│   │   ├── controller/                 # FileController
│   │   ├── service/                    # FileService, ImageProcessor
│   │   └── storage/                    # FileStorage 接口, LocalStorage
│   └── src/main/resources/
│       └── templates/                  # file-manager.html
├── gateway/                            # Spring Cloud Gateway
├── docker-compose.yml                  # 开发环境编排
├── docker-compose.prod.yml             # 生产环境（内存调优）
├── .env                                # 环境变量（不提交 git）
├── docs/
│   ├── agent-tools-guide.md            # Agent 工具系统完整文档
│   ├── hybrid-rag-guide.md             # 混合 RAG 文档
│   ├── mock-interview.md               # 模拟面试题
│   ├── portfolio.md                    # 项目作品集
│   └── rerank-design.md                # Rerank 设计文档
└── scripts/                            # 生产验证脚本
```

---

## 数据库表结构

| 表名 | 所属服务 | 用途 |
|------|---------|------|
| user_account | chatbot | 用户账号 |
| chat_record | chatbot | 聊天记录 |
| knowledge_document | chatbot | 知识库文档 |
| agent_tool_execution_log | chatbot | Agent 工具审计日志 |
| agent_pending_action | chatbot | Agent 待确认操作 |
| agent_workspace | chatbot | Agent 工作区 |
| agent_workspace_file | chatbot | 工作区文件 |
| file_record | file-service | 文件元数据 |
| ai_studio_knowledge_vectors | PGVector | 知识库向量索引 |

---

## 环境变量

关键配置（完整列表见 .env.example）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| DEEPSEEK_API_KEY | — | DeepSeek API Key |
| APP_JWT_SECRET | — | JWT 签名密钥（≥32 位） |
| MYSQL_ROOT_PASSWORD | — | MySQL 密码 |
| REDIS_PASSWORD | — | Redis 密码 |
| APP_RAG_MODE | hybrid | RAG 模式 |
| APP_RAG_VECTOR_ENABLED | true | 向量检索开关 |
| APP_RAG_EMBEDDING_API_KEY | — | DashScope API Key |
| APP_AGENT_ENABLED | true | Agent 开关 |
| APP_MCP_SERVER_ENABLED | false | MCP Server 开关 |
