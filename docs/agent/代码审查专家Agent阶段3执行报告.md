# 代码审查专家 Agent 阶段 3 执行报告

## 1. 本阶段目标

阶段 3 目标是引入显式 Agent Runtime，让代码审查流程从“服务方法直接执行”升级为“可观测、可限制、可返回轨迹的工程化流程”。

本阶段不做自动修改、不做 patch、不做 Git 写操作。

## 2. 实现结果

已完成。

新增通用 runtime 包：

    chatbot-service/src/main/java/com/example/chatbot/agent/runtime/

新增类：

- AgentRuntime
- AgentRun
- AgentStep
- AgentRuntimeProperties
- AgentRunStatus
- AgentStepStatus
- AgentStepType

代码审查流程现在显式记录为：

    CLASSIFY_SCOPE
    -> COLLECT_CONTEXT
    -> ANALYZE
    -> VERIFY
    -> REPORT

单文件审查和多文件审查结果都会返回 steps。

## 3. 新增 Runtime 设计

### 3.1 AgentRun

表示一次 Agent 运行。

核心字段：

- runId
- objective
- domain
- status
- maxIterations
- currentIteration
- steps
- errorMessage
- startedTime
- finishedTime

### 3.2 AgentStep

表示一次运行中的一个阶段。

核心字段：

- stepId
- runId
- index
- type
- status
- summary
- metadata
- errorMessage
- startedTime
- finishedTime

### 3.3 AgentRuntime

当前提供：

- start(domain, objective)
- step(run, type, summary)
- step(run, type, summary, metadata)
- complete(run)
- fail(run, error)

并通过 maxIterations 限制 step 数量。

默认配置：

    app.agent.runtime.max-iterations = 6

当前代码审查流程使用 5 个 step，保留 1 个扩展余量。

## 4. 接入范围

### 4.1 单文件审查

接口：

    POST /api/chat/agent/review/file

返回结果新增：

    steps

典型 steps：

1. CLASSIFY_SCOPE：审查范围识别为单文件。
2. COLLECT_CONTEXT：读取 workspace 文件内容。
3. ANALYZE：模型或本地规则完成问题分析。
4. VERIFY：校验审查结果结构和风险级别。
5. REPORT：生成单文件审查报告。

### 4.2 多文件审查

接口：

    POST /api/chat/agent/review/workspace

返回结果新增：

    steps

典型 steps：

1. CLASSIFY_SCOPE：审查范围识别为多文件，记录 scopeType。
2. COLLECT_CONTEXT：列出 workspace 文件并选择待审查文件。
3. ANALYZE：逐文件审查并聚合分析。
4. VERIFY：聚合 issues 并校验整体风险。
5. REPORT：生成多文件审查报告。

## 5. 为什么这是工程化 Agent Loop 的第一步

阶段 1/2 的代码审查能力主要是“功能服务”：

    请求 -> 读文件 -> 分析 -> 返回

阶段 3 加入 runtime 后，变成：

    请求 -> start run
      -> CLASSIFY_SCOPE
      -> COLLECT_CONTEXT
      -> ANALYZE
      -> VERIFY
      -> REPORT
      -> complete run

区别：

- 每一步都有明确类型。
- 每一步有 summary 和 metadata。
- 可以限制最大步骤数。
- 可以返回 steps 给前端展示。
- 后续可以持久化到数据库。
- 后续可以接入失败恢复、评测和 trace replay。

这还不是最终形态的 autonomous loop，但已经从“完全靠 LLM 隐式判断”推进到“后端显式控制审查流程”。

## 6. 验证结果

### 6.1 单元测试

执行命令：

    mvn -q -pl chatbot-service -Dtest=CodeReviewAgentServiceTest test

结果：

    Exit code: 0

新增断言：

- 单文件审查返回 5 个 steps。
- 第一个 step 是 CLASSIFY_SCOPE。
- 最后一个 step 是 REPORT。
- 多文件审查返回 5 个 steps。
- 多文件审查第二个 step 是 COLLECT_CONTEXT。

### 6.2 模块编译

执行命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 7. 当前限制

本阶段 runtime 仍是内存对象，暂不持久化。

限制：

- AgentRun / AgentStep 未入库。
- 没有 SSE 实时发送 step。
- 没有恢复中断的 run。
- 没有 trace replay。
- 没有真正让 LLM 输出 AgentDecision。
- 多文件审查内部仍逐文件调用 reviewFile，因此会产生内部子 run，但当前只返回外层 workspace run 的 steps。

这些限制符合阶段 3 最小可落地范围。

## 8. 下一阶段建议

阶段 4 建议做 Git diff / PR 风格审查。

目标：

- 新增只读 GitReviewTools。
- 支持审查当前 git diff。
- 支持按 changed files 审查。
- 问题优先落在 changed lines。
- 输出 PR review 风格报告。

建议新增工具：

- getGitStatus
- getGitDiff
- getChangedFiles
- getFileDiff

安全边界：

- 阶段 4 只读 Git。
- 不 commit。
- 不 push。
- 不执行任意 shell。
- 不自动改文件。

阶段 5 再做 patch 建议和用户确认。
