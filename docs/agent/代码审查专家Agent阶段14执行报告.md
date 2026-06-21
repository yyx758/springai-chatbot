# 代码审查专家 Agent 阶段 14 执行报告

## 1. 本阶段目标

本阶段目标是补齐前端发起审查入口，让用户不再需要通过浏览器控制台或手写 API 调用来触发代码审查。

范围：

- 在代码审查弹窗中新增 Start Review 区域。
- 支持单文件审查、工作区审查、Git diff 审查。
- 审查完成后自动刷新 Review Runs。
- 如果返回 runId，自动加载对应 Issue Cards。

## 2. 前端改动

chat.html 的代码审查弹窗左侧新增：

- 审查类型选择：单文件审查 / 工作区审查 / Git diff 审查。
- 文件路径输入框。
- 审查重点输入框。
- 最大问题数输入框。
- 开始审查按钮。
- 审查状态提示。

## 3. 调用接口

单文件审查：

    POST /api/chat/agent/review/file

工作区审查：

    POST /api/chat/agent/review/workspace

Git diff 审查：

    POST /api/chat/agent/review/git-diff

## 4. 使用方式

用户打开聊天页后：

1. 点击左侧“代码审查”。
2. 在 Start Review 中选择审查类型。
3. 单文件审查填写 relativePath。
4. 工作区审查可选填多个路径，用逗号或换行分隔；留空则按默认范围选择文件。
5. Git diff 审查不需要填写路径。
6. 可选填写审查重点。
7. 点击“开始审查”。
8. 审查完成后自动刷新 Review Runs 和 Issue Cards。

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

- 文件路径仍需要用户知道 workspace 中的 relativePath。
- 工作区审查路径选择还不是可点选的文件树。
- Git diff 审查依赖服务器当前仓库工作区有变更。
- 前端仍使用较轻量的表单，没有独立审查配置页面。

## 7. 下一阶段建议

下一阶段建议做文件选择体验：

1. 在审查表单中接入 workspace 文件列表。
2. 支持点击文件填入 relativePath。
3. 工作区审查支持多选文件。
4. Git diff 审查前展示 changed files。
