# 代码审查专家 Agent 阶段 7 执行报告

## 1. 本阶段目标

本阶段目标是实现“审查结果持久化 + issue 卡片后端接口”。

目的：

- 审查结果不只存在一次响应里。
- 前端可以拉取历史 review run。
- 前端可以按 runId 拉取 issue 卡片。
- 后续可以做 issue 状态流转、卡片化展示、一键生成 patch、绑定 apply action。

## 2. 实现结果

已完成。

新增表：

- code_review_run
- code_review_issue

新增查询接口：

    GET /api/chat/agent/review/runs?sessionId=7_xxx&limit=20

    GET /api/chat/agent/review/runs/{runId}/issues

审查接口现在会自动保存结果：

- /api/chat/agent/review/file
- /api/chat/agent/review/workspace
- /api/chat/agent/review/git-diff

## 3. 新增数据库迁移

新增 Flyway migration：

    chatbot-service/src/main/resources/db/migration/V11__add_code_review_tables.sql

code_review_run 字段：

- run_id
- user_id
- session_id
- scope_type
- target_path
- reviewed_file_count
- risk_level
- summary
- status
- created_time
- updated_time

code_review_issue 字段：

- run_id
- user_id
- session_id
- severity
- category
- title
- description
- file_path
- start_line
- end_line
- evidence
- impact
- recommendation
- patchable
- suggested_patch
- status
- created_time
- updated_time

## 4. 新增代码

新增实体：

- CodeReviewRunRecord.java
- CodeReviewIssueRecord.java

新增 Mapper：

- CodeReviewRunMapper.java
- CodeReviewIssueMapper.java

新增服务：

- CodeReviewPersistenceService.java

修改：

- CodeReviewAgentService.java
  - 审查完成后保存 file/workspace/git-diff 结果。
  - 持久化失败只 warn，不影响主审查结果返回。

- CodeReviewAgentController.java
  - 新增 runs 查询接口。
  - 新增 run issues 查询接口。

新增测试：

- CodeReviewPersistenceServiceTest.java

## 5. 查询接口说明

### 5.1 查询 review runs

请求：

    GET /api/chat/agent/review/runs?sessionId=7_xxx&limit=20

返回：

    {
      "success": true,
      "runs": [
        {
          "runId": "run_xxx",
          "sessionId": "7_xxx",
          "scopeType": "GIT_DIFF",
          "targetPath": "",
          "reviewedFileCount": 3,
          "riskLevel": "MEDIUM",
          "summary": "...",
          "status": "COMPLETED",
          "createdTime": "..."
        }
      ]
    }

### 5.2 查询 issue cards

请求：

    GET /api/chat/agent/review/runs/{runId}/issues

返回：

    {
      "success": true,
      "runId": "run_xxx",
      "issues": [
        {
          "id": 1,
          "runId": "run_xxx",
          "severity": "MEDIUM",
          "category": "RELIABILITY",
          "title": "新增代码直接打印异常堆栈",
          "filePath": "src/main/java/App.java",
          "startLine": 12,
          "endLine": 12,
          "evidence": "e.printStackTrace();",
          "impact": "...",
          "recommendation": "...",
          "patchable": true,
          "suggestedPatch": "...",
          "status": "OPEN",
          "createdTime": "..."
        }
      ]
    }

## 6. 安全和权限

- runs 查询按 userId 过滤。
- issue 查询按 userId + runId 过滤。
- 如果传 sessionId，会校验 sessionId 必须属于当前用户。
- 不暴露其他用户审查记录。

## 7. 验证结果

### 7.1 单元测试

执行命令：

    mvn -q -pl chatbot-service "-Dtest=CodeReviewAgentServiceTest,AgentPendingActionServiceTest,CodeReviewPersistenceServiceTest" test

结果：

    Exit code: 0

覆盖：

- file review 会调用持久化。
- workspace review 会调用持久化。
- persistence service 保存 run 和 issue。
- issue record 转换为 card-ready map。
- pending action 既有能力仍通过。

### 7.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 8. 当前限制

本阶段只做后端持久化和 card-ready 查询接口。

尚未做：

- 前端 issue 卡片 UI。
- issue 状态更新接口。
- issue 与 patch apply action 的绑定。
- run 详情页。
- review trace 持久化。

## 9. 下一阶段建议

下一阶段建议做前端 issue 卡片：

1. 在 chat.html 增加 review run 面板。
2. 展示 severity/category/filePath/line/evidence/recommendation。
3. patchable issue 显示“生成修复建议”按钮。
4. 后续绑定 patch/apply-request。
