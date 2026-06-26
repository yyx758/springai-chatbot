# AI Studio 上下文压缩模块设计

日期：2026-06-25

参考资料：

- GitHub: `shareAI-lab/learn-claude-code/s08_context_compact`
- 设计核心：便宜的先跑，贵的后跑；先做文本/结构压缩，再做 LLM 摘要，最后用 prompt-too-long 应急兜底。

## 1. 设计目标

AI Studio 当前已经有上下文拼装能力，但压缩逻辑分散在 `ChatContextService` 中，主要依赖：

- 滚动摘要
- 最近窗口
- 字符数预算裁剪
- 最近历史单条消息长度上限

这些机制能控制普通聊天上下文增长，但还不是一个独立的上下文压缩模块。

新模块目标：

1. 把上下文构造和上下文压缩拆开。
2. 用 token 预算替代纯字符预算。
3. 按上下文段落类型和优先级裁剪，不再简单删除 `messages[1]`。
4. 区分普通聊天、RAG、Agent 工具结果、代码审查 workspace 内容。
5. 提供自动压缩和 prompt-too-long 应急压缩。
6. 为后续真实 token、cache hit、延迟观测提供统一埋点。

## 2. 模块边界

建议新增包：

```text
chatbot-service/src/main/java/com/example/chatbot/context/
```

建议类：

```text
ContextSegment
ContextSegmentType
ContextCompressionProperties
ContextTokenEstimator
ContextCompressionService
ContextCompactionResult
ContextTranscriptService
ContextSummaryCompressor
ReactiveContextCompactor
```

职责拆分：

| 模块 | 职责 |
| --- | --- |
| `ChatContextService` | 只负责收集 system prompt、历史、摘要、RAG、当前输入，生成 segment 列表 |
| `ContextCompressionService` | 负责执行 L1/L2/L3/L4/reactive 压缩管线 |
| `ContextTokenEstimator` | 负责 token 估算，优先使用 provider usage 或 tokenizer，降级字符估算 |
| `ContextSummaryCompressor` | 负责调用 LLM 生成压缩摘要 |
| `ContextTranscriptService` | 负责保存压缩前完整 transcript，便于审计和恢复 |

## 3. 核心数据结构

### 3.1 ContextSegment

```java
public class ContextSegment {
    private String id;
    private ContextSegmentType type;
    private String role;
    private String content;
    private int priority;
    private boolean required;
    private boolean compactable;
    private boolean toolResult;
    private String toolName;
    private String sourceRef;
    private int estimatedTokens;
    private LocalDateTime createdAt;
}
```

### 3.2 ContextSegmentType

```text
SYSTEM_FIXED
TOOL_SCHEMA
USER_MEMORY
SESSION_SUMMARY
RECENT_HISTORY
RAG_CONTEXT
WEB_CONTEXT
TOOL_RESULT
WORKSPACE_FILE
CODE_REVIEW_DIFF
CURRENT_USER_INPUT
COMPACTED_SUMMARY
```

### 3.3 优先级建议

数字越小越不该被裁。

| 类型 | required | priority | 说明 |
| --- | --- | ---: | --- |
| `SYSTEM_FIXED` | true | 0 | 主系统规则，不能删 |
| `CURRENT_USER_INPUT` | true | 0 | 当前问题，不能删，但过长时可单独截断并提示 |
| `TOOL_SCHEMA` | true | 1 | Agent 工具定义，通常固定 |
| `USER_MEMORY` | true | 1 | 用户长期偏好和安全边界 |
| `SESSION_SUMMARY` | false | 2 | 会话摘要 |
| `RAG_CONTEXT` | false | 3 | 当前问题证据，优先保留靠前结果 |
| `RECENT_HISTORY` | false | 4 | 最近对话，尾部优先保留 |
| `WEB_CONTEXT` | false | 5 | 网页抓取内容，先留摘要/预览 |
| `WORKSPACE_FILE` | false | 6 | 代码文件内容，优先用摘要和路径 |
| `CODE_REVIEW_DIFF` | false | 6 | 大 diff 需要分块/摘要 |
| `TOOL_RESULT` | false | 7 | 旧工具结果最适合压缩 |

