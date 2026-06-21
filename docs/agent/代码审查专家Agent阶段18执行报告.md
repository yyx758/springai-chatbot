# 代码审查专家 Agent 阶段 18 执行报告

## 1. 本阶段目标

本阶段目标是增加 Git diff 文件级预览：用户在 changed files 中选择文件后，可以先查看该文件 diff 片段，再决定是否发起审查。

## 2. 后端改动

新增接口：

    GET /api/chat/agent/review/git-diff/file?sessionId={sessionId}&path={path}

返回：

- path
- diffPreview
- summary

summary 复用新增/删除行数统计。

## 3. 前端改动

Git diff 模式下 Changed files 选择器增加 onchange 行为：

- 选择文件后自动加载第一个选中文件的 diff preview。
- 多选时只预览第一个选中文件。
- 预览区域限制高度，可滚动。
- 浏览器端最多展示 12000 字符，超出显示 clipped 提示。

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

- 多选时只预览第一个文件。
- diff preview 仍是纯文本 pre，不是高亮 diff 组件。
- 后端会再次读取 diff 生成 summary，后续可优化缓存。

## 6. 下一阶段建议

下一阶段建议优化 diff 展示体验：

1. 增加 diff 高亮。
2. 多选文件时支持切换预览目标。
3. 将 diff preview 和审查按钮整合成更完整的 Git diff review 面板。
