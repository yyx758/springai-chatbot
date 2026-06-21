# AI Agent 升级阶段 5A 执行报告：Workspace 与 Web Tools

执行时间：2026-06-07

## 1. 本阶段目标

把 Agent 从“只能调用项目内知识库工具”升级为“能围绕每个对话生成、读取、更新、下载文件，并具备网页搜索/抓取工具入口”的工作型 Agent。

本阶段坚持安全边界：

- 不开放服务器任意文件系统。
- 不开放 shell。
- 不开放任意 SQL。
- 不开放任意内网 HTTP 访问。
- 文件只写入当前用户、当前会话的 Agent Workspace。
- Web tools 默认关闭，没有 API Key 时不会影响普通聊天。

## 2. 已完成改动

### 2.1 Agent Workspace 后端

新增表：

```text
agent_workspace
agent_workspace_file
```

新增能力：

- 每个 `userId + sessionId` 自动创建一个 workspace。
- 支持 workspace 文件列表、创建、读取、更新、下载、保存到知识库。
- 文件元数据存在 `chatbot-service`。
- 文件内容通过 `file-service` 存储并进入文件管理体系。

安全限制：

- `sessionId` 必须属于当前用户。
- 禁止 `../`、绝对路径、反斜杠路径、空路径。
- 默认单文件最大 2MB。
- 默认单 workspace 最多 100 个文件。
- 默认允许 `.md`、`.txt`、`.json`、`.csv`、`.html`、`.css`、`.js`。

新增接口：

```text
GET  /api/agent/workspaces/current
GET  /api/agent/workspaces/{workspaceId}/files
GET  /api/agent/workspaces/{workspaceId}/files/content
POST /api/agent/workspaces/{workspaceId}/files
PUT  /api/agent/workspaces/{workspaceId}/files/content
GET  /api/agent/workspaces/{workspaceId}/files/download
POST /api/agent/workspaces/{workspaceId}/files/save-to-knowledge
```

### 2.2 Agent 文件工具

新增 Spring AI 工具：

```text
createWorkspaceFile
readWorkspaceFile
updateWorkspaceFile
appendWorkspaceFile
listWorkspaceFiles
saveWorkspaceFileToKnowledge
```

工具行为：

- 自动使用当前 `ToolContext` 中的 `userId` 和 `sessionId`。
- 所有写操作进入工具审计。
- 文件创建/更新后通过 SSE 推送 `workspace_file_created`、`workspace_file_updated`。
- 保存到知识库后推送 `workspace_file_saved_to_knowledge` 和原有知识库文档卡片事件。

### 2.3 File Service 扩展

新增：

```text
POST /api/files/generated/workspace
```

用途：

- 保存 Agent 生成的 workspace 文本文件。
- `bizType=AGENT_WORKSPACE`。
- 文件仍走现有 `file_record` 和本地存储。

### 2.4 前端工作区 UI

聊天页新增：

- 顶部工作区按钮。
- 当前对话工作区弹窗。
- Agent 生成文件卡片。
- 文件 Open、Download、KB 按钮。
- 文件预览复用现有文档预览弹窗。

新增 SSE 事件处理：

```text
workspace_file_created
workspace_file_updated
workspace_file_saved_to_knowledge
web_search_started
web_search_completed
web_fetch_completed
```

下载注意：

- Workspace 下载接口受 JWT 保护。
- 前端使用 `authFetch -> blob -> a.download`，不是 `window.open`，避免丢失 Authorization。

### 2.5 Web Tools

新增配置：

```yaml
app:
  web-tools:
    enabled: ${APP_WEB_TOOLS_ENABLED:false}
    provider: ${APP_WEB_TOOLS_PROVIDER:firecrawl}
    firecrawl:
      base-url: ${APP_FIRECRAWL_BASE_URL:https://api.firecrawl.dev}
      api-key: ${APP_FIRECRAWL_API_KEY:}
```

新增工具：

```text
searchWeb
fetchWebPage
createWorkspaceFileFromWebPage
```

默认状态：

```text
APP_WEB_TOOLS_ENABLED=false
```

安全限制：

- 只允许 `http` / `https`。
- 禁止 localhost、127.0.0.1、0.0.0.0、169.254.169.254。
- 禁止 loopback、link-local、site-local、multicast 地址。
- 单网页内容最大 100KB。

