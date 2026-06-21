# 代码审查专家 Agent 阶段 9 执行报告

## 1. 本阶段目标

本阶段目标是补齐阶段 8 后遗留的 issue 状态闭环，并对代码审查 Agent 当前能力做阶段性总结。

范围：

- 后端支持单个 review issue 状态更新。
- 前端 issue card 支持接受、忽略、已修复三类状态操作。
- 状态更新继续复用当前登录用户身份做权限隔离。
- 补充测试与模块打包验证记录。

不做：

- 不直接应用 issue 的 suggestedPatch。
- 不把 suggestedPatch 自动转换为 full-file replacementContent。
- 不新增 run 详情页、筛选器或批量状态操作。
- 不调整 RAG、上下文、Kafka 等非代码审查 Agent 链路。

## 2. 实现结果

已完成。

新增/补齐接口：

    POST /api/chat/agent/review/issues/{issueId}/status

请求体：

    {
      "status": "FIXED"
    }

允许状态：

- OPEN
- ACCEPTED
- IGNORED
- FIXED

返回：

    {
      "success": true,
      "issue": {
        "id": 12,
        "runId": "run_xxx",
        "status": "FIXED"
      }
    }

## 3. 后端行为

CodeReviewPersistenceService.updateIssueStatus(...) 负责状态更新：

1. 规范化状态值为大写。
2. 校验状态必须在白名单内。
3. 使用 userId + issueId 查询 issue，避免跨用户更新。
4. 找不到记录时返回 404。
5. 状态非法或为空时返回 400。
6. 更新 status 和 updatedTime。

CodeReviewAgentController 新增 issue 状态更新入口：

    POST /api/chat/agent/review/issues/{issueId}/status

控制器仍从 AuthInterceptor.AUTH_USER_ID_ATTR 解析当前用户，不信任前端传入 userId。

## 4. 前端行为

chat.html 的代码审查弹窗在 issue card 底部增加状态按钮：

- 接受：ACCEPTED
- 忽略：IGNORED
- 已修复：FIXED

点击后调用：

    POST /api/chat/agent/review/issues/{issueId}/status

成功后重新加载当前 run 的 issue 列表，让卡片上的状态与后端记录保持一致。

## 5. 当前完整能力总结

代码审查 Agent 当前已经具备：

1. 单文件审查：POST /api/chat/agent/review/file
2. 工作区多文件审查：POST /api/chat/agent/review/workspace
3. Git diff 审查：POST /api/chat/agent/review/git-diff
4. patch 建议文件生成：POST /api/chat/agent/review/patch-suggestion
5. patch 应用申请：POST /api/chat/agent/review/patch/apply-request
6. 审查 run 持久化：code_review_run
7. 审查 issue 持久化：code_review_issue
8. review run 列表：GET /api/chat/agent/review/runs
9. issue card 查询：GET /api/chat/agent/review/runs/{runId}/issues
10. issue 状态流转：POST /api/chat/agent/review/issues/{issueId}/status

## 6. 安全边界

- 所有审查接口都使用当前登录用户身份。
- sessionId 必须属于当前用户，避免读取或列出其他用户会话数据。
- run 查询按 userId 过滤。
- issue 查询按 userId + runId 过滤。
- issue 状态更新按 userId + issueId 过滤。
- patch 建议当前只复制或写入建议文件，不自动修改源文件。
- 真正应用 patch 仍通过 Pending Action 进入确认流。

## 7. 验证结果

本阶段复验命令 1：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewPersistenceServiceTest,CodeReviewAgentServiceTest" test

结果：

    Exit code: 0

覆盖：

- CodeReviewPersistenceService 保存 run 和 issue。
- issue card map 转换。
- issue 状态更新白名单校验与持久化。
- CodeReviewAgentService 单文件审查、工作区审查、patch 建议、apply pending action 等核心路径。

本阶段复验命令 2：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 8. 当前限制与后续建议

仍未完成：

- 没有 issue 状态筛选和批量操作。
- 没有 run 详情页。
- suggestedPatch 仍是建议文本，不是可直接应用的完整文件替换内容。
- 前端还没有从 issue card 一键生成 replacementContent 并发起 apply-request。
- review trace 目前只随响应返回，未单独持久化为可回放轨迹。

下一步建议：

1. 增加 issue 状态筛选，方便只看 OPEN 或 ACCEPTED。
2. 将 patch suggestion 与 issueId 绑定。
3. 增加 replacementContent 预览和差异确认。
4. 用户确认后再调用 /patch/apply-request 创建 Pending Action。
5. 持久化 Agent steps，支持回放一次审查的执行轨迹。
