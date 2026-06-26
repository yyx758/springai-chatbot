# 代码审查工作台布局 v2 优化计划

## 1. 结论

Review Runs 不是修复历史，而是审查历史。

它记录的是每次代码审查的 run，例如单文件审查、workspace 审查、Git diff 审查，以及该次审查的风险等级、摘要、文件数量和时间。

当前把 Review Runs 常驻在左栏，会占用过多横向空间，导致真正重要的 Issue List 和 Diff 审核区被压缩。下一版应把 Review Runs 从主工作区移出，只保留一个“历史审查”入口。

核心调整：

- 默认不常驻 Review Runs。
- 主工作区聚焦 Issue List 和 Issue Detail / Diff。
- 右侧 Diff 面板支持动态推拉、扩大和收起。
- Issue List 重新设计为更紧凑、更漂亮、更适合扫描的卡片。
- 修复流程仍保持：选择 issue -> Agent 生成修复 -> 审查 diff -> 接受或放弃。

## 2. 当前 UI 问题

### 2.1 Review Runs 占用主空间

Review Runs 是审查历史，不是用户当前处理问题的主路径。它常驻左栏后，会让页面形成四块信息：

- Header
- Review Runs
- Issue List
- Fix Proposal / Diff

用户当前真正需要处理的是“这次审查发现了哪些问题”和“这个问题怎么修”，不是一直看历史 run。

### 2.2 Diff 审核窗口太小

当前右栏宽度固定，diff 内容容易横向和纵向都被截断。代码 diff 是修复流程里最关键的信息，不应该被挤在小窗口里。

### 2.3 Issue List 视觉质量不足

当前 issue card 的问题：

- 卡片过高。
- 标题换行不够优雅。
- badge 和文本层级不够清楚。
- file:line、category、status 混在一起。
- 列表留白和密度不平衡。
- 当前选中态不够强。

### 2.4 状态与按钮仍需进一步收敛

Issue 状态和修复状态应该分开：

- Issue 状态：这个问题现在处于什么处理结果。
- Fix 状态：当前修复草案进行到哪一步。

不要让用户误解“接受”是接受 issue、接受建议，还是应用代码修改。

## 3. 新目标布局

整体改为“主工作区 + 可展开历史 + 可推拉 Diff”的结构。

### 3.1 顶部 Header

Header 显示：

- Code Review Workbench。
- 当前 session / workspace。
- 当前 run 摘要。
- 当前 run 风险等级。
- 当前分支或 Git diff 来源。
- New Review 按钮。
- Review History 按钮。
- Refresh 按钮。

Header 中的 Review History 按钮用于打开历史审查抽屉。

### 3.2 Review Runs 改为隐藏入口

Review Runs 默认隐藏。

入口形式：

- Header 右侧按钮：Review History。
- 或 Header 当前 run 信息旁边的下拉入口。

打开方式：

- 右侧 Drawer。
- 或左侧 Slide-over。
- 或 Modal。

推荐：右侧 Drawer。

原因：

- 不占主工作区宽度。
- 用户需要切换历史 run 时再打开。
- 和“历史审查”语义匹配。

Drawer 内容：

- run 列表。
- scopeType。
- riskLevel。
- createdTime。
- reviewedFileCount。
- summary 一句话。

点击某个 run 后：

- 关闭 Drawer。
- Header 更新当前 run。
- Issue List 加载该 run 的 issues。
- Detail 面板展示默认 issue 或空态。

## 4. 主工作区布局

Review Runs 隐藏后，主工作区只保留两大区域：

1. Issue List。
2. Issue Detail / Fix Proposal / Diff。

推荐布局：

- 左侧 Issue List：360px 到 420px，可固定。
- 右侧 Detail / Diff：占据剩余空间。
- 中间加入可拖拽分割线。

当用户进入 Diff 审核时：

- 右侧自动扩大。
- Issue List 可以被压缩到 320px。
- 或提供“专注 Diff”按钮，把 Issue List 暂时收起。

## 5. 动态推拉设计

### 5.1 可拖拽分栏

在 Issue List 和 Detail / Diff 之间增加 resizer。

