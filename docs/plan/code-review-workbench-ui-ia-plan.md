# 代码审查页面 UI 信息架构优化计划

## 1. 背景与目标

当前代码审查弹窗把 Start Review、Review Runs、Issue Cards、Fix Review Panel 同时堆在一个页面里，导致用户很难判断当前应该先看哪里、当前选中的 issue 是什么、修复草案和 issue 的关系是什么。

本计划只重构前端布局和交互，不修改后端接口，不改变代码审查核心能力。

目标：

- 把代码审查页面改成三栏工作台。
- 让 Review Run、Issue List、Issue Detail / Fix Panel 有清晰层级。
- 让用户从“选择 issue”到“生成修复”再到“审核 diff”形成连续路径。
- 减少 Issue Card 长文本，提高扫描效率。
- 用清晰状态机表达 issue 状态和修复流程状态。
- 保持现有米白色背景和卡片风格。

## 2. 当前问题

### 2.1 页面层级混乱

当前 Start Review、Review Runs、Issue Cards、Fix Review Panel 同时常驻，用户无法快速判断主任务是发起审查、查看历史、浏览问题，还是处理修复草案。

### 2.2 Issue Cards 信息过载

每张 issue card 直接展示 description、evidence、impact、recommendation 和代码片段，导致列表过长，不适合扫描和筛选。

### 2.3 Fix Review Panel 位置不清晰

Fix Review Panel 放在列表底部，和当前选中的 issue 缺少强绑定关系。用户看到 diff 时，不一定知道它属于哪个 issue。

### 2.4 状态语义混杂

当前 OPEN / FIXED 这类 issue 状态，与“接受修改”“等待审核”这类修复流程状态混在一起，用户不容易理解“接受”到底是接受 issue、接受建议，还是应用代码修改。

## 3. 目标信息架构

整体改为三栏工作台：

1. 顶部 Header
2. 左栏 Review Runs
3. 中栏 Issue List
4. 右栏 Issue Detail / Fix Panel

布局建议：

- 左栏宽度：约 280px。
- 中栏宽度：自适应，占据主要浏览空间。
- 右栏宽度：约 460px。
- 整体保持米白色背景、圆角卡片、轻阴影。

## 4. 顶部 Header

Header 用来表达当前审查上下文，不承载复杂表单。

显示内容：

- 项目名或 workspace 名。
- 当前分支或 Git diff 来源。
- 当前选中的 Review Run 摘要。
- New Review 按钮。

建议结构：

- 左侧：Code Review Workbench / 当前项目名。
- 中间：当前分支、当前 run 类型、riskLevel。
- 右侧：New Review 按钮、刷新按钮。

New Review 按钮点击后打开 Modal 或 Drawer，不再让 Start Review 常驻左栏。

## 5. New Review Modal / Drawer

Start Review 改成按需打开。

Modal 内容：

- 审查范围：
  - 单文件审查。
  - 工作区审查。
  - Git diff 审查。
- 文件路径：
  - 单文件时必填。
  - 工作区审查可多选。
  - Git diff 审查可选 changed files。
- 审查重点：
  - 可选输入，例如 security、reliability、performance。
- 审查强度：
  - 快速。
  - 标准。
  - 深度。
- 开始审查按钮。

交互规则：

- 打开 Modal 时默认选择上一次使用的审查类型。
- Git diff 模式下显示 changed files 加载入口。
- 开始审查后关闭 Modal，并在 Header / 左栏中显示新 run。
- 审查失败时错误显示在 Modal 内，不弹 alert。

## 6. 左栏：Review Runs

左栏只做历史审查摘要，不再放 Start Review。

每个 run card 只显示：

- scopeType。
- riskLevel badge。
- summary 一句话截断。
- createdTime。
- reviewedFileCount。

选中态：

- 当前 run card 增加高亮边框。
- 切换 run 后，中栏刷新 issue list，右栏清空或展示该 run 的第一个 issue。

不在左栏显示：

- 文件选择表单。
- 审查重点输入框。
- 开始审查按钮。
- 大段 review summary。

## 7. 中栏：Issue List

中栏只展示问题摘要列表，目标是快速扫描。

每张 Issue Card 只显示：

- severity badge。
- issue 标题。
- category。
- file:line。
- status。
- 一句话摘要。

不在列表中显示：

- 完整 description。
- impact。
- recommendation。
- evidence 代码片段。
- suggestedPatch。
- diff。

选中态：

- 当前选中的 issue card 使用高亮边框。
- hover 时轻微阴影增强。
- 点击 card 后右栏展示完整详情。

筛选和排序：

- 顶部保留 status filter。
- 可增加 severity filter。
- 默认排序建议：BLOCKER / HIGH 优先，其次 OPEN 优先，再按文件顺序。

## 8. 右栏：Issue Detail / Fix Panel

右栏是当前 issue 的完整上下文和修复流程承载区。

### 8.1 Issue Detail 状态

用户点击 issue 后，右栏显示完整详情：

- 标题。
- severity / category / status。
- filePath:startLine-endLine。
- description。
- evidence 代码片段。
- impact。
- recommendation。
- 操作按钮：
  - 生成修复建议。
  - 忽略。
  - 标记已处理。

代码片段只在右栏展示，列表中不展示。

### 8.2 Fix Proposal 状态

点击“生成修复建议”后，右栏切换到 Fix Proposal。

Fix Proposal 显示：

- 当前 issue 摘要。
- 修复流程 stepper。
- Agent 状态文案。
- diffPreview。
- warnings。
- 操作按钮：
  - 接受修改。
  - 放弃修改。
  - 重新生成。

Fix Proposal 必须和当前 issue 强绑定，右栏顶部展示 issue 标题和 filePath。

