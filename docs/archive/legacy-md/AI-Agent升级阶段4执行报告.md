# AI Agent 升级阶段 4 执行报告

执行时间：2026-06-07

## 1. 阶段目标

阶段 4 的目标是给 AI Studio 增加基础 MCP 扩展能力，并继续满足 4G 云服务器约束。

本次没有新增独立 MCP Server 进程，而是在 `chatbot-service` 内实现了轻量 MCP-style 工具网关：

- 默认关闭。
- 统一走 Gateway 和现有 JWT 鉴权。
- 只允许白名单工具。
- 内部复用已有 Agent 工具、用户上下文和审计逻辑。
- 不开放 shell、文件系统、任意 SQL、任意 HTTP 请求。

这样做的原因是当前服务器资源有限，先把工具协议边界、白名单和鉴权链路做稳，比直接堆一个外部 MCP 进程更可控。

## 2. 本次改动

### 2.1 新增 MCP 配置

文件：

```text
chatbot-service/src/main/java/com/example/chatbot/mcp/McpProperties.java
chatbot-service/src/main/resources/application.yml
```

新增配置：

```yaml
app:
  mcp:
    enabled: ${APP_MCP_ENABLED:false}
    server:
      enabled: ${APP_MCP_SERVER_ENABLED:false}
      allowed-tools:
        - knowledge.search
        - files.info
        - chat.history
    client:
      enabled: ${APP_MCP_CLIENT_ENABLED:false}
      allowed-tools: []
```

生产默认：

```text
APP_MCP_ENABLED=false
APP_MCP_SERVER_ENABLED=false
```

### 2.2 新增 MCP 工具网关

文件：

```text
chatbot-service/src/main/java/com/example/chatbot/mcp/McpToolGateway.java
chatbot-service/src/main/java/com/example/chatbot/mcp/McpToolSpec.java
chatbot-service/src/main/java/com/example/chatbot/mcp/McpToolInvocationRequest.java
```

当前工具目录：

| MCP 工具名 | 内部工具 | 风险等级 | 默认开放 |
|---|---|---|---|
| `knowledge.search` | `searchKnowledge` | `READ_ONLY` | 是 |
| `files.info` | `getFileInfo` | `READ_ONLY` | 是 |
| `files.list` | `listUserFiles` | `READ_ONLY` | 否 |
| `chat.history` | `getCurrentChatHistory` | `READ_ONLY` | 是 |
| `knowledge.create` | `createKnowledgeDocument` | `LOW_RISK_WRITE` | 否 |

关键安全规则：

- 请求体不能传 `userId`。
- `userId` 只来自认证上下文。
- `sessionId` 必须属于当前用户，例如 `7_xxx` 只能由用户 `7` 使用。
- 白名单外工具直接返回 403。
- MCP Server 默认关闭时返回 404。

### 2.3 新增 MCP Controller

文件：

```text
chatbot-service/src/main/java/com/example/chatbot/controller/McpController.java
```

新增接口：

```text
GET  /api/mcp/tools
POST /api/mcp/invoke
```

这两个接口都在 `/api/**` 下，受 `AuthInterceptor` 保护。

### 2.4 Gateway 增加 MCP 路由

文件：

```text
gateway/src/main/resources/application.yml
```

新增路由：

```yaml
- id: mcp-service
  uri: lb://chatbot-service
  predicates:
    - Path=/api/mcp/**
```

没有把 `/api/mcp/**` 加入 `exclude-paths`，所以生产访问必须带 JWT。

### 2.5 修复 ResponseStatusException 被全局异常处理成 500

文件：

```text
chatbot-service/src/main/java/com/example/chatbot/config/GlobalExceptionHandler.java
```

问题：

- `McpToolGateway` 在 MCP 默认关闭时抛出 `ResponseStatusException(404)`。
- 原全局异常处理器把它当成普通异常处理，导致接口返回 500。

修复：

- 增加 `ResponseStatusException` 专门处理分支，保留原始 HTTP 状态码。

验证：

- 未登录访问 `/api/mcp/tools` 返回 401。
- 登录后访问默认关闭的 `/api/mcp/tools` 返回 404。

### 2.6 新增生产 MCP 验证脚本

文件：

```text
scripts/prod-mcp-status-check.sh
```

脚本验证：

