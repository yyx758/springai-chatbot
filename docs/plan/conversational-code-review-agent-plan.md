# 对话式代码审查 Agent 融合改造计划

## 1. 背景与目标

AI Studio 原本是一个通用 AI 应用平台，核心能力包括聊天、上下文管理、文件解析、Hybrid RAG、多模型调用和基础工程化支撑。当前项目已经逐步转向代码审查专家 Agent，并完成了单文件、多文件、Workspace、Git Diff 审查、Review Run / Issue 持久化、Issue Card、修复草案、Diff Preview 和 Pending Action 二次确认等能力。

目前代码审查能力主要以工具面板和表单方式展开，工程边界清晰，但与原 AI Studio 的对话能力存在割裂。用户需要先进入代码审查区域，选择审查范围，再查看 Issue Cards 和 Pending Apply Actions。这个形态适合管理结构化结果，但不够自然，也弱化了 AI Studio 作为智能协作入口的定位。

本计划目标：将代码审查能力融入对话入口，让用户可以通过自然语言触发审查、追问 issue、生成修复草案，同时保留现有 Review Run / Issue 持久化、Issue Cards、Diff Preview 和 Pending Action 安全确认机制。

最终形态不是纯聊天式代码助手，也不是纯表单式代码审核工具，而是：

    对话负责表达意图、追问解释、协作修复
    结构化 Review 系统负责执行审查、保存结果、管理状态
    Pending Action 负责控制真正写文件的安全边界

## 2. 核心判断

### 2.1 为什么要融入对话

代码审查天然存在大量自然语言交互：

- 帮我审查这次 Git diff。
- 只看 Controller 和 Service，不看前端。
- 重点检查权限和空指针。
- 解释第 2 个问题为什么是高危。
- 这个 issue 是不是误报？
- 基于刚才的 review 生成修复草案。
- 这个 patch 会不会影响登录逻辑？

这些需求如果全部通过按钮和表单表达，会导致交互复杂、上下文割裂，也不利于体现 AI Studio 的智能协作价值。

### 2.2 为什么不能完全改成纯聊天

纯聊天式代码审查存在明显风险：

- 模型输出只是自然语言，无法稳定沉淀为业务对象。
- 无法方便地筛选、跟踪、接受、忽略或标记 issue。
- 修复建议容易绕过安全流程。
- 用户难以审计哪个 run 发现了哪些问题、哪些被处理了。
- 项目会退化成普通 ChatGPT 代码助手，工程化价值下降。

因此必须保留现有结构化审查系统：

- code_review_run
- code_review_issue
- Issue 状态流转
- Patch Preview
- Pending Action
- 二次确认落盘

### 2.3 推荐定位

推荐定位为：对话式入口 + 工程化审查流程 + 结构化结果管理 + 受控修复闭环。

    Chat 是入口和解释层
    Agent Runtime 是任务执行层
    Review Run / Issue 是结果管理层
    Pending Action 是安全执行层

## 3. 目标用户流程

### 3.1 对话触发 Git Diff 审查

用户输入：

    帮我审查这次 Git diff，重点看权限和空指针问题。

系统行为：

1. 识别用户意图为 CODE_REVIEW_GIT_DIFF。
2. 提取审查偏好：重点检查权限、空指针。
3. 调用 Git diff 审查能力。
4. 创建 Review Run。
5. 持久化 Review Issues。
6. 在聊天窗口返回摘要。
7. 在代码审查面板展示 Issue Cards。

聊天返回示例：

    已完成 Git diff 审查，生成 Review Run #23。
    发现 4 个问题：高危 1 个，中危 2 个，低危 1 个。
    你可以在右侧 Issue Cards 查看详情，也可以继续问：解释第 1 个问题、帮我修复第 2 个问题、只列出权限相关问题。

### 3.2 对话追问 Issue

用户输入：

    解释第 1 个问题，为什么是高危？

系统行为：

