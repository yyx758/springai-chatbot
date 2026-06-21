# 代码审查专家 Agent 阶段 23 执行报告

## 1. 本阶段目标

本阶段目标是把 issue 修复流程改为用户期望的 Agent 修复模式：用户选择某个 issue，Agent 生成完整文件修复草案，前端展示 diff，用户最终确认后才应用。

## 2. 后端改动

CodeReviewAgentService.createPatchPreview 增强：

- 优先尝试解析简单 `- old` / `+ new` suggestedPatch。
- 如果解析失败且存在 issueId，则调用模型生成 full-file replacementContent。
- 模型输入包含 issue 的 title、description、evidence、impact、recommendation 和当前完整文件内容。
- 模型输出必须是 JSON，并包含 replacementContent。
- 仍然只生成预览，不直接修改源码。

## 3. 前端改动

按钮文案调整：

- “预览并申请修复” 改为 “Agent 修复”。
- “保存建议说明” 明确只是保存 markdown 说明，不修改源码。

Agent 修复按钮显示条件调整：

- 只要 issue 有 filePath 就显示。
- 不再要求 issue.patchable 或 suggestedPatch。

## 4. 正确交互流程

1. 审查代码。
2. 用户选择某个 issue。
3. 点击 Agent 修复。
4. 后端生成 replacementContent 草案。
5. 前端展示 diff 和 warnings。
6. 用户确认后创建 Pending Action。
7. 用户在 Pending Apply Actions 中确认执行。
8. workspace 文件才被修改。

## 5. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

本阶段复验命令 2：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest,AgentPendingActionServiceTest" test

结果：

    Exit code: 0

## 6. 当前限制

- Agent 修复质量依赖当前可用模型。
- 当前确认 diff 仍使用浏览器 confirm，而不是正式 diff modal。
- 如果模型不可用，会提示无法生成修复草案。
