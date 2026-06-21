# AI Agent 升级阶段 1 执行报告

执行时间：2026-06-04

## 1. 阶段目标

阶段 1 目标是实现 Agent Runtime 最小可用版。

本阶段只开放只读工具，不引入向量库、MCP、写操作工具或新的重型服务。

## 2. 已完成改动

### 2.1 新增 Agent SSE 入口

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/controller/AgentController.java
```

新增接口：

```text
POST /api/chat/agent/stream
```

说明：

- 仍然走原有 `/api/**` 鉴权拦截器。
- 未登录请求会被拦截。
- 如果请求没有 `sessionId`，自动生成 `{userId}_{uuid}`。
- 不修改原 `/api/chat/stream`，避免普通聊天链路回归。

### 2.2 新增 AgentService

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java
```

能力：

- 自动选择可用模型，默认优先 DeepSeek/OpenAI 兼容模型。
- 支持 SSE 流式输出。
- 构造 Agent 系统提示词。
- 注入最近会话历史。
- 注册阶段 1 只读工具。
- Agent 回复完成后复用现有 Kafka 异步聊天记录保存链路。

### 2.3 新增只读工具

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/tool/KnowledgeReadTools.java
chatbot-service/src/main/java/com/example/chatbot/agent/tool/FileReadTools.java
chatbot-service/src/main/java/com/example/chatbot/agent/tool/ChatHistoryTools.java
chatbot-service/src/main/java/com/example/chatbot/agent/tool/TimeTools.java
```

阶段 1 工具：

- `searchKnowledge`
- `getFileInfo`
- `listUserFiles`
- `getCurrentChatHistory`
- `getChatHistoryBySession`
- `getCurrentTime`

工具边界：

- 全部为只读工具。
- 不提供删除、发送、创建、修改等写操作。
- 不开放 shell、filesystem、任意 SQL、任意 HTTP。

### 2.4 工具上下文与用户隔离

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolContextKeys.java
chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolContextResolver.java
```

设计：

- `userId` 和 `sessionId` 通过 Spring AI `ToolContext` 注入。
- 工具方法参数不暴露 `userId` 给模型。
- 会话历史工具会校验 `sessionId` 必须以 `{userId}_` 开头。
- 工具 schema 测试确认没有暴露 `userId`、`sessionId` 或 `ToolContext` 给模型。

### 2.5 工具事件通知

新增：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentToolNotifier.java
```

支持事件：

- `tool_call_started`
- `tool_call_result`
- `tool_call_error`

说明：

- 当前是轻量通知机制。
- 如果 Spring AI 工具执行和 SSE 发送处于同一执行上下文，可向前端发工具事件。
- 即使跨 Reactor 线程导致事件无法送出，也不影响最终 Agent 回答。
- 阶段 2 做工具审计表时，会进一步固化工具执行记录。

### 2.6 文件服务客户端只读扩展

修改：

```text
chatbot-service/src/main/java/com/example/chatbot/service/FileServiceClient.java
```

新增：

- `getFileInfo(String fileKey, Long userId)`
- `listFiles(int page, int size, Long userId)`

说明：

- 新增方法会向 file-service 传 `X-Auth-UserId`。
- 原有 `getFileInfo(String fileKey)`、`getFileBytes(String fileKey)` 不变，避免影响多模态 fileKey 链路。

### 2.7 配置

修改：

```text
chatbot-service/src/main/resources/application.yml
```

新增：

```yaml
app:
  agent:
    enabled: true
    max-history: 5
    max-tool-iterations: 6
    tool-timeout-ms: 10000
```

说明：

- 当前阶段未引入重型服务。
- `max-tool-iterations` 和 `tool-timeout-ms` 先作为配置预留。

## 3. 新增测试

新增：

```text
chatbot-service/src/test/java/com/example/chatbot/agent/AgentToolSecurityTest.java
```

覆盖内容：

- 知识库工具从 `ToolContext` 读取当前用户 `userId`。
- `topK` 被限制到最大 10。
- 工具 schema 不暴露 `userId`、`sessionId`、`ToolContext`。
- 聊天历史工具拒绝访问其他用户 session。
- 聊天历史工具返回结果不包含 `imageData`。

## 4. 已执行测试

### 4.1 阶段 1 单元测试

```bash
mvn -q -pl chatbot-service -Dtest=AgentToolSecurityTest test
```

结果：通过。

### 4.2 chatbot-service 编译

```bash
mvn -q -pl chatbot-service -DskipTests package
```

结果：通过。

### 4.3 全工程构建

```bash
mvn -q -DskipTests package
```

结果：通过。

## 5. 发现的非阶段 1 问题

执行：

```bash
git diff --check
```

发现：

```text
chatbot-service/src/main/java/com/example/chatbot/service/ChatbotService.java:369: trailing whitespace.
```

说明：

- 这是阶段 1 开始前就存在的已修改文件问题。
- 本阶段没有修复它，避免顺手修改用户已有改动。

另外，`application.yml` 出现 CRLF/LF 提示：

```text
CRLF will be replaced by LF the next time Git touches it
```

说明：

- 该提示不影响 Maven 构建。
- 后续如果需要统一编码和换行，可以单独治理。

## 6. 阶段 1 通过标准对照

| 标准 | 结果 |
|---|---|
| 原 `/api/chat/stream` 不修改 | 通过 |
| 新增 Agent SSE 入口 | 通过 |
| 只开放只读工具 | 通过 |
| 工具不暴露 userId 给模型 | 通过 |
| 工具不能访问其他用户 session | 通过 |
| 单元测试通过 | 通过 |
| 全工程构建通过 | 通过 |
| 未新增重型服务 | 通过 |

## 7. 阶段 1 结论

阶段 1 通过。

可以进入阶段 2：工具调用审计与可控写操作。

进入阶段 2 前建议：

- 先做数据库表设计和 Flyway 脚本。
- 写操作工具必须先落审计表。
- L2 操作必须 pending action + 用户确认后再执行。
- 继续保持 Agent 工具不允许模型传入任意 `userId`。
