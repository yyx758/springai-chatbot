# 代码审查专家 Agent 转型计划

## 1. 结论

当前 AI Studio 可以转型为“代码审查专家 Agent”，而且这个方向比泛用 AI 专家 Agent 更适合实际开发提效。

原因：

- 项目已有 Agent 工具治理框架：工具注册、ToolContext、风险分级、审计日志、Pending Action、SSE 推送。
- 项目已有工作区能力：Agent 可以创建、读取、更新、追加 workspace 文件，适合承载代码审查和修改建议。
- 项目已有上下文分层：RAG、会话摘要、相关历史、最近窗口、预算裁剪，适合做长期代码审查记忆。
- 项目已有 MCP 工具网关雏形：后续可以把代码审查能力暴露给外部 IDE、CLI 或其他 AI 客户端。
- 当前缺口明确：没有显式 Agent Loop、没有代码审查专用任务模型、没有 review 结果结构化、没有评测集、没有 patch/diff 级工作流。

建议目标不是做一个“能聊天的代码审查助手”，而是做一个受控的工程化 Review Agent：

> 用户提交代码、文件、PR diff 或工作区项目后，Agent 能自动读取上下文，按规则审查风险，生成结构化 review 结果，必要时给出 patch 建议，并把审查轨迹、证据和结论保存下来。

## 2. 当前基础评估

### 2.1 已经具备的能力

| 能力 | 当前状态 | 可复用程度 |
|---|---|---|
| Agent 入口 | 已有 AgentService.streamAgent | 高 |
| 工具调用 | Spring AI Function Calling + @Tool | 高 |
| 工具上下文 | userId/sessionId/emitter 注入 | 高 |
| 工具审计 | AgentToolAuditService | 高 |
| 高风险确认 | AgentPendingActionService | 中 |
| 工作区文件 | AgentWorkspaceService + WorkspaceTools | 高 |
| RAG 检索 | Hybrid RAG + PGVector/ES/MySQL fallback | 中 |
| MCP 暴露 | McpToolGateway 白名单工具 | 中 |
| 上下文分层 | ChatContextService 摘要 + 相关历史 + 最近窗口 | 中 |
| 安全测试 | Agent/MCP/Workspace 已有部分测试 | 中 |

### 2.2 主要短板

| 短板 | 影响 |
|---|---|
| 没有显式 Agent Loop | 无法严格控制审查步骤、最大迭代次数、失败恢复和中间轨迹 |
| 没有代码审查领域模型 | Agent 不能稳定区分 bug、性能、安全、可维护性、架构问题 |
| 没有 diff/patch 抽象 | 无法自然支持 PR 级审查和局部代码变更建议 |
| 没有 review issue 数据结构 | 审查结果只能是自然语言，不方便前端展示、排序、筛选和回归 |
| 没有代码审查评测集 | 无法判断 Agent 是否真的变强，容易退化成“看起来会说” |
| 没有基于证据的 verifier | Agent 可能凭空下结论，或者没有引用具体文件/代码片段 |

## 3. 目标定位

建议命名：

- 英文：CodeReviewAgent
- 中文：代码审查专家 Agent

第一阶段聚焦本项目实际开发提效：

1. 审查当前工作区代码。
2. 审查指定文件。
3. 审查一组变更文件。
4. 审查 Git diff。
5. 发现 bug、并发问题、安全问题、权限问题、降级缺失、测试缺失。
6. 给出结构化问题清单。
7. 给出可执行修改计划。
8. 可选生成 workspace patch，但不直接修改真实本地文件。

暂不建议第一版直接做：

- 自动提交代码。
- 自动 push / PR。
- 全仓库深度审查。
- 大规模自动重构。
- 无人确认的高风险修改。

## 4. 专家审查范围

代码审查专家 Agent 应重点审查：

| 类型 | 具体问题 |
|---|---|
| 正确性 | 空指针、边界条件、异常吞噬、事务缺失、状态不一致 |
| 安全 | 越权访问、路径穿越、SSRF、敏感信息泄露、危险操作缺少确认 |
| 可靠性 | Kafka ACK、重试、DLT、幂等、缓存一致性、降级链路 |
| 性能 | N+1 查询、无界扫描、超大上下文、无超时、同步阻塞 |
| 可维护性 | 职责混乱、重复逻辑、配置未生效、命名误导 |
| 测试 | 缺少单测、缺少失败路径、缺少权限测试、缺少回归用例 |
| 架构 | Gateway 绕过、服务边界不清、Agent 工具权限失控 |
| AI 工程 | RAG 注入顺序、上下文裁剪、工具循环、幻觉防护、Prompt Injection |

## 5. 推荐架构