## 4. 压缩管线

借鉴参考文章的思想，但按 AI Studio 现状调整为五层：

```text
每轮 LLM 调用前：

L3 largeResultPersist
-> L1 snipHistory
-> L2 microCompactToolResults
-> L2.5 segmentBudgetTrim
-> L4 autoCompactSummary
-> 调用模型
-> prompt_too_long 时 reactiveCompact
```

注意：执行编号可以沿用文章习惯，但实际顺序必须先处理大结果，再处理中间裁剪和旧结果占位。

## 5. L1: snipHistory

目标：消息数量太多时裁掉中间旧消息。

适用场景：

- 普通聊天历史过长
- Agent 多轮工具调用后消息列表膨胀

规则建议：

```text
触发条件：
- message count > 60

保留：
- head: 固定 system / memory / summary 段
- tail: 最近 40 条 message 或最近 10 轮完整问答

替换：
- 中间被裁内容替换为一个 COMPACTED_SUMMARY placeholder
```

对 AI Studio 的特殊约束：

- 不能裁掉当前用户输入。
- 不能裁掉安全 system prompt。
- 如果未来 Agent 使用 tool call message，需要避免把 tool_use 和 tool_result 拆开。

## 6. L2: microCompactToolResults

目标：旧工具结果不再完整保留，只保留最近几条完整结果。

当前项目里普通聊天没有 tool result，主要用于 Agent：

- `KnowledgeReadTools`
- `FileReadTools`
- `WorkspaceTools`
- `ReviewWorkspaceTools`
- `GitReviewTools`
- `WebTools`

规则建议：

```text
触发条件：
- TOOL_RESULT 段数量 > 3

保留：
- 最近 3 条 tool result 完整内容

压缩：
- 更旧 tool result 替换为：
  [Earlier tool result compacted. Tool={toolName}, source={sourceRef}. Re-run/read again if needed.]
```

代码审查 Agent 的注意点：

- 文件读取结果被压缩后，必须保留 `workspaceId/fileId/path/hash`，否则后续无法重新读取。
- diff 结果被压缩后，必须保留 `runId/path/summary/additions/deletions/truncated`。
- Pending Action 相关结果不能丢失 actionId、status、targetPath。

## 7. L3: largeResultPersist

目标：单次工具结果过大时落盘或持久化，活跃上下文只保留引用和预览。

参考文章中阈值是 tool result 总和 200KB。AI Studio 建议：

```yaml
app.chatbot.context.compression.large-result-max-chars: 200000
app.chatbot.context.compression.large-result-preview-chars: 2000
```

存储位置建议：

```text
数据库：
agent_tool_execution_log.result_summary
agent_context_artifact

或 workspace 内部托管：
agent-context-artifacts/{sessionId}/{artifactId}.txt
```

上下文中保留：

```text
[Persisted large tool result]
tool=readWorkspaceFile
artifactId=...
path=...
sha256=...
preview:
...
```

安全边界：

- 不把完整 secret、大文件、二进制内容塞进 prompt。
- artifact 必须带 userId/sessionId 隔离。
- 文件内容恢复必须走已有 workspace 权限校验。

## 8. L2.5: segmentBudgetTrim

目标：在不调用 LLM 的情况下，按 segment 优先级裁剪。

当前 `trimToBudget` 是简单删除 `messages[1]`，建议替换为优先级裁剪：

```text
while totalTokens > budget:
    1. 裁 TOOL_RESULT 旧结果
    2. 裁 WEB_CONTEXT 旧内容，只留 title/url/summary
    3. 裁 WORKSPACE_FILE，只留 path/hash/摘要
    5. 裁 RAG_CONTEXT 低分 chunk
    6. 裁 RECENT_HISTORY 中较旧轮次
    7. 缩短 SESSION_SUMMARY
    8. 如果 CURRENT_USER_INPUT 仍超限，返回明确错误或截断并提示
```