宽度约束：

- Issue List 最小宽度：300px。
- Issue List 默认宽度：380px。
- Issue List 最大宽度：520px。
- Detail / Diff 最小宽度：520px。

用户拖动后：

- 当前宽度保存在前端内存。
- 可选保存到 localStorage。

### 5.2 专注 Diff 模式

在 Fix Proposal 顶部增加按钮：

- Expand Diff
- Collapse Diff

Expand Diff 后：

- Issue List 折叠为窄条或隐藏。
- Detail / Diff 占据主要区域。
- Header 仍保留，方便返回。

Collapse Diff 后：

- 恢复 Issue List 和 Detail 双栏。

### 5.3 Diff 区域内部滚动

Diff 面板应支持：

- 垂直滚动。
- 横向滚动。
- sticky 文件头。
- 新增/删除统计固定显示。

Diff 不要再被卡片高度限制在过小区域。

## 6. Issue List 重新设计

### 6.1 卡片内容

每张 issue card 只显示：

- severity badge。
- title。
- category。
- file:line。
- issue status。
- 一句话摘要。

不显示：

- impact。
- recommendation。
- evidence 完整代码。
- diff。
- 修复按钮组。

### 6.2 视觉层级

推荐卡片结构：

第一行：

- 左侧：标题，最多两行。
- 右侧：severity badge。

第二行：

- category。
- file:line。

第三行：

- status pill。
- 修复状态小标识，例如 Draft、Applied。

第四行：

- 一句话摘要，最多两行，超出省略。

### 6.3 卡片样式

保持米白色卡片风格，但提高质感：

- border-radius: 18px。
- 默认边框：1px solid rgba。
- hover：轻微阴影。
- selected：暖色高亮边框 + 左侧 3px accent bar。
- severity badge 使用统一尺寸。

建议颜色：

- HIGH：浅红底 + 深红字。
- MEDIUM：浅橙底 + 棕橙字。
- LOW：浅蓝底 + 蓝字。
- INFO：浅灰底 + 灰字。

### 6.4 列表顶部

Issue List 顶部显示：

- 标题：Issues。
- 数量：3 issues。
- severity filter。
- status filter。
- search input 可选。

筛选器不应挤压标题。建议标题和筛选器分两行。

## 7. 右侧 Detail / Fix Proposal

右侧面板根据状态切换内容。

### 7.1 未选中 issue

显示空态：

- 请选择一个问题查看详情。
- 可提示当前 run 的 issue 数量。

### 7.2 Issue Detail

展示：

- issue 标题。
- severity / category / status。
- filePath:startLine-endLine。
- description。
- evidence 代码片段。
- impact。
- recommendation。
- 主按钮：生成修复草案。
- 次按钮：忽略、标记已处理。

### 7.3 Fix Generating

点击生成修复草案后，右侧切换为生成中：

- 显示 stepper。
- 当前步骤高亮：生成修复草案。
- 显示 spinner。
- 展示状态文案：
  - Agent 正在读取 issue 上下文。
  - Agent 正在生成完整文件修复草案。
  - 正在生成 diff。

即使后端仍是同步接口，前端也必须把等待过程显式展示出来。

### 7.4 Fix Proposal / Diff Review

修复草案生成后，右侧展示：

- issue 标题。
- file path。
- +N / -N。
- warnings。
- diff viewer。
- 接受修改。
- 放弃修改。
- 重新生成。
- Expand Diff。

Diff viewer 尽量占据右侧主要空间。

### 7.5 Applied / Rejected

接受修改后：

- 状态显示 APPLIED 或 FIXED。
- 展示“修改已应用到 workspace”。
- 提供查看 diff 按钮。

放弃修改后：

- 回到 Issue Detail。
- 展示“已放弃本次修复草案”。

## 8. 状态关系说明

### 8.1 Review Run

Review Run 是一次审查历史。

Review Run 不等于修复历史。

Review Run 保存：

- 审查范围。
- 风险等级。
- 摘要。
- 时间。
- 文件数量。
- 该 run 下的 issues。

### 8.2 Issue 状态

