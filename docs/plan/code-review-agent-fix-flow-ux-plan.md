# 代码审查 Agent 修复流程 UX 优化计划

## 1. 背景与问题

当前代码审查弹窗里的 Issue Card 操作偏多，用户需要理解多组按钮和多个确认区：

- 复制修复建议
- 保存建议说明
- Agent 修复
- 接受
- 忽略
- 已修复
- Agent 修复 Diff
- Pending Apply Actions

这些入口把“审查 issue 状态管理”和“让 Agent 修复代码”混在了一起。用户真正想要的是更直接的三步：

1. 用户选择要修复的问题。
2. Agent 明确进入修复过程，并在界面上展示当前状态。
3. Agent 生成修改后，以 Git diff 形式展示给用户，用户决定接受或放弃。

目标不是增加更多按钮，而是把修复链路收敛成一个清晰、连续、可确认的体验。

## 2. 目标流程

目标交互流程：

1. 用户在 Issue Card 上点击“修复此问题”。
2. 页面打开或激活 Fix Review Panel。
3. Fix Review Panel 显示：
   - 当前 issue 标题。
   - 当前文件路径。
   - Agent 修复状态。
   - loading / progress 文案。
4. Agent 生成 replacementContent 和 diffPreview。
5. 页面展示 Git diff 风格预览：
   - 文件路径。
   - 新增 / 删除行统计。
   - 红色删除行。
   - 绿色新增行。
   - 可滚动 diff 区域。
6. 用户点击：
   - “接受修改”：应用到 workspace 文件。
   - “放弃修改”：丢弃草案，不修改文件。

用户不应该再需要单独理解 Pending Apply Actions。用户在 diff 面板点击“接受修改”本身就是确认动作。

## 3. 前端改造方案

### 3.1 Issue Card 操作收敛

Issue Card 主操作只保留：

- 修复此问题

Issue 状态类操作保留但弱化：

- 忽略
- 标记已处理

建议处理方式：

- “忽略”和“标记已处理”可以放到次级按钮或更多菜单中。
- 不再把“接受”“已修复”和“Agent 修复”并列展示，避免用户误解“接受”到底是接受 issue 还是接受代码修改。
- 移除或弱化“复制修复建议”“保存建议说明”这类非主流程按钮。

### 3.2 新增 Fix Review Panel

复用当前已有的 Agent 修复 Diff 区域，但调整为完整修复面板。

建议面板状态：

- idle：未选择修复。
- fixing：Agent 正在生成修复草案。
- review：已生成 diff，等待用户审核。
- applying：正在应用修改。
- applied：已接受并应用。
- rejected：已放弃。
- failed：生成或应用失败。

面板展示内容：

- 当前 issue 标题。
- severity / category。
- filePath:startLine。
- 当前状态文案。
- warnings。
- diffPreview。
- 操作按钮。

### 3.3 Agent 修复过程必须可见

点击“修复此问题”后，不能只在后台请求接口。

前端应立即展示：

- “Agent 正在读取问题上下文...”
- “Agent 正在生成修复草案...”
- “正在生成 Diff 预览...”

如果 v1 不新增 SSE，可以先用同步请求状态实现：

1. 请求发出前设置 fixing 状态。
2. 请求返回后切到 review 状态。
3. 请求失败后切到 failed 状态。

后续如果要增强体验，再考虑新增流式进度。

### 3.4 Git diff 风格展示

Diff 预览不要再放进浏览器 confirm。

应在页面中常驻展示，视觉参考 Git diff：

- 文件标题行：path + +N / -N。
- 删除行：红色背景。
- 新增行：绿色背景。
- 上下文行：普通背景。
- hunk header：弱化灰色。
- 支持滚动。

现有 Git diff preview 的 renderDiffPreview 可以复用或抽取为通用 diff renderer。

### 3.5 接受 / 放弃行为

放弃修改：

- 清空当前 draft 或切换为 rejected 状态。
- 不调用 patch/apply-request。
- 不调用 action confirm。
- 不修改 workspace 文件。

接受修改：

1. 调用 patch/apply-request 创建待执行 action。
2. 立即调用 actions/{actionId}/confirm。
3. 成功后刷新 issue / workspace 状态。
4. 面板切换为 applied。

用户已经在 diff 面板点击“接受修改”，这个动作等价于确认，不再额外展示 Pending Apply Actions 区域。

## 4. 后端接口策略

v1 不强制新增后端接口，优先复用现有接口：

- POST /api/chat/agent/review/patch/preview
- POST /api/chat/agent/review/patch/apply-request
- POST /api/chat/agent/actions/{actionId}/confirm

现有接口职责保持不变：

- patch/preview：只生成修复草案和 diff，不写文件。
- patch/apply-request：只创建 action，不直接写文件。
- confirm：真正更新 workspace 文件。

前端接受修改时串联调用 apply-request 和 confirm，保留后端已有安全边界。

## 5. 失败处理

Agent 无法生成修复草案：

- 面板展示失败原因。
- 保留 issue 信息。
- 提供“重试修复”按钮。
- 不弹 alert。

Diff 为空或 replacementContent 缺失：

- 禁用“接受修改”。
- 展示“Agent 未生成可应用修改”。

应用失败：

- 保留 diffPreview。
- 展示错误信息。
- 用户可以重试或放弃。

文件版本冲突：

- 展示 expectedVersion 冲突提示。
- 要求用户重新生成修复草案。

## 6. 验收标准

完成后应满足：

- Issue Card 主路径只有“修复此问题 -> Agent 修复中 -> Diff 审核 -> 接受/放弃”。
- 修复期间用户能看到 Agent 正在工作。
- 不再使用浏览器 confirm 展示 diff。
- 用户接受前不会修改 workspace 文件。
- 用户放弃后不会创建或确认 action。
- 用户接受后 workspace 文件被更新。
- Pending Apply Actions 不再作为代码修复主路径展示。

## 7. 测试建议

前端手工测试：

1. 选择一个有 filePath 的 issue，点击“修复此问题”。
2. 确认进入 fixing 状态。
3. 确认返回后展示 Git diff。
4. 点击“放弃修改”，确认文件不变。
5. 再次修复并点击“接受修改”，确认文件更新。
6. 模拟接口失败，确认错误展示在面板内，不弹出 alert。

后端回归测试：

- CodeReviewAgentServiceTest 保持通过。
- AgentPendingActionServiceTest 保持通过。
- CodeReviewPersistenceServiceTest 保持通过。

构建验证：

- mvn -q -pl chatbot-service -DskipTests package

## 8. 实施边界

本计划只优化代码审查 Agent 的修复交互，不改变：

- RAG 检索链路。
- 普通聊天链路。
- Git diff 审查入口。
- review run / issue 持久化表结构。
- 后端 Pending Action 的安全模型。

本计划也不要求 Agent 自动 commit、push 或修改真实 Git 仓库。修改范围仍限制在当前会话 workspace 文件。