1. 从当前 session 中找到 active review run。
2. 将第 1 个问题解析为对应 issue。
3. 加载 issue 详情、相关文件片段、原始审查上下文。
4. 调用 LLM 生成解释，或者由后端规则和 issue 内容组合生成。
5. 在聊天窗口返回解释。

### 3.3 对话生成修复草案

用户输入：

    帮我给第 2 个问题生成修复草案。

系统行为：

1. 解析目标 issue。
2. 加载原文件内容、issue 描述、用户补充说明。
3. 调用现有 patch preview 能力。
4. 生成 full-file replacementContent。
5. 计算 diff preview 和 warnings。
6. 在聊天窗口返回摘要。
7. 在前端 diff preview 区展示差异。

注意：

- 对话可以触发 patch preview。
- 对话可以创建 apply-request。
- 对话不能直接绕过 Pending Action confirm 写文件。

### 3.4 对话创建 Pending Action

用户输入：

    这个修复草案可以，创建待确认操作。

系统行为：

1. 基于当前 patch preview 创建 Pending Action。
2. 不直接写 workspace 文件。
3. 在 Pending Apply Actions 中展示待确认项。
4. 用户必须二次确认后才真正落盘。

聊天返回示例：

    已创建 Pending Action #18。
    该操作尚未写入文件。请在 Pending Apply Actions 中进行二次确认。

## 4. 功能边界

### 4.1 对话允许做的事

对话层可以执行：

- 触发单文件审查。
- 触发多文件审查。
- 触发 Workspace 审查。
- 触发 Git Diff 审查。
- 查询当前 Review Run 摘要。
- 查询 issue 列表。
- 解释某个 issue。
- 判断 issue 是否可能误报。
- 根据 issue 生成 patch preview。
- 基于 patch preview 创建 apply-request。
- 查询 Pending Action 列表。
- 引导用户去确认或取消 Pending Action。

### 4.2 对话禁止做的事

对话层禁止执行：

- 直接写 workspace 文件。
- 直接 confirm Pending Action。
- 直接修改真实 Git 工作区。
- 自动 commit。
- 自动 push。
- 执行任意 shell。
- 暴露 Pending Action 中的完整 replacementContent 到列表 card。
- 信任前端传入的 userId。

## 5. 架构设计

### 5.1 总体架构

    用户自然语言
      -> ChatController / ChatService
      -> CodeReviewIntentClassifier
      -> CodeReviewConversationOrchestrator
      -> CodeReviewAgentService / GitReviewService / PatchPreviewService
      -> Review Run / Review Issue / Pending Action
      -> 聊天摘要 + Issue Cards + Diff Preview + Pending Actions

### 5.2 分层职责

#### Chat 层

职责：

- 接收用户自然语言。
- 维护 session。
- 返回聊天消息。
- 不直接承担代码审查业务细节。

#### Intent Classifier 层

职责：

- 判断用户是否在表达代码审查相关意图。
- 提取审查目标、范围、过滤条件、关注点。
- 将自然语言转成内部命令对象。

建议命令类型：

- REVIEW_FILE
- REVIEW_WORKSPACE
- REVIEW_GIT_DIFF
- LIST_REVIEW_ISSUES
- EXPLAIN_REVIEW_ISSUE
- GENERATE_PATCH_PREVIEW
- CREATE_PATCH_APPLY_REQUEST
- LIST_PENDING_ACTIONS
- GENERAL_CHAT
- UNKNOWN

#### Conversation Orchestrator 层

职责：

- 将代码审查意图路由到已有 service。
- 维护当前会话中的 active review run。
- 解析第 1 个问题、刚才那个 patch 等上下文引用。
- 组装聊天回复。
- 保证危险操作不被对话绕过。

#### Review Runtime 层

职责：保持当前已有审查流程。

    CLASSIFY_SCOPE
    -> COLLECT_CONTEXT
    -> ANALYZE
    -> VERIFY
    -> REPORT

Review Runtime 不关心用户是从按钮触发还是从聊天触发，只接受明确的审查请求对象。

#### Persistence 层

职责：