预算建议：

```yaml
app.chatbot.context.compression.max-input-tokens: 24000
app.chatbot.context.compression.reserve-output-tokens: 4000
app.chatbot.context.compression.reserve-safety-buffer-tokens: 2000
```

真实阈值应按模型动态配置：

```text
inputBudget = modelContextWindow - maxOutputTokens - safetyBuffer
```

## 9. L4: autoCompactSummary

目标：前面 0 API 压缩仍超预算时，调用 LLM 做摘要压缩。

触发条件：

```text
estimatedTokens > autoCompactThreshold
```

建议阈值：

```yaml
app.chatbot.context.compression.auto-compact-enabled: true
app.chatbot.context.compression.auto-compact-threshold-ratio: 0.80
app.chatbot.context.compression.max-consecutive-auto-compact-failures: 3
```

处理流程：

1. `ContextTranscriptService` 保存压缩前完整 transcript。
2. `ContextSummaryCompressor` 调 LLM 生成摘要。
3. 用 `COMPACTED_SUMMARY` 替换旧 segment。
4. 重新附加必要上下文：
   - system prompt
   - user memory
   - 当前用户输入
   - 最近 3-5 轮对话
   - 当前 active review run
   - 最近 workspace 文件路径/hash/摘要

压缩摘要必须保留：

```text
1. 当前用户目标
2. 已确认约束和偏好
3. 安全边界
4. 当前任务进展
5. 已读取/修改/待修改文件
6. 代码审查 runId / issueId / actionId
7. 未完成事项
8. 重要决策
9. 不确定点和待验证项
```

压缩 prompt 必须明确：

```text
CRITICAL: Respond with text only. Do not call tools.
Do not invent facts.
Preserve IDs, file paths, user constraints, pending actions, and unresolved work.
```

## 10. reactiveCompact

目标：API 返回 prompt too long 后应急兜底。

触发条件：

- DeepSeek/OpenAI 兼容 API 返回上下文过长错误。
- Ollama 返回 context length exceeded。
- Spring AI 抛出等价异常。

流程：

```text
1. 保存完整 transcript
2. 保留最后 5 条 message 或最近 2 轮
3. 对更早内容生成摘要
4. 重新调用一次模型
5. 最多重试 1 次
```

应急压缩比 autoCompact 更激进，但必须保留：

- system prompt
- 当前用户输入
- 当前代码审查 issue/action/run 标识
- 最近工具结果的必要引用

## 11. 对普通聊天和代码审查 Agent 的差异

### 普通聊天

启用：

- L1 snipHistory
- L2.5 segmentBudgetTrim
- L4 autoCompactSummary
- reactiveCompact

通常不需要：

- L2 microCompactToolResults
- L3 largeResultPersist

### Agent 聊天

全部启用。

因为 Agent 会引入：

- 工具调用结果
- 文件内容
- Git diff
- Web scrape 内容
- workspace 文件读取

这些才是上下文爆炸的主要来源。

### 代码审查 Agent

额外规则：

- diff 超过预算时优先保留 changed hunks、文件路径、问题位置、issueId。
- replacementContent 不应长期进入上下文。
- Pending Action 列表不暴露完整 replacementContent。
- 压缩后必须保留 Pending Action 的 actionId、targetPath、status、expiresAt。

## 12. 配置建议

```yaml
app:
  chatbot:
    context:
      compression:
        enabled: true
        max-input-tokens: 24000
        reserve-output-tokens: 4000
        reserve-safety-buffer-tokens: 2000
        snip-max-messages: 60
        snip-head-messages: 6
        snip-tail-messages: 40
        keep-recent-tool-results: 3
        large-result-max-chars: 200000
        large-result-preview-chars: 2000
        auto-compact-enabled: true
        auto-compact-threshold-ratio: 0.80
        max-consecutive-auto-compact-failures: 3
        reactive-compact-enabled: true
        max-reactive-compact-retries: 1
```

## 13. 迁移计划