### 2.6 MCP-style 网关扩展

新增可选工具：

```text
workspace.files.list
workspace.files.read
workspace.files.create
workspace.files.update
```

默认白名单只加入只读工具：

```text
workspace.files.list
workspace.files.read
```

写文件工具不默认对 MCP 外部入口开放。

### 2.7 Gateway

新增路由：

```text
/api/agent/** -> chatbot-service
```

没有加入放行列表，仍需要 JWT。

## 3. 本地测试结果

已通过：

```bash
mvn -q -pl chatbot-service -Dtest=AgentWorkspaceServiceTest test
mvn -q -pl chatbot-service -Dtest=WebToolsSecurityTest test
mvn -q -pl chatbot-service -Dtest=McpToolSecurityTest test
mvn -q -pl chatbot-service -Dtest=McpServerSmokeTest test
mvn -q -DskipTests package
docker compose config --services
docker compose -f docker-compose.prod.yml config --services
```

说明：

- 同一 Maven 模块测试不能并行跑，否则多个 Maven 进程会竞争 `target` 目录，出现假性“找不到类”。
- 顺序执行后测试通过。
- Docker compose 本机仍有 Docker config 权限 warning，但命令退出码为 0。

## 4. 新增生产验证脚本

新增：

```text
scripts/prod-workspace-status-check.sh
scripts/prod-web-tools-status-check.sh
```

`prod-workspace-status-check.sh` 验证：

- 未登录访问 workspace API 返回 401。
- 临时用户登录。
- 创建当前 session workspace。
- 创建 `checks/hello.md`。
- 读取文件内容。
- 清理临时用户、workspace 元数据、知识库文档和 file_record。

`prod-web-tools-status-check.sh` 验证：

- 读取 `APP_WEB_TOOLS_ENABLED`。
- 如果 web tools 开启但没有 `APP_FIRECRAWL_API_KEY`，直接失败。
- 默认关闭时通过。

## 5. 远程部署状态

远程已部署完成。

部署方式：

- 本地 `mvn -q -DskipTests package` 构建 jar。
- 上传 `chatbot-service`、`file-service`、`chatbot-gateway` 三个 jar。
- 使用低内存 jar 替换方式更新容器内 `/app/app.jar`。
- 重启 `chatbot-service`、`file-service`、`chatbot-gateway`。

同步内容：

- 三个服务 jar。
- 新增/修改源码和配置。
- `V5__add_agent_workspace_tables.sql` 迁移。
- 新增生产验证脚本。
- 本阶段报告。

部署后执行：

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
bash scripts/prod-rag-status-check.sh
bash scripts/prod-mcp-status-check.sh
bash scripts/prod-workspace-status-check.sh
bash scripts/prod-web-tools-status-check.sh
```

结果：

```text
[health] health check passed
[e2e] verify=passed
[rag] RAG status check passed
[mcp] MCP status check passed
[workspace] workspace status check passed
[web-tools] web tools status check passed
```

Workspace 验证重点：

```text
[pass] workspace API without token returns 401
[pass] temporary user login works
[pass] workspace current works
[pass] workspace file create works
[pass] workspace file read works
```

Web tools 验证重点：

```text
[web-tools] enabled=false
[pass] web tools are disabled by default
```

部署后资源占用：

```text
chatbot-gateway   220.7MiB / 3.32GiB
chatbot-service   330MiB / 3.32GiB
file-service      201.5MiB / 3.32GiB
chatbot-kafka     240.1MiB / 3.32GiB
chatbot-redis     5.91MiB / 3.32GiB
chatbot-mysql     94.7MiB / 3.32GiB
chatbot-nacos     325.2MiB / 3.32GiB
```

## 6. 当前结论

阶段 5A 已完成并部署到远程生产环境。

Agent 现在具备以下新增能力：

- 能创建当前对话 workspace 文件。
- 能读取、更新、追加 workspace 文件。
- 能把 workspace 文件保存进知识库。
- 前端能显示 workspace 文件卡片。
- 前端能打开、下载、保存 workspace 文件。
- Web search/fetch 工具已实现但默认关闭。
- MCP-style 网关已扩展 workspace 只读工具。
- 远程生产验证已通过。
