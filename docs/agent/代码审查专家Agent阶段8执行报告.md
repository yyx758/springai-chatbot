# 代码审查专家 Agent 阶段 8 执行报告

## 1. 本阶段目标

本阶段目标是实现前端 issue 卡片展示，让阶段 7 的持久化数据可以在聊天页面直接查看。

范围：

- 增加代码审查入口。
- 增加 review run 列表。
- 增加 issue card 展示。
- patchable issue 支持复制 suggestedPatch。

不做：

- 直接应用 patch。
- 前端构造 replacementContent。
- 前端触发 apply-request。

## 2. 实现结果

已完成。

修改文件：

    chatbot-service/src/main/resources/templates/chat.html

新增入口：

- 侧栏“代码审查”

新增 Modal：

- codeReviewModal

新增前端函数：

- openCodeReviewModal
- loadReviewRuns
- renderReviewRuns
- loadReviewIssues
- renderReviewIssues
- copyReviewPatch

## 3. 前端行为

用户点击侧栏“代码审查”后：

1. 打开代码审查弹窗。
2. 请求当前 session 的 review runs：

       GET /api/chat/agent/review/runs?sessionId={activeSessionId}&limit=20

3. 渲染左侧 review run 列表。
4. 默认加载第一条 run 的 issues。
5. 请求 issue cards：

       GET /api/chat/agent/review/runs/{runId}/issues

6. 渲染右侧 issue cards。

## 4. Issue Card 展示内容

每张 issue card 展示：

- severity
- category
- title
- filePath:startLine
- description
- evidence
- impact
- recommendation
- suggestedPatch 复制按钮

severity 使用不同颜色：

- BLOCKER/HIGH：红色
- MEDIUM：橙色
- LOW：蓝色
- INFO：灰色

## 5. Patch 建议入口

如果 issue 满足：

- patchable = true
- suggestedPatch 非空

前端显示：

    复制修复建议

当前只复制 suggestedPatch，不自动应用。

原因：

- apply-request 需要完整 replacementContent。
- issue 级 suggestedPatch 目前仍是建议文本，不是严格 full-file replacement。
- 为避免误改，本阶段不在前端直接触发 apply。

## 6. 验证结果

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

- 没有 run 详情页。
- 没有 issue 状态更新。
- 没有一键生成 replacementContent。
- 没有 apply-request 前端确认流程。
- 没有按 severity/filter 搜索。

## 8. 下一阶段建议

下一阶段建议做 issue 状态和 apply 绑定：

1. 新增 issue 状态更新接口，例如 OPEN / ACCEPTED / IGNORED / FIXED。
2. Patch suggestion 文件绑定 issueId。
3. 前端增加“申请应用到 workspace”按钮。
4. 弹窗展示 replacementContent 预览。
5. 用户确认后调用 /patch/apply-request 创建 Pending Action。
