# 代码审查专家 Agent 阶段 11 执行报告

## 1. 本阶段目标

本阶段目标是实现“可预览的 apply-request”：在不直接修改源码的前提下，把 issue 的 suggestedPatch 转换为 replacementContent 草案，展示 diff 预览，用户确认后再创建 Pending Action。

范围：

- 新增 patch preview 请求和响应模型。
- 新增后端预览接口。
- 基于简单一行 suggestedPatch 生成 replacementContent 草案。
- 前端 issue card 增加“预览申请应用”按钮。
- 用户确认 diff 后调用现有 apply-request 创建 Pending Action。

不做：

- 不直接确认 Pending Action。
- 不直接修改 workspace 文件。
- 不处理复杂多文件 patch。
- 不保证所有自然语言 suggestedPatch 都能转换为 replacementContent。

## 2. 后端改动

新增 DTO：

- CodeReviewPatchPreviewRequest
- CodeReviewPatchPreviewResult

新增接口：

    POST /api/chat/agent/review/patch/preview

请求体示例：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/App.java",
      "issueId": 12,
      "suggestedPatch": "..."
    }

返回字段：

- success
- sessionId
- workspaceId
- relativePath
- issueId
- expectedVersion
- applicable
- currentContent
- replacementContent
- diffPreview
- warnings
- message

## 3. 预览生成策略

当前采用保守的一行替换策略：

1. 从 suggestedPatch 中解析第一组 `- old` 和 `+ new`。
2. 读取 workspace 当前文件内容。
3. 在当前文件中查找与 old trim 后相等的行。
4. 保留原行缩进，把该行替换为 new。
5. 生成 replacementContent 和 diffPreview。
6. 如果找不到 old 行或 suggestedPatch 不是简单替换，则 applicable=false，不创建 apply-request。

这个策略只覆盖当前本地规则生成的简单建议，例如：

    - e.printStackTrace();
    + log.warn("Operation failed", e);

## 4. 前端行为

chat.html 的 issue card 新增按钮：

    预览申请应用

点击后流程：

1. 调用 `/api/chat/agent/review/patch/preview`。
2. 如果 applicable=false，弹窗提示 warnings 和 diffPreview。
3. 如果 applicable=true，使用 confirm 展示文件、issueId、warnings 和 diff 预览。
4. 用户确认后调用 `/api/chat/agent/review/patch/apply-request`。
5. 返回 actionId，提示用户后续仍需通过 Pending Action 确认流真正执行。

## 5. 安全边界

- preview 只读 workspace 文件，不写文件。
- preview 校验 sessionId 必须属于当前用户。
- 如果传 issueId，会校验 issue 属于当前用户且属于当前 session。
- apply-request 只创建 PENDING action，不执行文件更新。
- 真正文件更新仍由 `/api/chat/agent/actions/{actionId}/confirm` 执行。

## 6. 测试覆盖

新增测试：

- CodeReviewAgentServiceTest.createPatchPreviewBuildsReplacementContent

覆盖点：

- issueId 归属读取。
- workspace 当前文件读取。
- suggestedPatch 一行替换生成 replacementContent。
- diffPreview 包含删除行和新增行。
- 不调用 PendingActionService，确认 preview 不产生写操作。

## 7. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest" test

结果：

    Exit code: 0

本阶段复验命令 2：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 8. 当前限制

- 当前只支持简单一行替换。
- 使用 logger 的草案可能需要目标文件已有 logger 字段或 import。
- replacementContent 仍需用户通过 diff 确认后才创建 apply-request。
- apply-request 创建后还需要 Pending Action 确认，才会真正更新 workspace 文件。
- 复杂修复仍应走建议文件或人工编辑 replacementContent。

## 9. 下一阶段建议

下一阶段建议增强 replacementContent 生成能力：

1. 支持多行 patch 解析。
2. 针对 logger/import 缺失给出更明确的改动提示。
3. 改为前端 modal 展示 diff，而不是 window.confirm。
4. 增加 pending action 列表入口，让用户能在同一弹窗中确认或查看状态。
