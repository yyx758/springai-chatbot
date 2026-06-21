# 代码审查专家 Agent 阶段 6 执行报告

## 1. 本阶段目标

阶段 6 目标是实现“确认后应用 workspace patch”的安全闭环。

本阶段仍然不操作真实 Git 文件：

- 不 commit。
- 不 push。
- 不执行 git apply。
- 不直接修改真实本地仓库。

只允许在用户确认后更新 Agent workspace 文件。

## 2. 实现结果

已完成。

新增接口：

    POST /api/chat/agent/review/patch/apply-request

请求示例：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/com/example/App.java",
      "replacementContent": "完整替换后的文件内容",
      "expectedVersion": 2,
      "reason": "根据代码审查修复 printStackTrace"
    }

返回示例：

    {
      "success": true,
      "actionId": 123,
      "status": "PENDING",
      "actionType": "APPLY_WORKSPACE_PATCH",
      "message": "Patch application requires confirmation...",
      "expireTime": "..."
    }

确认接口复用现有 Pending Action：

    POST /api/chat/agent/actions/{actionId}/confirm

确认后才会调用 AgentWorkspaceService.updateFile 更新 workspace 文件。

## 3. 新增代码

新增 DTO：

- CodeReviewApplyPatchRequest.java
- CodeReviewApplyPatchResult.java

修改：

- CodeReviewAgentService.java
  - 新增 requestApplyWorkspacePatch。
  - 创建 APPLY_WORKSPACE_PATCH pending action。

- CodeReviewAgentController.java
  - 新增 /patch/apply-request 接口。

- AgentPendingActionService.java
  - 新增 ACTION_APPLY_WORKSPACE_PATCH。
  - 新增 requestApplyWorkspacePatch。
  - confirm 中支持 APPLY_WORKSPACE_PATCH。

- AgentPendingActionServiceTest.java
  - 补充 request apply pending action 测试。
  - 补充 confirm apply 更新 workspace 文件测试。

- CodeReviewAgentServiceTest.java
  - 补充 apply request 只创建 pending action、不直接更新 workspace 的测试。

## 4. 行为说明

### 4.1 申请应用 patch

调用：

    POST /api/chat/agent/review/patch/apply-request

服务会：

1. 校验 userId/sessionId/relativePath/replacementContent。
2. 校验目标 workspace 文件存在且属于当前用户。
3. 创建 agent_pending_action 记录。
4. status = PENDING。
5. 不更新源文件。

### 4.2 用户确认

调用：

    POST /api/chat/agent/actions/{actionId}/confirm

服务会：

1. 校验 action 属于当前用户。
2. 校验 status 是 PENDING。
3. 校验未过期。
4. 解析 replacementContent、relativePath、expectedVersion。
5. 调用 AgentWorkspaceService.updateFile。
6. 更新 action status = CONFIRMED。

### 4.3 版本冲突保护

请求支持 expectedVersion。

如果前端传 expectedVersion，最终更新 workspace 文件时会走 AgentWorkspaceService.updateFile 的版本冲突校验。

## 5. 安全边界

本阶段只更新 workspace 文件，不更新真实 Git 工作区。

不会执行：

- git apply
- git commit
- git push
- shell patch
- 直接覆盖服务器本地文件

所有应用操作必须经过 pending action 二次确认。

## 6. 验证结果

### 6.1 单元测试

执行命令：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewAgentServiceTest,AgentPendingActionServiceTest" test

结果：

    Exit code: 0

覆盖场景：

- apply request 只创建 pending action。
- apply request 不直接调用 workspace update。
- confirm 后才调用 AgentWorkspaceService.updateFile。
- expectedVersion 能传入 WorkspaceFileUpdateRequest。
- 既有删除知识库 pending action 仍通过。

### 6.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

当前应用方式是“完整文件替换”，不是 unified diff apply。

原因：

- 完整文件替换更容易做权限和版本控制。
- 避免 diff hunk 行号漂移导致误改。
- 与现有 AgentWorkspaceService.updateFile 能力一致。

后续可以再做更细粒度 patch：

- strict unified diff parser
- patch dry-run
- patch conflict 检测
- patch preview

## 8. 下一阶段建议

下一阶段建议做“审查结果持久化与前端卡片化”：

1. 新增 code_review_run 表。
2. 新增 code_review_issue 表。
3. 前端展示 issue 卡片。
4. 每个 issue 支持一键生成 patch suggestion。
5. patch apply request 绑定 issueId。
6. 后续接入测试执行结果。