### 阶段 A：只做结构重构，不改行为

1. 新增 `ContextSegment`。
2. `ChatContextService` 从直接返回 `List<Message>` 改为先生成 segment。
3. `ContextCompressionService` 初版只做当前等价逻辑。
4. 保持现有测试通过。

### 阶段 B：引入 token 观测和预算

1. 接入模型 usage 记录。
2. 保留 DeepSeek 的 `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`。
3. 增加每轮上下文 segment 统计。
4. 把 `max-context-chars` 升级为 token budget 优先，字符 budget 兜底。

### 阶段 C：实现 0 API 压缩

1. 实现 L1 snipHistory。
2. 实现 L2 microCompactToolResults。
3. 实现 L3 largeResultPersist。
4. 实现 L2.5 segmentBudgetTrim。

### 阶段 D：实现 LLM 压缩和应急兜底

1. 实现 autoCompactSummary。
2. 实现 transcript 保存。
3. 实现 reactiveCompact。
4. 增加熔断器和重试上限。

## 14. 验收标准

功能验收：

- 普通聊天长会话不会因历史增长无限膨胀。
- Agent 连续读文件/跑工具后，旧工具结果会被压缩。
- 大文件结果不会完整进入 prompt。
- prompt too long 时最多重试 1 次，不死循环。
- 压缩后仍保留用户安全约束和当前任务状态。

指标验收：

- 记录每轮 prompt tokens、completion tokens、cache hit tokens、cache miss tokens。
- 记录压缩前后 segment 数量和 token 数。
- 记录每层压缩释放 tokens。
- 记录 autoCompact 调用次数和失败次数。

安全验收：

- 不绕过 Pending Action。
- 不把 secret、大文件、replacementContent 长期暴露在上下文。
- artifact 恢复必须做 userId/sessionId/workspace 权限校验。

## 15. 当前结论

AI Studio 可以借鉴 Claude Code 的分层压缩思想，但不能照搬。

我们自己的上下文压缩模块应该以 `ContextSegment` 为核心，把普通聊天、RAG、Agent 工具结果、代码审查 diff、workspace 文件内容统一建模，再按成本递增执行：

```text
结构压缩 -> 占位/落盘 -> 优先级裁剪 -> LLM 摘要 -> prompt-too-long 应急兜底
```

这样既能控制 token，又能保留代码审查 Agent 最重要的安全边界和任务状态。

## 16. 阶段 A 执行状态

执行日期：2026-06-25

已完成：

1. 新增 `com.example.chatbot.context` 包。
2. 新增 `ContextSegment` 和 `ContextSegmentType`，开始把上下文从裸 `Message` 列表抽象成可压缩 segment。
3. 新增 `ContextCompressionProperties`，配置前缀为 `app.chatbot.context.compression`。
4. 新增 `ContextTokenEstimator`，当前为轻量估算器，后续可替换为 tokenizer 或模型 usage 统计。
5. 新增 `ContextCompressionService`，初版保持原有字符预算裁剪行为：超过 `max-context-chars` 时从第 2 个 segment 开始删除，直到预算内。
6. `ChatContextService` 已接入 segment -> compression -> message 流程，但 public 方法签名保持不变。
7. 新增 `ContextCompressionServiceTest`，覆盖启用/禁用压缩和等价字符预算裁剪。

本阶段刻意未做：

