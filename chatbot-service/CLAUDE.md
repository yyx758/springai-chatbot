# chatbot-service 子模块

## 代码结构

```
chatbot-service/src/main/java/com/example/chatbot/
├── agent/                      # Agent 工具治理框架
│   ├── AgentService.java       # Agent 主服务
│   ├── AgentToolLevel.java     # 三级风险常量
│   ├── AgentToolAuditService.java  # 审计日志
│   ├── AgentToolNotifier.java  # SSE 实时推送
│   ├── AgentPendingActionService.java  # Pending Action
│   └── tool/                  # 7 个工具类
├── rag/                        # 混合 RAG 检索
│   ├── HybridSearchService.java  # 混合检索编排
│   ├── HybridRanker.java     # 加权 RRF + Rerank
│   ├── QueryIntentAnalyzer.java  # 查询意图识别
│   ├── KeywordExtractor.java # 关键词提取
│   ├── PgVectorClient.java   # PGVector SQL
│   ├── EmbeddingClient.java  # DashScope API
│   └── DocumentChunker.java  # 文档切片
├── mcp/                        # MCP 服务（REST-based）
├── workspace/                  # 工作区系统
├── webtools/                   # 网页抓取（Anti-SSRF）
├── kafka/                      # Kafka 生产者/消费者
├── security/                   # JWT、AuthInterceptor
├── service/                    # ChatbotService, RagService
└── controller/                 # REST API
```

## 数据库表

| 表 | 用途 |
|----|------|
| user_account | 用户账号 |
| chat_record | 聊天记录 |
| knowledge_document | 知识库文档 |
| agent_tool_execution_log | Agent 工具审计日志 |
| agent_pending_action | Agent 待确认操作 |
| agent_workspace | Agent 工作区 |
| agent_workspace_file | 工作区文件 |
| file_record (file-service) | 文件元数据 |
| ai_studio_knowledge_vectors (PGVector) | 知识库向量索引 |

## 上下文管理

- Redis 滑动窗口：最近 5 轮对话，RPUSH + LTRIM(-5, -1)
- 2 小时 TTL，过期后从 MySQL 重建
- Kafka 异步写入：Consumer 先写 MySQL，再追加 Redis

## 安全防御

- JWT 双令牌：Access Token (30min) + Refresh Token (7天)
- 双重鉴权：Gateway + Service 两层 JWT 校验
- Anti-SSRF：WebTools URL 校验，禁止内网地址
- 路径穿越防护：工作区路径 normalization + 扩展名白名单

## MCP 服务

- REST-based MCP Server（自己实现，非标准 MCP SDK）
- 暴露 9 个工具（默认只暴露 READ_ONLY）
- 白名单控制：`app.mcp.server.allowed-tools`

## 工作区系统

- 每用户每会话独立虚拟文件工作区
- 支持版本控制、乐观锁
- Agent 可创建/读取/编辑/追加文件
- 工作区文件可一键保存到知识库
