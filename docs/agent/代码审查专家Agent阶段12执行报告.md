# 代码审查专家 Agent 阶段 12 执行报告

## 1. 本阶段目标

本阶段目标是把阶段 11 生成的 apply-request 接回可见的 Pending Action 确认流，让用户能在代码审查弹窗内看到待确认操作并执行确认。

范围：

- 新增 Pending Action 列表查询能力。
- 返回前端安全 action card，不暴露 replacementContent 大字段。
- 代码审查弹窗展示当前会话的 APPLY_WORKSPACE_PATCH 待确认项。
- 前端支持确认执行 Pending Action。

不做：

- 不绕过 Pending Action 直接写 workspace 文件。
- 不展示完整 replacementContent。
- 不新增取消/撤销接口。
- 不做历史 action 全量审计页。

## 2. 后端改动

AgentPendingActionService 新增：

    listActions(Long userId, String sessionId, String actionType, String status, int limit)

行为：

- 按 userId 强制过滤。
- sessionId 存在时校验必须属于当前用户。
- 支持 actionType、status 和 limit 过滤。
- limit 最大 100。

AgentPendingActionService 新增：

    toActionCard(AgentPendingAction action)

返回安全字段：

- id
- sessionId
- actionType
- toolName
- status
- arguments
- expireTime
- createdTime
- confirmedTime
- resultSummary
- errorMessage

arguments 只保留：

- workspaceId
- relativePath
- expectedVersion
- reason
- documentId
- title

不返回 replacementContent，避免前端列表承载大字段或泄露完整文件内容。

AgentActionController 新增：

    GET /api/chat/agent/actions

示例：

    GET /api/chat/agent/actions?sessionId=7_xxx&actionType=APPLY_WORKSPACE_PATCH&status=PENDING&limit=20

## 3. 前端改动

chat.html 的代码审查弹窗新增 Pending Apply Actions 区域：

- 自动加载当前会话的 PENDING APPLY_WORKSPACE_PATCH。
- apply-request 创建成功后自动刷新列表。
- 每个 action 显示 actionId、文件路径、expectedVersion、原因。
- 提供“确认执行”按钮。

点击确认执行时调用：

    POST /api/chat/agent/actions/{actionId}/confirm

确认前仍有浏览器 confirm 二次确认。执行成功后刷新列表。

## 4. 安全边界

- 列表接口不信任前端 userId，只用 AuthInterceptor 注入的当前用户。
- sessionId 必须属于当前用户。
- action card 不返回 replacementContent。
- confirm 仍复用原有 AgentPendingActionService.confirm。
- 只有 confirm 成功后，workspace 文件才会更新。

## 5. 测试覆盖

新增测试：

- AgentPendingActionServiceTest.listActionsReturnsSafeCards

覆盖点：

- listActions 能返回当前用户 action。
- toActionCard 保留 relativePath、reason 等摘要字段。
- toActionCard 不返回 replacementContent。

## 6. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest,AgentPendingActionServiceTest" test

结果：

    Exit code: 0

本阶段复验命令 2：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

- 前端仍使用浏览器 confirm，不是正式 diff modal。
- 当前只展示 PENDING 的 APPLY_WORKSPACE_PATCH，不展示已确认/失败历史。
- 没有取消 Pending Action 的接口。
- 不展示 replacementContent 完整内容，用户如需复查完整内容仍应回到 preview 阶段确认。

## 8. 下一阶段建议

下一阶段建议做更完整的确认体验：

1. 用正式 modal 替代 window.confirm。
2. 在 modal 中展示 diffPreview、action 摘要和风险提示。
3. 增加 Pending Action 取消接口。
4. 增加已确认/失败 action 历史筛选。