- L1 `snipHistory`
- L2 `microCompactToolResults`
- L3 `largeResultPersist`
- L4 `autoCompactSummary`
- `reactiveCompact`
- transcript 落盘
- tool result / workspace artifact 恢复

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,ChatContextServiceBenchmarkTest,ContextCompressionServiceTest" test
```

验证结果：通过。

当前行为变化：

- 业务行为应与原有 `trimToBudget` 基本等价。
- 内部结构从直接拼 `List<Message>` 改为先拼 `List<ContextSegment>`，再由 `ContextCompressionService` 输出 messages。
- 基准样本字符数和估算 token 未变化：`3129` 字符、估算 `1764` token。
- 本地构造耗时因新增 segment 和估算层略有上升，本次基准约 `3.724 ms`。

## 17. 阶段 B 执行状态

执行日期：2026-06-25

已完成：

1. `ContextCompressionService` 从仅按字符预算裁剪，升级为先按估算 token 预算裁剪，再按字符预算兜底。
2. `ContextCompactionResult` 增加压缩指标：
   - `removedEstimatedTokens`
   - `tokenBudgetExceeded`
   - `charBudgetExceeded`
3. `ContextCompressionServiceTest` 增加 token 预算优先裁剪测试。
4. 默认 token 预算使用 `app.chatbot.context.compression.max-input-tokens`，当前默认 `24000`。

当前策略：

```text
if estimatedTokens > maxInputTokens:
    从第 2 个 segment 开始删除，直到 token 预算内

if chars > maxContextChars:
    从第 2 个 segment 开始删除，直到字符预算内
```

这仍然是阶段 B 的等价/过渡实现，还没有进入阶段 C 的优先级裁剪。也就是说，虽然 `ContextSegment` 已经有 `type` 和 `priority`，但当前删除顺序仍保持原 `trimToBudget` 风格，避免一次性改变聊天行为。

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,ChatContextServiceBenchmarkTest,ContextCompressionServiceTest" test
```

验证结果：通过。

当前观测：

- 基准样本字符数和估算 token 未变化：`3129` 字符、估算 `1764` token。
- 本次基准上下文构造耗时约 `5.583 ms`。
- 耗时增加来自每轮 segment token 估算和多次预算求和。后续可优化为一次性累计、按删除量增量更新，而不是每轮 `stream().sum()`。

下一阶段建议：

1. 实现真正的 `segmentBudgetTrim`：按 `ContextSegmentType/priority` 删除，而不是固定删除第 2 段。
2. 对 `RAG_CONTEXT` 支持按检索 score 裁低分 chunk。
3. 对 `RECENT_HISTORY` 支持成对裁剪 user/assistant，避免破坏对话轮次。
4. 增加压缩日志或指标上报，记录每层释放 token 数。

## 18. 阶段 C1 执行状态：tokenizer 与优先级裁剪

执行日期：2026-06-25

已完成：

1. 引入 `com.knuddels:jtokkit:1.1.0`。
2. `ContextTokenEstimator` 优先使用 `cl100k_base` tokenizer 计算 token。
3. tokenizer 初始化或运行失败时，自动退回阶段 B 的启发式估算逻辑。
4. `ContextCompressionService` 从固定删除第 2 个 segment，升级为按 segment 优先级裁剪：
   - `SYSTEM_FIXED` 和 `CURRENT_USER_INPUT` 标记为 required，不裁剪。
   - 优先裁剪 priority 最大的低价值 segment。
   - 默认优先级：`TOOL_RESULT` > `WORKSPACE_FILE/CODE_REVIEW_DIFF` > `WEB_CONTEXT` > `RECENT_HISTORY` > `RAG_CONTEXT` > `SESSION_SUMMARY`。
