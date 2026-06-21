# 代码审查专家 Agent 阶段 4 执行报告

## 1. 本阶段目标

阶段 4 目标是支持 Git diff / PR 风格只读审查，让代码审查 Agent 能贴近日常开发变更审查。

本阶段安全边界：

- 只读 Git。
- 不 commit。
- 不 push。
- 不修改文件。
- 不执行任意 shell。
- 只通过固定白名单 git 参数读取 status、changed files 和 file diff。

## 2. 实现结果

已完成。

新增 Git diff 审查接口：

    POST /api/chat/agent/review/git-diff

请求示例：

    {
      "sessionId": "7_xxx",
      "focus": "reliability,security",
      "maxFiles": 8,
      "maxIssuesPerFile": 3
    }

返回结构复用 CodeReviewWorkspaceResult：

- scopeType = GIT_DIFF
- reviewedFileCount
- files
- issues
- riskLevel
- steps
- warnings

## 3. 新增代码

新增 Git 只读服务：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/GitReviewService.java

新增配置：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/GitReviewProperties.java

新增请求 DTO：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/CodeReviewGitDiffRequest.java

新增 Git 工具：

    chatbot-service/src/main/java/com/example/chatbot/agent/tool/GitReviewTools.java

修改：

- CodeReviewAgentService.java
  - 新增 reviewGitDiff。
  - 新增 diff hunk 行号解析。
  - 新增新增行本地规则审查。

- CodeReviewAgentController.java
  - 新增 /git-diff 接口。

- AgentService.java
  - 注册 GitReviewTools 到 Agent tools 列表。

- CodeReviewAgentServiceTest.java
  - 新增 Git diff 审查测试。

## 4. GitReviewService 设计

GitReviewService 只允许固定读命令：

- git status --short
- git diff --name-only
- git diff --cached --name-only
- git diff -- path
- git diff --cached -- path

实现方式：

- 使用 ProcessBuilder。
- 不通过 shell 拼接命令。
- 用户输入只允许 repository relative path。
- 拒绝绝对路径、盘符路径、~、.. 等路径。
- 设置 commandTimeoutMs，超时后 destroyForcibly。
- diff 输出按 maxDiffChars 截断。

默认配置：

    app.agent.review.git.enabled = true
    app.agent.review.git.repository-path = .
    app.agent.review.git.command-timeout-ms = 10000
    app.agent.review.git.max-diff-chars = 60000

## 5. Git diff 审查行为

reviewGitDiff 流程：

    CLASSIFY_SCOPE
    -> COLLECT_CONTEXT
    -> ANALYZE
    -> VERIFY
    -> REPORT

具体行为：

1. 校验 session 属于当前用户。
2. 通过 GitReviewService 获取 changed files。
3. 过滤可审查源文件。
4. 按 maxFiles 限制文件数。
5. 获取每个文件的 unstaged + staged diff。
6. 解析 unified diff hunk。
7. 只审查新增行。
8. 聚合 issue、file summary、riskLevel、testsToAdd、patchPlan。

当前本地规则识别：

- 新增 printStackTrace。
- 新增 System.out.println。
- 新增 password / secret / api key 相关敏感关键词。
- 新增 catch Exception 且未重新抛出的宽泛异常捕获。

## 6. 新增 GitReviewTools

新增工具：

1. getGitStatus

   获取只读 git status。

2. getChangedFiles

   获取 unstaged + staged changed files。

3. getFileDiff

   获取单个文件的 unified diff。

工具治理：

- 风险等级 READ_ONLY。
- 走 AgentToolAuditService。
- 走 AgentToolNotifier。
- 已注册到 AgentService。

## 7. 验证结果

### 7.1 单元测试

执行命令：

    mvn -q -pl chatbot-service -Dtest=CodeReviewAgentServiceTest test

结果：

    Exit code: 0

新增覆盖：

- Git diff 审查能识别新增行中的 printStackTrace。
- Git diff 审查返回 GIT_DIFF scopeType。
- Git diff 审查返回 runtime steps。

### 7.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 8. 当前限制

阶段 4 仍保持保守：

- 只做本地规则 diff 审查，未调用 LLM 做复杂语义审查。
- 只审查新增行，不对上下文行做深度推理。
- 不支持 GitHub PR API。
- 不支持行级评论写回。
- 不生成 patch。
- 不执行测试命令。
- 不持久化 code_review_run / issue。

这些限制符合阶段 4 的安全边界。

## 9. 下一阶段建议

阶段 5 建议做 Patch 建议与用户确认。

目标：

- 对低风险问题生成 suggested patch。
- patch 先写入 workspace 报告或 patch 文件。
- 用户确认后才允许更新 workspace 文件。
- 大规模修改必须 Pending Action。

阶段 5 仍不建议直接 commit/push。
