# 代码审查专家 Agent 阶段 17 执行报告

## 1. 本阶段目标

本阶段目标是增强 Git diff changed files 选择器，让用户在发起审查前能看到每个变更文件的新增/删除行数摘要。

## 2. 后端改动

`GET /api/chat/agent/review/git-diff/files` 返回结构新增：

    fileDetails

每个 file detail 包含：

- path
- additions
- deletions
- truncated
- error，可选

实现方式：

1. 先通过 git diff changed files 获取文件列表。
2. 对每个可审查源文件读取 diff。
3. 统计非 header 行中的 + 和 - 行。
4. 返回给前端展示。

## 3. 前端改动

Git diff 模式下点击“加载变更”后，Changed files 下拉项显示：

    src/main/java/App.java  +3 / -1

如果 diff 被截断，会显示：

    truncated

## 4. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest,AgentPendingActionServiceTest" test

结果：

    Exit code: 0

本阶段复验命令 2：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 5. 当前限制

- 目前只展示新增/删除行数，不展示完整 diff 内容。
- 对二进制文件或异常 diff 只返回 error 摘要。
- changed files 多时会逐个读取 diff，后续可增加缓存或限制。

## 6. 下一阶段建议

下一阶段建议增加 diff 片段预览：

1. 点击 changed file 后展示该文件 diff 片段。
2. 支持复制 diff。
3. 审查前确认选中文件和 diff 内容。
