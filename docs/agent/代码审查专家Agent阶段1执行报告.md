# 代码审查专家 Agent 阶段 1 执行报告

## 1. 本阶段目标

阶段 1 目标是实现“只读代码审查 MVP”：

- 支持审查 workspace 中的指定文件。
- 返回结构化 review 结果。
- 每个问题包含严重级别、分类、文件路径、行号、证据、影响和建议。
- 不自动修改文件。
- 模型不可用时仍有本地保守规则兜底。
- 补充基础单元测试和编译验证。

## 2. 实现结果

已完成。

新增接口：

    POST /api/chat/agent/review/file

请求体示例：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/com/example/App.java",
      "focus": "security,reliability",
      "maxIssues": 8
    }

返回结构：

- success
- runId
- sessionId
- workspaceId
- relativePath
- fileName
- modelUsed
- reviewedChars
- truncated
- summary
- riskLevel
- issues
- testsToAdd
- patchPlan
- warnings

## 3. 新增代码

新增代码审查领域包：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/

新增文件：

- CodeReviewAgentService.java
- CodeReviewRequest.java
- CodeReviewResult.java
- CodeReviewIssue.java
- CodeReviewIssueSeverity.java
- CodeReviewIssueCategory.java
- CodeReviewProperties.java

新增 Controller：

    chatbot-service/src/main/java/com/example/chatbot/controller/CodeReviewAgentController.java

新增测试：

    chatbot-service/src/test/java/com/example/chatbot/agent/review/CodeReviewAgentServiceTest.java

## 4. 关键设计

### 4.1 只读审查

本阶段只调用：

- AgentWorkspaceService.getOrCreateWorkspace
- AgentWorkspaceService.readFileContent

不调用：

- updateWorkspaceFile
- appendWorkspaceFile
- createWorkspaceFile
- Git 写操作
- commit / push

因此阶段 1 不会自动修改工作区文件。

### 4.2 模型优先，本地规则兜底

审查流程：

1. 校验 userId、sessionId、relativePath。
2. 读取当前用户会话 workspace 文件。
3. 给文件内容添加行号。
4. 如果有可用 OpenAI/DeepSeek 或 Ollama 模型，则要求模型返回结构化 JSON。
5. 如果模型不可用、调用失败或 JSON 解析失败，则使用本地保守规则生成 review。

本地规则当前能识别：

- printStackTrace
- System.out.println
- password / secret / api key 相关敏感关键词
- catch Exception 且未重新抛出的宽泛异常捕获

本地规则不是完整代码审查能力，只用于保证 MVP 在无模型环境可用。

### 4.3 结构化问题模型

CodeReviewIssue 包含：

- severity
- category
- title
- description
- filePath
- startLine
- endLine
- evidence
- impact
- recommendation
- patchable
- suggestedPatch

严重级别：

- BLOCKER
- HIGH
- MEDIUM
- LOW
- INFO

问题分类：

- BUG
- SECURITY
- RELIABILITY
- PERFORMANCE
- MAINTAINABILITY
- TESTING
- ARCHITECTURE
- AI_ENGINEERING

## 5. 配置项

新增配置前缀：

    app.agent.review

默认值：

    enabled: true
    max-file-chars: 30000
    default-max-issues: 8

当前未显式写入 application.yml，使用 CodeReviewProperties 默认值即可运行。

后续如需生产环境调参，可补充：

    app:
      agent:
        review:
          enabled: true
          max-file-chars: 30000
          default-max-issues: 8

## 6. 验证结果

### 6.1 单元测试

执行命令：

    mvn -q -pl chatbot-service -Dtest=CodeReviewAgentServiceTest test

结果：

    Exit code: 0

覆盖场景：

- 模型不可用时使用本地规则兜底。
- 拒绝不属于当前用户的 session。
- 尊重 maxIssues 限制。
- workspace 文件不存在时传播 404。

### 6.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

阶段 1 仍有这些限制：

- 只支持单文件审查。
- 不支持 Git diff。
- 不支持多文件调用链分析。
- 不支持行级前端卡片。
- 不持久化 code_review_run / code_review_issue。
- 没有显式 Agent Runtime。
- 没有 patch 生成和确认流程。

这些限制符合阶段 1 范围。

## 8. 下一阶段建议

阶段 2 建议做“工作区多文件审查”：

1. 新增 readFileWithLineNumbers 工具，供 Agent 工具体系复用。
2. 新增 searchWorkspaceCode，支持按关键词查找相关文件。
3. 新增 getWorkspaceFileDigest，先摘要再按需读取，避免上下文爆炸。
4. 支持审查 controller 时主动关联 service、mapper、entity、config。
5. 输出跨文件问题，例如权限校验缺失、配置不一致、缓存和持久化链路不一致。

阶段 3 再做显式 Agent Runtime：

    CLASSIFY_SCOPE -> COLLECT_CONTEXT -> ANALYZE -> VERIFY -> REPORT

不要在阶段 2 就引入自动修改。修改和 patch 应放到阶段 5。