- 保存 Review Run。
- 保存 Review Issue。
- 保存 Pending Action。
- 严格执行 userId / sessionId 隔离。

## 6. 后端改造计划

### 6.1 新增代码审查意图模型

建议新增包：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/conversation/

建议新增类：

- CodeReviewIntentType.java
- CodeReviewIntent.java
- CodeReviewIntentClassifier.java
- RuleBasedCodeReviewIntentClassifier.java
- CodeReviewConversationOrchestrator.java
- CodeReviewConversationContext.java
- CodeReviewConversationResponse.java

CodeReviewIntent 建议字段：

- type
- sessionId
- targetPath
- targetPaths
- issueRef
- runId
- focusAreas
- statusFilter
- userInstruction
- confidence

### 6.2 第一阶段使用规则识别，不直接依赖 LLM 分类

第一阶段不建议把意图识别完全交给 LLM。原因：

- 容易不稳定。
- 难测试。
- 容易误触发危险流程。
- 当前命令类型有限，规则足够覆盖常见场景。

建议规则：

- 包含 review / 审查 / 检查 / 看看，同时包含 git diff / diff / 这次改动，识别为 REVIEW_GIT_DIFF。
- 包含 review / 审查 / 检查，同时包含 workspace / 项目 / 全部，识别为 REVIEW_WORKSPACE。
- 包含解释 / 为什么 / 详细说说，同时包含第 N 个问题 / issue，识别为 EXPLAIN_REVIEW_ISSUE。
- 包含修复 / 生成 patch / 修复草案，识别为 GENERATE_PATCH_PREVIEW。
- 包含创建待确认 / apply request / 待确认操作，识别为 CREATE_PATCH_APPLY_REQUEST。

后续可升级为：规则优先，低置信度时调用 LLM 分类，高风险意图必须二次确认。

### 6.3 会话上下文绑定 activeReviewRunId

需要在 session 维度记录当前活跃 Review Run。

可选方案：

1. 放在现有 chat session metadata 中。
2. 新增轻量表保存 conversation state。
3. 暂时由前端携带 activeReviewRunId，后端仍做 userId 校验。

推荐优先方案：后端维护 activeReviewRunId，前端显示和切换，所有 run / issue 查询必须校验 userId。

建议上下文字段：

- sessionId
- userId
- activeReviewRunId
- activeIssueId
- activePatchPreviewId 或临时 preview token
- updatedAt

### 6.4 复用现有审查接口逻辑

不要复制 controller 逻辑。应将已有审查能力沉到 service 方法中：

- reviewFile(...)
- reviewWorkspace(...)
- reviewGitDiff(...)
- listIssues(...)
- generatePatchPreview(...)
- createApplyRequest(...)

表单入口和聊天入口都调用同一批 service，避免两套逻辑分叉。

### 6.5 Issue 引用解析

用户常用表达：

- 第 1 个问题
- 第一个 issue
- 刚才那个高危问题
- 权限那个问题
- issue #123

第一阶段支持：

- issue #数字
- 第 N 个问题
- 第 N 个 issue

解析规则：

1. 如果用户提供明确 issueId，按 issueId 查。
2. 如果用户说第 N 个问题，从 activeReviewRun 的 issue 排序列表中取第 N 个。
3. 如果没有 activeReviewRun，提示用户先选择或执行一次 review。

排序建议：

- severity DESC
- status OPEN first
- createdAt ASC
- id ASC

### 6.6 Patch Preview 上下文管理

对话触发 patch preview 后，需要能在下一轮识别：

- 这个修复可以。
- 创建待确认操作。
- 取消这个 patch。
- 这个改动影响哪些逻辑？

建议保存轻量 preview context：

- sessionId
- userId
- issueId
- filePath
- previewSummary
- warnings
- replacementContent 存储策略待定
- createdAt
- expiresAt

安全建议：

- 不在普通聊天消息中完整展示 replacementContent。
- 前端 diff preview 可以展示差异。
- 如果需要服务端暂存 replacementContent，必须绑定 userId、sessionId、issueId，并设置过期时间。
- apply-request 创建时再次校验 issue、文件路径和用户权限。