建议 UI 展示状态：

- OPEN：待处理。
- FIX_PROPOSED：已有修复草案，等待审核。
- APPLIED：用户已接受并应用修改。
- DISMISSED：用户忽略。
- FIXED：用户确认已修复。

如果后端暂时没有 FIX_PROPOSED / APPLIED：

- 前端可先用本地状态展示。
- 后端持久状态继续使用现有 OPEN / ACCEPTED / IGNORED / FIXED。

### 8.3 Fix 流程状态

Fix 流程状态只影响右侧 Fix Proposal：

- IDLE。
- GENERATING。
- REVIEWING_DIFF。
- APPLYING。
- APPLIED。
- FAILED。

不要把 Fix 流程状态直接混入 run 列表。

## 9. 前端实现边界

本次仍不改后端接口。

允许修改：

- chat.html 的代码审查 modal DOM。
- 代码审查相关 CSS。
- 代码审查相关 JS 状态管理。
- Review Runs 的展示位置。
- Issue Card 的展示字段。
- Fix Proposal 的布局。

不修改：

- CodeReviewAgentController。
- CodeReviewAgentService。
- AgentPendingActionService。
- 数据库 migration。
- RAG / Kafka / ChatContext。

## 10. 推荐实现步骤

### 阶段 1：隐藏 Review Runs

- Header 增加 Review History 按钮。
- Review Runs 移入 Drawer / Modal。
- 主工作区移除常驻 Review Runs 栏。
- 切换 run 后刷新 Issue List。

### 阶段 2：重构主工作区

- 主区域改成 Issue List + Detail / Fix 双栏。
- Issue List 默认宽度约 380px。
- Detail / Fix 占剩余空间。
- 右侧空态、详情态、修复态拆清楚。

### 阶段 3：重做 Issue Card

- 移除长文本和代码片段。
- 标题最多两行。
- 摘要最多两行。
- 增加 selected 高亮。
- 统一 severity badge。

### 阶段 4：扩大 Diff 体验

- Fix Proposal 中 diff 占据主要空间。
- 增加 Expand Diff / Collapse Diff。
- 增加可拖拽 resizer 或先实现固定展开模式。

### 阶段 5：状态文案统一

- 明确 Review Run 是审查历史。
- Issue 状态和 Fix 流程状态分开显示。
- 接受修改只出现在 Diff 审核阶段。

## 11. 验收标准

完成后应满足：

- Review Runs 不再常驻主页面。
- 用户通过 Review History 入口查看历史审查。
- 主页面优先展示 Issue List 和 Detail / Diff。
- Diff 审核区域明显变大。
- 用户可以展开/收起 Diff 区域。
- Issue List 卡片更紧凑，能快速扫描。
- 当前选中 issue 有明显视觉高亮。
- 右侧面板始终对应当前选中 issue。
- 接受修改和 issue 状态不再语义混乱。
- 不修改后端接口。

## 12. 测试建议

手工测试：

1. 打开代码审查弹窗，确认 Review Runs 默认隐藏。
2. 点击 Review History，确认能看到历史 run。
3. 选择历史 run，确认 Issue List 刷新。
4. 点击 issue card，确认右侧详情同步变化。
5. 确认 issue card 不再展示大段详情和代码片段。
6. 点击生成修复草案，确认右侧显示生成中状态。
7. 生成完成后确认 diff 区域足够大。
8. 点击 Expand Diff，确认 diff 面板扩大。
9. 点击 Collapse Diff，确认恢复普通布局。
10. 接受修改后确认 workspace 文件更新。
11. 放弃修改后确认不创建或应用修改。

构建验证：

- mvn -q -pl chatbot-service -DskipTests package

## 13. 和上一版计划的关系

上一版 code-review-workbench-ui-ia-plan.md 解决的是三栏信息架构。

本 v2 计划进一步调整：

- Review Runs 从常驻栏改为隐藏历史入口。
- 主工作区从三栏变成更聚焦的两栏。
- Detail / Diff 面板支持动态推拉。
- Issue List 视觉重新设计。

实际实施时，以本 v2 计划为准。
