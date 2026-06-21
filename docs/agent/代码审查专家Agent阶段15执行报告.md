# 代码审查专家 Agent 阶段 15 执行报告

## 1. 本阶段目标

本阶段目标是优化发起审查体验：在 Start Review 区域接入 workspace 文件列表，让用户不必手动记忆和输入文件 relativePath。

## 2. 实现结果

chat.html 的 Start Review 区域新增 Workspace files 选择器：

- 点击“加载文件”读取当前会话 workspace 文件。
- 单文件审查时选择一个文件，自动填入路径。
- 工作区审查时支持多选文件，自动用逗号拼接路径。
- Git diff 审查时隐藏文件路径和文件选择器。

## 3. 调用接口

复用已有接口：

    GET /api/agent/workspaces/current?sessionId={activeSessionId}

该接口返回当前会话 workspace 和文件列表。

## 4. 使用方式

1. 打开聊天页。
2. 点击“代码审查”。
3. 在 Start Review 中选择审查类型。
4. 点击“加载文件”。
5. 单文件审查选择一个文件；工作区审查可以按住 Ctrl/Shift 多选。
6. 点击“开始审查”。

## 5. 验证结果

本阶段复验命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 6. 当前限制

- 文件选择器仍是简单 select，不是完整文件树。
- 工作区文件较多时没有搜索过滤。
- 多选依赖浏览器原生 Ctrl/Shift 操作。
- Git diff 审查还没有展示 changed files 预览。

## 7. 下一阶段建议

下一阶段建议继续优化 Git diff 审查体验：

1. 发起 Git diff 审查前展示 changed files。
2. 支持选择其中部分 changed files。
3. 展示每个文件 diff 摘要。
4. 再发起审查。