总体结构：

    用户请求
      -> AgentController
      -> CodeReviewAgentService
      -> AgentRuntime
          -> CodeReviewPlanner
          -> CodeReviewContextBuilder
          -> CodeReviewToolExecutor
          -> CodeReviewAnalyzer
          -> CodeReviewVerifier
          -> CodeReviewReporter

建议新增包：

    chatbot-service/src/main/java/com/example/chatbot/agent/review/
    ├── CodeReviewAgentService.java
    ├── CodeReviewRequest.java
    ├── CodeReviewResult.java
    ├── CodeReviewIssue.java
    ├── CodeReviewIssueSeverity.java
    ├── CodeReviewIssueCategory.java
    ├── CodeReviewPlanner.java
    ├── CodeReviewContextBuilder.java
    ├── CodeReviewAnalyzer.java
    ├── CodeReviewVerifier.java
    ├── CodeReviewReporter.java
    ├── CodeReviewProperties.java
    └── diff/
        ├── CodeDiffParser.java
        ├── ChangedFile.java
        └── ChangedHunk.java

后续如果引入显式 Agent Loop，再新增：

    chatbot-service/src/main/java/com/example/chatbot/agent/runtime/
    ├── AgentRuntime.java
    ├── AgentRun.java
    ├── AgentStep.java
    ├── AgentDecision.java
    ├── AgentObservation.java
    ├── AgentRuntimeProperties.java
    └── AgentTraceService.java

## 6. 显式 Agent Loop 设计

代码审查场景不应完全依赖 LLM 自由决定每一步。建议采用“后端控制流程，LLM 负责分析”的方式。

Loop 状态：

    START
      -> CLASSIFY_SCOPE
      -> COLLECT_CONTEXT
      -> ANALYZE
      -> VERIFY
      -> REPORT
      -> DONE

每一步职责：

| Step | 后端职责 | LLM 职责 |
|---|---|---|
| CLASSIFY_SCOPE | 判断审查范围：文件、diff、工作区、模块 | 辅助理解用户意图 |
| COLLECT_CONTEXT | 调 workspace/git/RAG 工具读取真实材料 | 不允许凭空假设 |
| ANALYZE | 组织审查 prompt，要求输出结构化 issue | 找问题、给依据 |
| VERIFY | 校验 issue 是否有文件、行号、证据、严重级别 | 可辅助二次判断 |
| REPORT | 生成最终报告和修改计划 | 组织表达 |

关键原则：

- LLM 不负责决定是否读取文件；后端必须强制读取。
- LLM 不负责决定是否停止；后端 runtime 控制 stop condition。
- LLM 输出必须是结构化 JSON，再由后端校验。
- 没有证据的 issue 降级为 suggestion，不允许标成 bug。

## 7. Review Issue 数据结构

建议把审查结果结构化，不要只返回 Markdown。

CodeReviewIssue 字段建议：

- id
- severity
- category
- title
- description
- filePath
- startLine
- endLine
- evidence
- impact
- recommendation
- patchable
- suggestedPatch

严重级别：

- BLOCKER
- HIGH
- MEDIUM
- LOW
- INFO

分类：

- BUG
- SECURITY
- RELIABILITY
- PERFORMANCE
- MAINTAINABILITY
- TESTING
- ARCHITECTURE
- AI_ENGINEERING

## 8. 工具能力规划

### 8.1 第一阶段复用现有工具

当前可直接复用：

- listWorkspaceFiles
- readWorkspaceFile
- updateWorkspaceFile
- appendWorkspaceFile
- getCurrentChatHistory
- searchKnowledge

### 8.2 建议新增代码审查专用工具

建议新增 ReviewWorkspaceTools：

- listChangedFiles
- readFileWithLineNumbers
- searchWorkspaceCode
- getWorkspaceFileDigest
- createReviewReportFile
- createSuggestedPatchFile

优先新增这 3 个：

1. readFileWithLineNumbers(relativePath)
   代码审查必须能引用行号。

2. searchWorkspaceCode(query, extensions, limit)
   审查某个类或配置时，需要快速找调用点。

3. createReviewReportFile(title, content)
   把审查结果沉淀到 workspace，便于后续保存知识库。

### 8.3 后续 Git diff 工具

如果要审查真实本地 Git 变更，建议新增只读 Git 工具：

- getGitStatus
- getGitDiff
- getChangedFiles
- getFileDiff

注意：

- 第一版只读，不做 commit/push。
- 写操作仍走 workspace 或用户确认。
- 不要让 Agent 执行任意 shell 命令。

## 9. Prompt 设计

建议单独维护代码审查 prompt，不要混在通用 Agent prompt 里。

核心规则：

