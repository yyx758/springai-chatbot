# 代码审查专家 Agent 阶段 5 执行报告

## 1. 本阶段目标

阶段 5 目标是引入 Patch 建议能力，但保持安全边界：

- 可以生成 suggestedPatch。
- 可以把修复建议写入 workspace Markdown 文件。
- 不直接修改源代码。
- 不 commit。
- 不 push。
- 不自动应用 patch。

本阶段实现的是“建议文件生成”，不是“自动修复”。

## 2. 实现结果

已完成。

新增接口：

    POST /api/chat/agent/review/patch-suggestion

请求示例：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/com/example/App.java",
      "focus": "reliability",
      "maxIssues": 5
    }

可选自定义输出路径：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/com/example/App.java",
      "suggestionPath": "review/patch-suggestions/app-review.md"
    }

默认输出路径：

    review/patch-suggestions/{fileName}-{runId}.md

## 3. 新增代码

新增 DTO：

- CodeReviewPatchSuggestionRequest.java
- CodeReviewPatchSuggestionResult.java

修改：

- CodeReviewAgentService.java
  - 新增 createPatchSuggestion。
  - 审查 issue 增加 patchable / suggestedPatch 填充。
  - 新增 patch suggestion Markdown 生成。

- CodeReviewAgentController.java
  - 新增 /patch-suggestion 接口。

- CodeReviewAgentServiceTest.java
  - 新增 patch suggestion 写入 workspace 文件测试。

## 4. 当前 Patch 建议逻辑

本地规则当前会为以下问题生成 suggestedPatch 或修复建议：

1. printStackTrace

   建议改为结构化 logger。

2. System.out.println

   建议改为项目统一 logger。

3. 敏感字段关键词

   建议不要硬编码或输出敏感信息，改用环境变量或密钥管理。

4. 宽泛异常捕获

   建议补充结构化日志、失败传播或业务降级策略。

注意：

- suggestedPatch 是建议，不是自动应用的 diff。
- patch suggestion 文件是 Markdown 报告。
- 源文件不会被修改。

## 5. 安全边界

createPatchSuggestion 执行流程：

1. 校验 userId/sessionId/relativePath。
2. 调用 reviewFile 生成审查结果。
3. 根据 issues 生成 Markdown 建议。
4. 调用 AgentWorkspaceService.createFile 写入 workspace 建议文件。
5. 返回建议文件路径和 review 结果。

不会执行：

- updateFile 修改源代码。
- Git commit。
- Git push。
- shell patch apply。
- 自动覆盖真实仓库文件。

这符合阶段 5 的“先建议、后确认”原则。

## 6. 验证结果

### 6.1 单元测试

执行命令：

    mvn -q -pl chatbot-service -Dtest=CodeReviewAgentServiceTest test

结果：

    Exit code: 0

新增覆盖：

- review issue 会生成 patchable 和 suggestedPatch。
- patch suggestion 会创建 workspace Markdown 文件。
- patch suggestion 不应用源文件修改。

### 6.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

阶段 5 仍然保守：

- 不生成严格 unified diff patch。
- 不自动修改源文件。
- 不做 Pending Action 应用。
- 不做 patch apply。
- 不运行测试验证修复效果。

这些能力应放到后续阶段。

## 8. 下一阶段建议

下一步建议做“确认后应用 workspace patch”。

建议阶段 6：

1. 新增 PatchPlan 数据结构。
2. 生成严格的文件级 suggested replacement。
3. 创建 Pending Action。
4. 用户确认后只更新 workspace 文件。
5. 更新后要求运行或提示运行相关测试。

仍不建议直接改真实本地仓库，也不建议自动 commit/push。