### 8.3 不再保留底部 Fix Review Panel

移除页面底部独立 Fix Review Panel。

所有修复草案、diff、warnings、接受/放弃操作都归入右栏。

## 9. 状态机设计

### 9.1 Issue 状态

前端统一展示以下状态：

- OPEN：待处理。
- FIX_PROPOSED：Agent 已生成修复草案，等待用户审核 diff。
- APPLIED：用户已接受并应用修改。
- DISMISSED：用户忽略该问题。
- FIXED：用户确认问题已处理。

兼容策略：

- 后端当前如果只支持 OPEN / ACCEPTED / IGNORED / FIXED，前端可以先做显示映射。
- ACCEPTED 不再作为主要用户可见状态，避免和“接受修改”混淆。
- IGNORED 显示为 DISMISSED。
- 接受 diff 并应用成功后，前端可把 issue 更新为 FIXED，或本地显示 APPLIED。

### 9.2 修复流程状态

右栏 Fix Proposal 显示流程：

1. 生成修复草案。
2. 审查 Diff。
3. 应用修改。
4. 完成。

对应 UI 状态：

- generating：Agent 正在生成修复草案。
- reviewing：diff 已生成，等待用户审核。
- applying：正在应用修改。
- completed：已完成。
- failed：失败，可重试。

## 10. 交互流程

### 10.1 发起新审查

1. 用户点击 Header 的 New Review。
2. 打开 Modal / Drawer。
3. 用户选择审查范围、文件、重点、强度。
4. 点击开始审查。
5. Modal 显示 loading。
6. 审查完成后关闭 Modal。
7. 左栏新增 run。
8. 中栏展示 issues。
9. 右栏默认展示第一个 high priority issue，或展示空态提示。

### 10.2 浏览问题

1. 用户在左栏选择 run。
2. 中栏刷新 issue list。
3. 用户点击 issue card。
4. 右栏展示完整 Issue Detail。

### 10.3 生成修复建议

1. 用户在右栏点击“生成修复建议”。
2. 右栏切到 Fix Proposal。
3. 显示 generating 状态。
4. 调用现有 patch/preview 接口。
5. 成功后显示 diff。
6. 状态切到 reviewing。

### 10.4 接受或放弃修改

接受修改：

1. 用户点击“接受修改”。
2. 状态切到 applying。
3. 调用现有 patch/apply-request。
4. 调用现有 actions/{actionId}/confirm。
5. 成功后状态切到 completed。
6. issue 状态显示 APPLIED 或 FIXED。

放弃修改：

1. 用户点击“放弃修改”。
2. 丢弃当前 proposal。
3. 回到 Issue Detail。
4. 不调用 apply-request。
5. 不修改 workspace 文件。

重新生成：

1. 保留当前 issue。
2. 重新调用 patch/preview。
3. 新 diff 覆盖旧 proposal。

## 11. 视觉规范

保持现有风格：

- 米白色背景。
- 软圆角。
- 轻阴影。
- 暖色系文字。
- 卡片式布局。

新增规范：

- severity badge 统一尺寸和颜色。
- 当前选中 issue card 高亮边框。
- 当前 run card 高亮边框。
- 列表区域减少正文行数。
- 右栏允许长文本滚动。
- diff 使用红绿背景，但降低饱和度，避免刺眼。
- 空态文案清晰，例如“请选择一个问题查看详情”。

## 12. 实施边界

本次只改前端布局和交互。

不改：

- 后端接口。
- 数据库表结构。
- Agent 修复生成逻辑。
- RAG。
- GitReviewService。
- Pending Action 后端安全模型。

允许：

- 调整 chat.html 中代码审查 modal 的 DOM 结构。
- 调整 CSS。
- 调整前端 JS 状态管理。
- 复用现有接口串联调用。
- 新增前端状态映射和本地 UI 状态。

## 13. 预计修改文件

主要修改：

- chatbot-service/src/main/resources/templates/chat.html

可能不需要修改其他文件。

如果实现过程中发现 CSS 过大，可以后续再拆分静态 CSS 文件；本阶段不强制拆分。

## 14. 验收标准

完成后应满足：

- 页面是三栏工作台，不再是 Start Review + Runs + Cards + Fix Panel 堆叠。
- Start Review 不常驻，点击 New Review 后才打开 Modal / Drawer。
- Issue List 每张卡片只展示摘要信息。
- Issue Detail 和 Fix Proposal 都在右栏。
- Fix Review Panel 不再出现在页面底部。
- 用户能明确看到当前选中 issue。
- 用户能明确看到当前修复流程处于哪一步。
- 用户接受修改前不会写 workspace 文件。
- 用户放弃修改不会创建 apply-request。
- 所有后端接口保持兼容。

## 15. 测试建议

手工测试：

1. 打开代码审查弹窗，确认三栏布局。
2. 点击 New Review，确认打开 Modal / Drawer。
3. 发起单文件审查，确认 run 出现在左栏。
4. 点击不同 run，确认中栏 issues 刷新。
5. 点击 issue card，确认右栏展示详情。
6. 点击生成修复建议，确认右栏进入 Fix Proposal。
7. 确认 diff 展示在右栏，不再出现在底部。
8. 点击放弃修改，确认文件不变。
9. 点击接受修改，确认 workspace 文件更新。
10. 切换 status filter，确认 issue list 正常刷新。

构建验证：

- mvn -q -pl chatbot-service -DskipTests package

回归重点：

- 现有单文件审查、workspace 审查、Git diff 审查入口仍可用。
- Review Runs 仍可加载。
- Issue 状态更新仍可用。
- patch/preview 和 apply-request 仍按现有接口调用。
