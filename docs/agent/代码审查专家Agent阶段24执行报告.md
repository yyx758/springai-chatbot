# 代码审查专家 Agent 阶段 24 执行报告：对话式 Git Diff Review 入口

## 1. 本阶段目标

根据 docs/plan/conversational-code-review-agent-plan.md 的阶段 1，完成对话式代码审查入口的第一步接入：

- 在普通聊天入口识别 Git diff 代码审查意图。
- 复用现有 CodeReviewAgentService.reviewGitDiff，不复制审查逻辑。
- 审查结果继续落到 Review Run / Issue。
- 聊天窗口返回摘要。
- 前端收到审查摘要后刷新 Review Runs / Issue Cards。
- 维护 session 级 activeReviewRunId。
- 不新增任何写 workspace 文件、confirm pending action、commit、push 能力。

## 2. 后端改动

新增包：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/conversation/

新增核心类：

- CodeReviewIntentType
- CodeReviewIntent
- CodeReviewIntentClassifier
- RuleBasedCodeReviewIntentClassifier
- CodeReviewConversationContext
- CodeReviewConversationResponse
- CodeReviewConversationOrchestrator

新增接口：

    GET  /api/chat/agent/conversation/context?sessionId={sessionId}
    POST /api/chat/agent/conversation/context/active-run

接入点：

- ChatbotService.streamChat(...)

行为：

1. 先校验 session 属于当前用户。
2. 调用规则式 intent classifier。
3. 如果命中 REVIEW_GIT_DIFF，直接调用 reviewGitDiff。
4. 发送 SSE content 消息和 reviewSummary 事件。
5. 保存聊天记录。
6. 不调用通用聊天模型。

## 3. 前端改动

修改文件：

    chatbot-service/src/main/resources/templates/chat.html

新增行为：

- SSE 处理新增 reviewSummary 分支。
- 收到 reviewSummary.runId 后设置 activeReviewRunId。
- 自动刷新 loadReviewRuns() 和 loadReviewIssues(runId)。
- 用户在 Review Workbench 切换 run 时，会调用 /api/chat/agent/conversation/context/active-run 同步后端 active run。

## 4. 安全边界

本阶段保持以下约束：

- 对话入口只触发 Git diff 审查。
- Git diff 审查仍走现有只读 GitReviewService 能力。
- 不写 workspace 文件。
- 不创建 Pending Action。
- 不 confirm Pending Action。
- 不执行 shell。
- 不 commit / push。
- Controller 和 orchestrator 只使用 AuthInterceptor 注入的当前 userId。
- active-run 设置会校验 run 属于当前 userId + sessionId。

## 5. 验证结果

已执行：

    mvn -q -pl chatbot-service "-Dtest=RuleBasedCodeReviewIntentClassifierTest,CodeReviewConversationOrchestratorTest,CodeReviewAgentServiceTest,CodeReviewPersistenceServiceTest,AgentPendingActionServiceTest" test

结果：

    Exit code: 0

覆盖点：

- Git diff 审查意图识别。
- 普通聊天不误触发代码审查。
- 文件审查意图和路径提取。
- 对话编排调用 reviewGitDiff。
- activeReviewRunId 绑定。
- active-run 越权绑定拒绝。
- 既有代码审查 service / persistence / pending action 测试回归。

## 6. 当前限制

本阶段只完成阶段 1：

- 单文件 / workspace 对话触发尚未接入。
- issue 追问和解释尚未接入。
- patch preview 对话触发尚未接入。
- apply-request 对话触发尚未接入。
- active context 当前为内存级，应用重启后丢失；后续如需稳定多轮引用，可按计划新增 code_review_conversation_context 表。