1. 你是 AI Studio 的代码审查专家 Agent。
2. 你的目标不是泛泛建议，而是发现真实、可定位、可修复的问题。
3. 必须基于已读取的真实代码、diff、配置或文档给出判断。
4. 没有证据的问题只能标为 INFO 或 suggestion，不能标为 HIGH/BLOCKER。
5. 每个问题必须包含 filePath、line、evidence、impact、recommendation。
6. 优先发现正确性、安全、可靠性、权限、事务、并发、降级和测试缺失问题。
7. 不要为了数量编造问题。
8. 不要重复报告同一根因。
9. 修改建议必须最小化，不做无关重构。
10. 涉及删除、覆盖、大规模重写时必须要求用户确认。

模型输出建议采用 JSON：

    {
      "summary": "本次审查结论",
      "riskLevel": "LOW|MEDIUM|HIGH",
      "issues": [
        {
          "severity": "HIGH",
          "category": "SECURITY",
          "title": "文件下载缺少用户隔离校验",
          "filePath": "file-service/src/main/java/...",
          "startLine": 120,
          "endLine": 135,
          "evidence": "代码片段或行为依据",
          "impact": "可能导致越权访问",
          "recommendation": "增加 uploaderId 校验"
        }
      ],
      "testsToAdd": [
        "新增越权下载测试"
      ],
      "patchPlan": [
        "修改 FileService.canAccess",
        "补充 FileServiceAccessTest"
      ]
    }

后端解析失败时，应要求模型重试一次；仍失败则返回保守错误。

## 10. 前端展示建议

当前聊天流式输出可以继续保留，但代码审查结果最好结构化展示。

建议前端展示：

    代码审查报告
    ├── 总体风险：MEDIUM
    ├── 问题数量：HIGH 1 / MEDIUM 3 / LOW 2
    ├── 问题卡片
    │   ├── 严重级别
    │   ├── 文件路径 + 行号
    │   ├── 问题描述
    │   ├── 影响
    │   ├── 建议修复
    │   └── 一键生成 patch
    └── 测试建议

如果暂时不做前端结构化页面，也可以先输出 Markdown 报告，同时保存 JSON 到数据库或 workspace。

## 11. 数据库规划

第一版可以不建表，直接复用聊天记录和工具审计。

如果要做专业化，建议新增：

- code_review_run
- code_review_issue

code_review_run 建议字段：

- id
- run_id
- user_id
- session_id
- scope_type
- status
- risk_level
- summary
- created_time
- updated_time

code_review_issue 建议字段：

- id
- run_id
- severity
- category
- title
- file_path
- start_line
- end_line
- evidence
- impact
- recommendation
- suggested_patch
- status
- created_time

## 12. 分阶段实施计划

### 阶段 0：收敛定位与文档

目标：

- 明确从泛用 Agent 转向代码审查专家 Agent。
- 定义能力边界、数据结构、工具边界和验收标准。

产出：

- 本计划文档。
- 后续可补 docs/agent/代码审查专家Agent设计说明.md。

### 阶段 1：只读代码审查 MVP

目标：

- 支持审查 workspace 中指定文件。
- 支持输出结构化 review issue。
- 不做自动修改。

改造点：

- 新增 CodeReviewAgentService。
- 新增 CodeReviewRequest。
- 新增 CodeReviewResult。
- 新增 CodeReviewIssue。
- 新增 CodeReviewAnalyzer。
- 新增 CodeReviewReporter。
- 新增 readFileWithLineNumbers 工具。

接口建议：

    POST /api/chat/agent/review/file

请求体：

    {
      "sessionId": "7_xxx",
      "relativePath": "src/main/java/xxx.java",
      "focus": "security,reliability"
    }

验收：

- 指定文件不存在时明确报错。
- 指定文件过大时截断并说明。
- 每个 issue 有文件路径和证据。
- 没有明显问题时能明确说“未发现高风险问题”。

### 阶段 2：工作区多文件审查

目标：

- 支持审查一组文件。
- 支持按模块/关键词检索相关文件。
- 支持跨文件问题，例如调用链、权限校验遗漏、配置不一致。

新增工具：

- searchWorkspaceCode
- getWorkspaceFileDigest

验收：

- 审查 controller 时能主动读取 service。
- 审查 service 时能关联 mapper/entity/config。
- 不会一次把所有文件塞进上下文。

### 阶段 3：显式 Agent Runtime

目标：

- 不再完全依赖 LLM 自由调用工具。
- 后端控制代码审查流程。

新增：

- AgentRuntime
- AgentRun
- AgentStep
- AgentTraceService

审查 loop：

    CLASSIFY_SCOPE
    -> COLLECT_CONTEXT
    -> ANALYZE
    -> VERIFY
    -> REPORT