## 7. 前端改造计划

### 7.1 Chat 中支持代码审查结果卡片

聊天消息中增加轻量 Review Summary Card：

- runId
- scope
- issue count
- high / medium / low count
- open issue count
- 查看 Issue Cards 按钮
- 解释第 1 个问题快捷入口
- 生成修复草案快捷入口

该卡片不是替代 Issue Cards，而是作为聊天中的入口。

### 7.2 代码审查面板保留

保留现有能力：

- Start Review 表单。
- Workspace 文件选择器。
- Git changed files 选择器。
- Git diff preview。
- Review Runs。
- Issue Cards。
- Pending Apply Actions。

对话入口只是新增触发方式，不删除现有工具入口。

### 7.3 Chat 与 Review 面板联动

需要支持：

- 聊天触发 review 后，Review Runs 自动刷新。
- activeReviewRunId 自动切换到最新 run。
- Issue Cards 展示当前 run。
- 用户点击 Issue Card 后，聊天上下文可以知道 activeIssueId。
- 用户在聊天中说解释这个问题，能关联当前选中 issue。

### 7.4 Patch Preview 展示

对话生成 patch preview 后：

- 聊天中展示摘要和 warnings。
- diff preview 面板展示具体差异。
- 提供创建 Pending Action 按钮。
- 不在 Pending Action 列表 card 中暴露完整 replacementContent。

## 8. API 设计建议

### 8.1 对话入口扩展

推荐优先复用现有聊天接口，在 ChatService 内部识别代码审查意图，避免产生两个聊天入口。内部需要清晰拆分普通聊天和代码审查意图。

如果需要专用接口，可新增：

    POST /api/chat/agent/conversation

请求示例：

    sessionId: xxx
    message: 帮我审查这次 Git diff，重点看权限问题

响应建议字段：

- type
- message
- runId
- issueCount
- severityCounts
- actions

### 8.2 Conversation Context 查询

可选接口：

    GET /api/chat/agent/conversation/context?sessionId={sessionId}

返回字段：

- sessionId
- activeReviewRunId
- activeIssueId
- hasActivePatchPreview

### 8.3 设置 Active Review Run

当用户在前端切换 Review Run 时，需要同步到对话上下文：

    POST /api/chat/agent/conversation/context/active-run

后端必须校验：

- run 属于当前 userId。
- run 属于当前 sessionId 或允许被当前 session 绑定。

## 9. 数据模型建议

### 9.1 是否新增表

第一阶段可以不新增表，尽量使用已有 session 和 review run。

如果需要稳定支持多轮引用，建议新增 code_review_conversation_context。

建议字段：

- id
- user_id
- session_id
- active_review_run_id
- active_issue_id
- active_patch_preview_ref
- created_at
- updated_at

约束：

- unique(user_id, session_id)

### 9.2 Patch Preview 是否持久化

第一阶段不建议将 patch preview 长期持久化为正式业务数据，避免 replacementContent 泄露和历史膨胀。

推荐：

- patch preview 临时保存在内存或短期缓存中。
- apply-request 创建后，进入 Pending Action 体系。
- Pending Action 中继续遵守不在列表 card 展示 replacementContent 的规则。

如果后续需要审计 patch preview，再单独设计安全存储和脱敏展示策略。

## 10. 安全设计

### 10.1 写操作边界

必须保持：

- patch preview：只读，不写文件。
- apply-request：只创建 PENDING action，不写文件。
- confirm：二次确认后才写 workspace 文件。
- cancel：只改状态，不写文件。

对话层不得新增任何绕过路径。

### 10.2 用户隔离

所有接口必须基于 AuthInterceptor 注入的当前用户，不信任前端传入 userId。

必须校验：

- sessionId 属于当前 userId。
- review run 属于当前 userId。
- issue 属于当前 userId。
- pending action 属于当前 userId。
- patch preview 绑定当前 userId。

