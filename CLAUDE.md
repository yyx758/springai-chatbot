# CLAUDE.md — AI Studio 项目上下文

## 项目概述

AI 智能客服平台，Spring Boot 3.2 + Spring AI，Docker 容器化微服务。

| 服务 | 端口 | 职责 |
|------|------|------|
| `chatbot-service` | :8080 (内部) | AI 对话、Agent 工具、RAG 检索、知识库 |
| `file-service` | :8081 (内部) | 文件上传/下载、文档解析 |
| `gateway` | :9000 (对外) | 统一入口、JWT 鉴权、Nacos 服务发现 |

基础设施：MySQL(:3307) / Redis(:6379) / Kafka(:9092) / Nacos(:8848) / PGVector(:5432,可选)

## 开发命令

```bash
docker compose up -d              # 启动全部
docker compose up -d --build      # 代码修改后重建
docker compose up -d --build chatbot-service  # 只重建一个
docker compose --profile vector up -d         # 含 PGVector
docker compose logs -f chatbot-service        # 查看日志
docker compose down               # 停止（数据保留在 Volume）
```

## CRITICAL 安全约束

- **NEVER 运行 `docker compose down -v`** — 会删除所有数据库数据
- **NEVER 删除任意服务** — MySQL/Redis/Kafka/Nacos/chatbot-service/file-service/gateway 都不可删
- **NEVER 提交 .env 到 git** — 包含 API Key 和密码
- **NEVER 直接操作生产数据库** — 除非用户明确要求
- 高风险操作（删除容器、改数据库）需用户确认

## 远程部署

服务器：腾讯云轻量 4GB / ubuntu / 密钥认证
路径：`/opt/springai-chatbot`
部署：`git fetch origin && git reset --hard origin/main && docker compose -f docker-compose.prod.yml up -d --build chatbot-service`
详细内存调优见 `.claude/rules/deploy.md`

## 环境变量（关键）

| 变量 | 说明 |
|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek API Key |
| `APP_JWT_SECRET` | JWT 签名密钥（≥32位） |
| `APP_RAG_MODE` | hybrid / keyword / vector |
| `APP_RAG_VECTOR_ENABLED` | 向量检索开关 |
| `APP_RAG_EMBEDDING_API_KEY` | DashScope API Key |
| `APP_AGENT_ENABLED` | Agent 开关 |

## 子模块文档

详细设计文档按需自动加载，不在根 CLAUDE.md 中展开：
- `.claude/rules/agent-tools.md` — Agent 工具治理（涉及 agent/ 目录时加载）
- `.claude/rules/rag-retrieval.md` — 混合 RAG 检索（涉及 rag/ 目录时加载）
- `.claude/rules/deploy.md` — 生产部署与内存调优
- `chatbot-service/CLAUDE.md` — 主服务代码结构、上下文管理、安全防御（涉及 chatbot-service 时加载）
- `CLAUDE.local.md` — 个人偏好（不提交 git）