5. `RECENT_HISTORY` 支持按连续 history 小组裁剪，避免只删 user 或 assistant 造成半轮对话残留。
6. `ContextCompressionService` 对调用方未设置 required/priority 的 segment 自动补默认值，降低误用风险。
7. 基准测试 `ChatContextServiceBenchmarkTest` 改为使用同一个 `ContextTokenEstimator`，避免测试和生产估算逻辑不一致。

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,ChatContextServiceBenchmarkTest,ContextCompressionServiceTest" test
```

验证结果：通过。

当前观测：

- 基准样本字符数仍为 `3129`。
- 使用 `jtokkit cl100k_base` 后，估算 token 为 `1745`。
- 本次基准上下文构造耗时约 `6.084 ms`。耗时上升主要来自 tokenizer 计算，后续可通过按 segment 缓存 token 或只对变更 segment 重算来优化。

当前限制：

- `cl100k_base` 是 DeepSeek/OpenAI 兼容模型的近似 tokenizer，不保证与 DeepSeek 服务端完全一致。
- 真实计费 token 仍以模型接口返回的 `usage.prompt_tokens` 为准。
- 本阶段只实现了 `segmentBudgetTrim`，还没有实现 L1 `snipHistory`、L2 `microCompactToolResults`、L3 `largeResultPersist`。

## 19. 阶段 C/D 执行状态：核心压缩管线

执行日期：2026-06-25

已完成阶段 C：

1. `ContextCompressionService.compact()` 已升级为分层压缩管线：

   ```text
   L3 largeResultPersist
   -> L1 snipHistory
   -> L2 microCompactToolResults
   -> L2.5 segmentBudgetTrim
   -> L4 autoCompactSummary（配置开启时）
   ```

2. L3 `largeResultPersist` 已实现：对 `TOOL_RESULT`、`WORKSPACE_FILE`、`CODE_REVIEW_DIFF`、`WEB_CONTEXT` 这类大段内容生效。超过 `large-result-max-chars` 后，prompt 中只保留 artifact 引用、来源、原始字符数和 preview。
3. L1 `snipHistory` 已实现：segment 数量超过 `snip-max-messages` 时触发，保留 head/tail，中间可压缩段替换为 `COMPACTED_SUMMARY` 占位摘要。
4. L2 `microCompactToolResults` 已实现：只保留最近 `keep-recent-tool-results` 条完整工具结果，更旧的 `TOOL_RESULT` 替换为工具名、sourceRef、原始 token 数和重读提示。
5. L2.5 `segmentBudgetTrim` 继续作为预算兜底：先按 tokenizer 估算 token budget 裁剪，再按字符 budget 兜底。

已完成阶段 D：

1. 新增 `ContextSummaryCompressor` 接口。
2. 新增 `ModelContextSummaryCompressor`，生产默认优先调用已配置模型做 LLM 语义摘要：
   - 优先使用 OpenAI/DeepSeek 兼容模型。
   - 其次使用 Ollama。
   - 模型不可用、调用失败或返回空内容时，降级到 `DeterministicContextSummaryCompressor` 本地规则摘要。
3. 保留 `DeterministicContextSummaryCompressor`，只作为失败降级和测试兜底，不再作为主策略。
3. 新增 `ContextTranscriptService`，当前使用内存快照保存压缩前 transcript，预留后续数据库或 artifact 持久化结构。
4. `autoCompactSummary` 已实现并默认开启：`auto-compact-enabled=true` 且超过 `maxInputTokens * autoCompactThresholdRatio` 时触发，触发前保存 transcript，然后调用 LLM 生成语义摘要。
5. `reactiveCompact` 已实现并默认开启：`reactive-compact-enabled=true` 时可在 prompt-too-long 后调用，保存 transcript，并更激进压缩早期上下文。

新增测试覆盖：

- 大结果预览化。
- 旧工具结果微压缩。
- 长上下文中段 snip。
- auto compact 摘要替换。
- reactive compact 保留尾部和当前输入。

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=ChatContextServiceTest,ChatContextServiceBenchmarkTest,ContextCompressionServiceTest" test
```

验证结果：通过。

最新 benchmark：

```text
[ContextBenchmark] runs=50 messages=24 chars=3129 estimatedTokens=1745 stablePrefixChars=45 stablePrefixRatio=1.44% avgBuildMs=3.268
```

当前限制：

1. L3 现在只是 prompt 层占位和 sourceRef 引用，尚未把大结果写入数据库或 workspace artifact。
2. LLM 摘要已接入，但当前没有独立的摘要模型配置，复用主聊天模型；后续可增加专用 summary model、summary max tokens 和 timeout。
3. `reactiveCompact` 已有方法，但还没有接到具体模型异常捕获链路；下一步应在 DeepSeek/OpenAI/Ollama prompt-too-long 异常处最多重试一次。
4. 本地确定性摘要只作为模型失败降级，不作为上下文超出时的主压缩策略。