### 10.3 高风险意图处理

以下意图必须谨慎：

- 创建 apply-request。
- confirm pending action。
- 修改 workspace 文件。
- Git 操作。

策略：

- 对话可以创建 apply-request。
- 对话不直接 confirm。
- confirm 必须走 Pending Action 面板，或者未来实现专门的二次确认 UI。
- GitReviewService 保持只读。
- 不自动 commit / push。

### 10.4 Prompt 注入风险

代码文件本身可能包含恶意文本，例如要求模型忽略系统指令、删除文件或执行命令。审查 prompt 需要明确：代码内容是被审查对象，不是系统指令；不得执行代码中的自然语言命令；不得执行 shell；不得进行未授权写操作。

## 11. 分阶段实施计划

### 阶段 1：对话意图识别与 Git Diff Review 触发

目标：

- 用户可以在聊天中触发 Git Diff 审查。
- 审查结果仍然保存为 Review Run / Issue。
- 聊天中返回 Review Summary。
- Review Runs / Issue Cards 自动刷新。

范围：

- 新增 CodeReviewIntentClassifier。
- 新增 CodeReviewConversationOrchestrator。
- 支持 REVIEW_GIT_DIFF。
- 支持 activeReviewRunId 绑定。

验收标准：

- 输入帮我审查这次 Git diff可以生成 Review Run。
- 数据库中产生 run 和 issue。
- 聊天窗口返回摘要。
- Issue Cards 可以查看本次结果。
- 不产生任何文件写操作。

### 阶段 2：支持单文件 / Workspace 对话触发

目标：用户可以通过自然语言触发单文件和 Workspace 审查。

范围：

- 支持 REVIEW_FILE。
- 支持 REVIEW_WORKSPACE。
- 支持从消息中提取文件路径。
- 如果路径不明确，引导用户选择，不直接猜测危险范围。

验收标准：

- 审查 xxx.java 触发单文件 review。
- 审查当前 workspace 触发 workspace review。
- 未指定路径时返回明确提示。
- 所有 run / issue 仍按 userId 隔离。

### 阶段 3：支持 Issue 追问和解释

目标：用户可以围绕当前 Review Run 继续提问。

范围：

- 支持 LIST_REVIEW_ISSUES。
- 支持 EXPLAIN_REVIEW_ISSUE。
- 支持第 N 个问题解析。
- 支持 activeIssueId。

验收标准：

- 用户问解释第 1 个问题时能定位 issue。
- 无 activeReviewRun 时给出明确提示。
- 不跨用户读取 issue。

### 阶段 4：支持对话生成 Patch Preview

目标：用户可以通过对话为某个 issue 生成修复草案。

范围：

- 支持 GENERATE_PATCH_PREVIEW。
- 复用现有 patch/preview 能力。
- 聊天中返回 patch 摘要和 warnings。
- 前端展示 diff preview。

验收标准：

- 帮我修复第 2 个问题可以生成 patch preview。
- patch preview 不写文件。
- replacementContent 不暴露在普通列表 card。
- warnings 正常展示。

### 阶段 5：支持对话创建 Apply Request

目标：用户可以基于当前 patch preview 创建 Pending Action。

范围：

- 支持 CREATE_PATCH_APPLY_REQUEST。
- 复用现有 patch/apply-request。
- 创建 Pending Action。
- 不直接 confirm。

验收标准：

- 创建待确认操作后生成 PENDING action。
- 文件未被修改。
- Pending Apply Actions 中可见该操作。
- confirm 仍需二次确认。

### 阶段 6：完善 UI 联动和审计体验

目标：对话、Issue Cards、Diff Preview、Pending Actions 形成完整工作流。

范围：

- Review Summary Card。
- active run UI 状态。
- active issue UI 状态。
- Pending Action 历史筛选。
- Diff Modal 替换浏览器 confirm。

验收标准：

- 用户能从聊天触发 review。
- 能在 Issue Cards 查看详情。
- 能回到聊天追问。
- 能生成 patch preview。
- 能创建 Pending Action。
- 能通过二次确认落盘。

