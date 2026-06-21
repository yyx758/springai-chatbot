# 代码审查专家 Agent 阶段 16 执行报告

## 1. 本阶段目标

本阶段目标是优化 Git diff 审查体验：发起 Git diff 审查前展示 changed files，并支持选择部分变更文件审查。

## 2. 后端改动

CodeReviewGitDiffRequest 新增：

    relativePaths

CodeReviewAgentService.reviewGitDiff 会在 Git changed files 中按 relativePaths 做交集筛选，只审查用户选择的变更文件。

新增接口：

    GET /api/chat/agent/review/git-diff/files?sessionId={sessionId}&maxFiles=50

返回当前仓库可审查的 changed files。

## 3. 前端改动

Start Review 选择 Git diff 审查时：

- 隐藏 workspace 文件选择器。
- 显示 Changed files 选择器。
- 点击“加载变更”读取 Git diff changed files。
- 可多选部分文件。
- 不选择文件时默认审查全部可审查变更。

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

- changed files 只列文件名，不展示 diff 摘要。
- 多选仍使用浏览器原生 select。
- 如果服务器工作区没有未提交/暂存 diff，列表为空。
- 仅审查 isReviewableSource 认可的源文件。

## 6. 下一阶段建议

下一阶段建议增加 Git diff 摘要预览：

1. changed file 列表显示每个文件新增/删除行数。
2. 支持点击文件查看 diff 片段。
3. 选择文件后再发起审查。