- 读取运行时 MCP 开关。
- 未登录访问 `/api/mcp/tools` 必须返回 401。
- 创建临时用户并登录。
- 登录后访问 `/api/mcp/tools`：
  - MCP 默认关闭时应返回 404。
  - MCP 开启时应返回 200。
- 清理临时用户和 Refresh Token。

## 3. 本地测试

执行：

```bash
mvn -q -pl chatbot-service -Dtest=McpToolSecurityTest test
mvn -q -pl chatbot-service -Dtest=McpServerSmokeTest test
mvn -q -DskipTests package
docker compose config
docker compose -f docker-compose.prod.yml config
```

结果：

- `McpToolSecurityTest` 通过。
- `McpServerSmokeTest` 通过。
- 整体打包通过。
- 本地 compose 配置校验通过。
- 生产 compose 配置校验通过。

说明：

- Docker 本地配置读取时出现过 Docker config 权限 warning，但命令退出码为 0，不影响 compose 配置解析。

## 4. 远程部署

部署方式：

- 本地构建 jar。
- 上传 `chatbot-service` 和 `gateway` jar。
- 替换容器内 `/app/app.jar`。
- 重启 `chatbot-service` 和 `chatbot-gateway`。

原因：

- 远程服务器只有 4G，完整远程 Docker build 可能耗时较长。
- jar 替换部署对内存压力更小，适合本次小范围后端和配置改动。

远程状态：

- `chatbot-service` 已重启。
- `chatbot-gateway` 已重启。
- MCP 生产默认保持关闭。

## 5. 远程验证结果

执行：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
bash scripts/prod-mcp-status-check.sh
```

结果：

```text
[health] health check passed
[e2e] verify=passed
[rag] RAG status check passed
[mcp] MCP status check passed
```

关键断言：

```text
[pass] /api/mcp/tools without token returns 401
[pass] /api/mcp/tools with token returns expected 404
```

说明：

- 401 表示 Gateway/服务鉴权链路生效。
- 404 表示 MCP 默认关闭，不对生产用户暴露。

## 6. 资源占用

部署和验证后远程资源占用：

```text
chatbot-gateway   216MiB / 3.32GiB
chatbot-service   307MiB / 3.32GiB
file-service      164.7MiB / 3.32GiB
chatbot-kafka     243MiB / 3.32GiB
chatbot-redis     5.848MiB / 3.32GiB
chatbot-mysql     85.34MiB / 3.32GiB
chatbot-nacos     323.9MiB / 3.32GiB
```

判断：

- 阶段 4 采用内置轻量网关，没有新增常驻容器。
- 对 4G 服务器资源影响很小。

## 7. 当前能力

目前 AI Studio 的 Agent 能力包括：

- 模型可通过 Spring AI tool calling 调用项目内工具。
- 工具调用有前端事件展示。
- 工具调用有审计日志。
- 低风险写操作可以直接执行，例如创建知识库文档。
- 高风险写操作可以走待确认动作，例如删除知识库文档。
- MCP-style 工具网关已具备白名单、鉴权和用户上下文隔离。

当前 MCP 生产状态：

```text
已部署，但默认关闭。
```

如果后续要灰度开启，可在生产环境增加：

```text
APP_MCP_ENABLED=true
APP_MCP_SERVER_ENABLED=true
```

建议先只保留默认白名单：

```text
knowledge.search
files.info
chat.history
```

暂不建议开放：

```text
knowledge.create
files.list
```

原因：

- `knowledge.create` 是写操作，虽然低风险，但生产 MCP 对外入口初期应先只读。
- `files.list` 可能暴露文件列表范围，建议确认 UI 和审计需求后再开放。

## 8. 后续建议

下一阶段可以进入阶段 5：生产部署与观测。

推荐优先做：

1. Agent/MCP 工具调用指标：调用次数、失败次数、平均耗时。
2. RAG 命中指标：keyword 命中数、Top-K 结果数、空召回次数。
3. Kafka DLT 指标：死信消息数量和最近失败原因。
4. 管理端增加 Agent 工具审计页，方便展示和排障。

标准 MCP 协议适配建议延后：

- 当前已具备安全工具网关。
- 后续如果确实要接外部 MCP Client，再把 `/api/mcp` 后面的工具目录封装成标准 MCP JSON-RPC 或 stdio/server-sent events 协议。
- 不建议在当前阶段开放 shell、filesystem、任意 SQL 或任意 HTTP 工具。
