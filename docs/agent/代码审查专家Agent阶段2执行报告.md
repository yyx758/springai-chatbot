# 代码审查专家 Agent 阶段 2 执行报告

## 1. 本阶段目标

阶段 2 目标是从“单文件只读审查”升级为“工作区多文件审查”：

- 支持显式指定多个 workspace 文件进行审查。
- 支持通过 query 从 workspace 中选择相关文件。
- 聚合多个文件的 review issue、风险级别、测试建议和修复计划。
- 新增代码审查辅助工具：带行号读取文件、按关键词搜索 workspace 代码。
- 将只读审查工具注册到现有 Agent 工具体系。
- 继续保持只读，不做 patch，不自动修改文件。

## 2. 实现结果

已完成。

### 2.1 新增多文件审查接口

接口：

    POST /api/chat/agent/review/workspace

请求体示例一：显式文件列表

    {
      "sessionId": "7_xxx",
      "relativePaths": [
        "src/main/java/com/example/App.java",
        "src/main/java/com/example/AppService.java"
      ],
      "focus": "security,reliability",
      "maxIssuesPerFile": 5
    }

请求体示例二：按 query 自动选择文件

    {
      "sessionId": "7_xxx",
      "query": "printStackTrace",
      "focus": "reliability",
      "maxFiles": 8,
      "maxIssuesPerFile": 3
    }

返回结构：

- success
- runId
- sessionId
- workspaceId
- scopeType
- reviewedFileCount
- summary
- riskLevel
- files
- issues
- testsToAdd
- patchPlan
- warnings

scopeType：

- EXPLICIT_FILES：用户明确指定文件。
- QUERY：按 query 匹配文件路径或文件内容。
- WORKSPACE_SAMPLE：未指定文件和 query 时，从工作区可审查源文件中取样。

### 2.2 新增只读工具

新增文件：

    chatbot-service/src/main/java/com/example/chatbot/agent/tool/ReviewWorkspaceTools.java

新增工具：

1. readWorkspaceFileWithLineNumbers

   作用：读取 workspace 文件并返回稳定的 1-based 行号内容，供代码审查生成行级问题。

2. searchWorkspaceCode

   作用：按关键词搜索当前会话 workspace 中的源代码文件，返回匹配文件、行号和代码片段。

工具治理：

- 风险等级：READ_ONLY。
- 使用 ToolContext 读取 userId/sessionId。
- 走 AgentToolAuditService 审计。
- 走 AgentToolNotifier 推送工具状态。
- 已注册到 AgentService 的 tools 列表。

## 3. 新增和修改代码

新增 review DTO：

- CodeReviewWorkspaceRequest.java
- CodeReviewWorkspaceResult.java
- CodeReviewFileSummary.java

修改：

- CodeReviewAgentService.java
  - 新增 reviewWorkspace 方法。
  - 新增多文件选择逻辑。
  - 新增按 query 匹配 workspace 文件逻辑。
  - 新增多文件结果聚合逻辑。

- CodeReviewAgentController.java
  - 新增 POST /api/chat/agent/review/workspace。

- AgentService.java
  - 注入 ReviewWorkspaceTools。
  - 将 ReviewWorkspaceTools 注册到 Spring AI tools 列表。

测试：

- CodeReviewAgentServiceTest.java
  - 新增显式多文件聚合测试。
  - 新增 query 选择文件测试。

## 4. 当前行为

### 4.1 显式文件审查

用户传入 relativePaths 时，服务会：

1. 校验 session 属于当前用户。
2. 获取当前 session workspace。
3. 对传入路径去空、去重、按 maxFiles 截断。
4. 逐文件复用阶段 1 的 reviewFile 逻辑。
5. 聚合风险、问题、文件摘要、测试建议和 patchPlan。

### 4.2 Query 选择文件

用户传入 query 且未传 relativePaths 时，服务会：

1. 列出 workspace 文件。
2. 过滤可审查源文件类型。
3. 优先匹配 relativePath。
4. 再读取文件内容进行 query 匹配。
5. 按 maxFiles 限制进入审查。

当前可审查类型包括：

- .java
- .kt
- .py
- .go
- .rs
- .js
- .ts
- .jsx
- .tsx
- .vue
- .sql
- .xml
- .yml
- .yaml
- .properties
- .md
- .json

### 4.3 聚合结果

多文件审查结果会：

- 汇总所有文件 issues。
- 按 severity 排序。
- 计算整体 riskLevel。
- 输出每个文件的 CodeReviewFileSummary。
- 去重聚合 testsToAdd 和 patchPlan。
- 输出 warnings，例如只审查部分文件。

## 5. 验证结果

### 5.1 单元测试

执行命令：

    mvn -q -pl chatbot-service -Dtest=CodeReviewAgentServiceTest test

结果：

    Exit code: 0

覆盖场景：

- 单文件本地规则兜底。
- 拒绝越权 session。
- maxIssues 限制。
- 文件不存在传播 404。
- 显式多文件审查聚合。
- query 自动选择匹配文件。

### 5.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 6. 当前限制

阶段 2 仍然保持保守范围：

- 多文件审查是逐文件审查后聚合，不是完整跨文件调用链推理。
- searchWorkspaceCode 是关键词搜索，不是 AST/符号级搜索。
- Query 选择文件会读取候选文件内容，当前用 maxFiles 和 MAX_SEARCHED_FILES 控制规模。
- 没有 Git diff 审查。
- 没有行级前端卡片。
- 没有 code_review_run / code_review_issue 持久化。
- 没有 patch 生成和确认流程。
- 没有显式 Agent Runtime。

这些限制符合阶段 2 范围，下一阶段再解决 runtime 和 trace。

## 7. 下一阶段建议

阶段 3 建议做“显式 Agent Runtime”。

目标：

- 不再完全依赖 Spring AI function calling 的隐式循环。
- 后端控制代码审查流程。
- 每一步可追踪、可停止、可评测。

建议 loop：

    CLASSIFY_SCOPE
    -> COLLECT_CONTEXT
    -> ANALYZE
    -> VERIFY
    -> REPORT

建议新增：

- AgentRuntime
- AgentRun
- AgentStep
- AgentDecision
- AgentObservation
- AgentTraceService

阶段 3 不建议引入自动修改。自动 patch 和确认流程应放到阶段 5。