验收：

- maxIterations 真正生效。
- 每一步可追踪。
- 工具失败能恢复或安全停止。
- SSE 能展示当前审查阶段。

### 阶段 4：Git diff / PR 风格审查

目标：

- 支持审查当前 Git diff。
- 支持只审查 changed files。
- 支持生成 PR review 风格报告。

新增工具：

- GitReviewTools.getGitStatus
- GitReviewTools.getGitDiff
- GitReviewTools.getChangedFiles
- GitReviewTools.getFileDiff

验收：

- 能区分新增代码和上下文代码。
- 问题优先落在 changed lines。
- 可以输出类似 GitHub Review 的行级建议。

### 阶段 5：Patch 建议与安全确认

目标：

- 对低风险问题生成 patch 建议。
- 对中高风险修改生成修改计划，等待用户确认。
- 不直接覆盖真实本地文件。

策略：

- 第一版 patch 写入 workspace 新文件，例如 review/suggested-fix.patch。
- 用户确认后再通过 workspace 工具更新文件。
- 大规模修改必须 Pending Action。

验收：

- 修改前必须已读取目标文件。
- patch 只包含相关文件。
- 不引入无关格式化。
- 自动补测试建议。

### 阶段 6：评测 Harness

目标：

- 建立代码审查 Agent 的回归测试能力。

新增测试集：

    chatbot-service/src/test/resources/agent-review-evals/
    ├── security.jsonl
    ├── reliability.jsonl
    ├── context.jsonl
    ├── workspace.jsonl
    └── false-positive.jsonl

评测维度：

- 是否发现目标问题。
- 是否引用正确文件。
- 是否避免编造。
- 是否正确区分严重级别。
- 是否遵守“修改前先读取文件”。
- 是否对高风险操作要求确认。

## 13. 验收标准

第一版上线标准：

- 能审查单个 workspace 文件。
- 能输出结构化问题列表。
- 每个问题必须包含证据。
- 不允许直接修改文件。
- 有基础单元测试。
- 有失败路径处理。

成熟版标准：

- 支持多文件和 diff 审查。
- 有显式 Agent Loop。
- 有 review run / issue 存储。
- 有行级问题展示。
- 有 patch 建议。
- 有评测集。
- 有 SSE 阶段进度。
- 能沉淀 review 报告到知识库。

## 14. 风险与取舍

### 14.1 最大风险：误报和幻觉

代码审查 Agent 最容易出现“看起来很专业但问题不真实”。

控制方式：

- 强制读取真实代码。
- issue 必须包含证据。
- 没有证据不得标 HIGH。
- verifier 校验文件路径和行号是否存在。
- 建立 false-positive 评测集。

### 14.2 第二风险：上下文过大

多文件审查容易超上下文。

控制方式：

- 先 digest，再按需读取。
- 只读取 changed files 和直接依赖。
- 工具 observation 压缩。
- context segment 按优先级裁剪。

### 14.3 第三风险：自动修改破坏代码

控制方式：

- 第一版只读。
- patch 写入 workspace，不直接落真实文件。
- 修改前必须读取文件。
- 大规模修改必须用户确认。
- 修改后要求测试建议或执行验证。

## 15. 推荐学习重点

为了做好这个方向，建议重点补这些：

1. Code Review 规则体系
   学会把问题分成 bug/security/reliability/performance/testing/architecture。

2. Agent Runtime
   学会显式 loop、step trace、tool observation、stop condition、retry/fallback。

3. Structured Output
   学会让模型输出 JSON Schema，并做解析失败重试和后端校验。

4. Diff/Patch 基础
   学会 unified diff、changed hunk、line mapping、patch apply 风险。

5. Eval Harness
   学会用固定样例评估 Agent，而不是只看一次回答效果。

6. Context Engineering
   学会多文件摘要、相关代码检索、工具观察压缩、token budget。

## 16. 建议下一步

优先做阶段 1：只读代码审查 MVP。

最小任务列表：

1. 新增 CodeReviewIssue、CodeReviewResult、CodeReviewRequest。
2. 新增 CodeReviewAgentService。
3. 新增 readFileWithLineNumbers 工具，或在 service 内复用 workspace 读取并加行号。
4. 新增 /api/chat/agent/review/file 接口。
5. 让模型基于单文件输出 JSON review result。
6. 后端解析并转成 Markdown/SSE 输出。
7. 补 3 类测试：
   - 正常审查。
   - 文件不存在。
   - 模型输出无效 JSON 时降级。

完成阶段 1 后，再做显式 Agent Runtime。不要一开始就做完整多 Agent，否则范围会失控。
