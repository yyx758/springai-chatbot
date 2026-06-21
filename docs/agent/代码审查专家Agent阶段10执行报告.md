# 代码审查专家 Agent 阶段 10 执行报告

## 1. 本阶段目标

本阶段延续阶段 9 的后续建议，补齐两个低风险闭环：issue 状态筛选，以及 patch suggestion 与具体 issue 的绑定。

范围：

- issue 列表支持按状态筛选。
- 前端代码审查弹窗支持状态下拉筛选。
- issue card 支持生成与 issueId 绑定的 patch suggestion 文件。
- patch suggestion 请求和返回结构携带 issueId。

不做：

- 不自动应用 suggestedPatch。
- 不生成 full-file replacementContent。
- 不新增批量状态操作。
- 不做 run 详情页和 Agent steps 持久化。

## 2. 后端改动

### 2.1 issue 状态筛选

CodeReviewPersistenceService 新增：

    listIssues(Long userId, String runId, String status)

行为：

- status 为空或 ALL 时返回该 run 下全部 issue。
- status 非空时复用状态白名单校验。
- 只允许 OPEN / ACCEPTED / IGNORED / FIXED。
- 非法状态返回 400。
- 查询仍按 userId + runId 过滤，避免跨用户读取。

CodeReviewAgentController 的 issue 查询接口增加可选参数：

    GET /api/chat/agent/review/runs/{runId}/issues?status=OPEN

返回中增加 status 字段，便于前端确认当前筛选条件。

### 2.2 patch suggestion 绑定 issueId

CodeReviewPatchSuggestionRequest 新增：

    issueId

CodeReviewPatchSuggestionResult 新增：

    issueId

CodeReviewAgentService 生成建议文件时：

- 如果请求包含 issueId，默认文件名会包含 issue-{issueId}。
- Markdown 建议文件头部写入 Issue ID。
- 仍只写入 workspace 建议文件，不修改目标源码。

## 3. 前端改动

chat.html 的代码审查弹窗新增：

- Issue Cards 右侧状态筛选下拉框。
- 筛选项：全部、OPEN、ACCEPTED、IGNORED、FIXED。
- 切换筛选后重新请求当前 run 的 issue 列表。
- 每个 issue card 新增“生成建议文件”按钮。

生成建议文件调用：

    POST /api/chat/agent/review/patch-suggestion

请求体包含：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/App.java",
      "issueId": 12,
      "maxIssues": 3
    }

成功后前端提示生成的 suggestionPath。

## 4. 测试覆盖

新增/调整测试：

- CodeReviewPersistenceServiceTest
  - 非法状态筛选返回 400。
- CodeReviewAgentServiceTest
  - patch suggestion 请求携带 issueId。
  - 返回结果携带 issueId。
  - suggestionPath 包含 issue-{issueId}。

## 5. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest" test

结果：

    Exit code: 0

本阶段复验命令 2：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 6. 当前限制

- issueId 当前作为建议文件元数据和文件名绑定，不代表 suggestedPatch 已可自动应用。
- 生成建议文件仍会重新执行一次单文件审查，后续可以改为基于已持久化 issue 生成更精确的 issue-level suggestion。
- 前端仍没有 replacementContent 预览和二次确认。
- apply-request 仍需要调用方提供完整 replacementContent。

## 7. 下一阶段建议

下一阶段建议做“可预览的 apply-request”：

1. 基于 issue 或建议文件生成 replacementContent 草案。
2. 前端展示目标文件原文、replacementContent 和差异摘要。
3. 用户确认后调用 /api/chat/agent/review/patch/apply-request。
4. 继续复用 Pending Action 的确认流，避免直接修改源码。