## 12. 测试计划

### 12.1 单元测试

新增测试：

- CodeReviewIntentClassifierTest
- CodeReviewConversationOrchestratorTest
- CodeReviewConversationContextServiceTest

重点覆盖：

- Git diff 审查意图识别。
- 单文件审查意图识别。
- Workspace 审查意图识别。
- Issue 引用解析。
- 无 activeReviewRun 的异常路径。
- 非当前用户 issue 访问拒绝。
- apply-request 不写文件。

### 12.2 集成测试

覆盖流程：

1. 聊天触发 Git diff review。
2. 创建 Review Run。
3. 创建 Issues。
4. 设置 activeReviewRunId。
5. 解释 issue。
6. 生成 patch preview。
7. 创建 Pending Action。
8. confirm 前文件不变。

### 12.3 安全测试

重点测试：

- 前端传入伪造 userId 无效。
- sessionId 越权访问失败。
- issueId 越权访问失败。
- runId 越权绑定失败。
- 对话不能直接 confirm pending action。
- patch preview 不写文件。
- GitReviewService 不执行写 Git 操作。

### 12.4 回归测试命令

代码审查相关测试：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest,AgentPendingActionServiceTest" test

服务打包：

    mvn -q -pl chatbot-service -DskipTests package

硬编码端口检查：

    rg "8080|8081" chatbot-service/src/main/resources gateway/src/main/resources file-service/src/main/resources

## 13. 面试表达建议

这个改造完成后，项目可以这样表达：

项目最初是一个通用 AI Studio，包含聊天、上下文管理、文件解析和 Hybrid RAG。后续我将其聚焦到代码审查 Agent 场景，并进一步把代码审查能力融入对话入口。用户可以通过自然语言触发 Git Diff、Workspace 或单文件审查，系统会通过显式 Agent Runtime 构建代码审查任务上下文，调用 LLM 生成结构化 Review Issue，并持久化为 Review Run。用户可以继续在对话中围绕 issue 追问、解释和生成修复草案，但真正写文件必须经过 Pending Action 二次确认，保证 AI 能力与危险写操作隔离。

重点突出：

- 不是普通聊天机器人。
- 不是简单 RAG 问答。
- 是任务型 Agent。
- 是工程上下文构建。
- 是结构化结果沉淀。
- 是安全可控的修复闭环。

## 14. 风险与取舍

### 14.1 风险：意图识别误判

缓解：

- 第一阶段使用规则识别。
- 低置信度时提示用户确认。
- 高风险动作必须显式确认。

### 14.2 风险：聊天入口绕过安全边界

缓解：

- 对话只允许 preview 和 apply-request。
- confirm 仍走 Pending Action 二次确认。
- 所有写操作集中在 AgentPendingActionService。

### 14.3 风险：上下文引用混乱

缓解：

- session 绑定 activeReviewRunId。
- 前端展示当前 active run。
- 用户切换 run 时同步上下文。
- 第 N 个问题基于当前 active run 解析。

### 14.4 风险：功能复杂度上升

缓解：

- 保留现有表单入口。
- 分阶段接入对话能力。
- 每个阶段都有明确验收标准。
- 不一次性重构全部前端。

## 15. 最终完成标准

完成后应满足：

- 用户可以通过聊天触发代码审查。
- 审查仍然生成 Review Run / Issue。
- 用户可以通过聊天追问 issue。
- 用户可以通过聊天生成 patch preview。
- 用户可以通过聊天创建 Pending Action。
- 真正写文件仍必须二次确认。
- Review 面板仍可独立使用。
- 所有接口保持 userId 隔离。
- GitReviewService 保持只读。
- 不自动 commit / push。

最终项目定位：

> 一个面向代码审查场景的对话式工程 Agent 平台：自然语言负责协作入口，Agent Runtime 负责审查执行，结构化 Issue 系统负责结果管理，Pending Action 负责安全落盘。
