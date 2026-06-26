# 长期记忆模块更新计划

日期：2026-06-25

## 1. 背景和目标

当前项目已有会话上下文能力：

- Redis 保存短期 `chat:history:{sessionId}` 热历史。
- MySQL `chat_record` 持久化聊天原文。
- MySQL `chat_session_summary` 保存会话级滚动摘要。
- `ChatContextService` 每轮构建 `SYSTEM_FIXED -> SESSION_SUMMARY -> RELEVANT_HISTORY -> RECENT_HISTORY -> RAG_CONTEXT -> CURRENT_USER_INPUT`。
- `ContextCompressionService` 控制本轮 prompt 预算。

这些能力解决的是“当前会话上下文如何放进模型窗口”，不是严格意义上的长期记忆。

本计划新增长期记忆模块，目标是：

- 像 Claude Code 的 `CLAUDE.md` / memory system 一样，默认把用户级、项目级长期记忆索引注入上下文。
- 第一层注入长期记忆索引和简短描述，而不是全量详情。
- 第二层由 LLM 根据索引判断是否需要加载相关 memory detail。
- 长期记忆写入由用户显式决定；Agent 必要时提示“这条信息适合写入长期记忆”，但不自动入库。
- 长期记忆跨 session 生效，保存稳定项目事实、用户偏好、协作反馈和常用引用。
- 与代码审查 Agent 的安全边界兼容，不绕过 Pending Action，不自动 commit / push。

参考设计：

- shareAI-lab `s09_memory`：Memory System = 存储、加载、提取、整理。
- 参考 `.memory/*.md + MEMORY.md index` 的形态，但本项目生产主存储采用 MySQL。
- 在 prompt 中默认加载 memory index，必要时再加载具体 memory 内容。

## 2. 核心原则

长期记忆不是“模型自动抽取一堆事实后按置信度写入”的系统。

第一版采用更接近 Claude Code 的方式：

```text
用户显式写入 memory
-> 系统维护 memory index
-> 每轮默认注入 memory index
-> LLM 根据 index 判断是否加载 detail
-> detail 被选中后进入本轮上下文
```

目标上下文顺序：

```text
SYSTEM_FIXED
-> LONG_TERM_MEMORY_INDEX
-> SELECTED_LONG_TERM_MEMORY_DETAIL
-> SESSION_SUMMARY
-> RELEVANT_HISTORY
-> RECENT_HISTORY
-> RAG_CONTEXT
-> CURRENT_USER_INPUT
```

其中：

- `LONG_TERM_MEMORY_INDEX`：默认每轮注入，内容轻量，类似 `MEMORY.md` 索引。
- `SELECTED_LONG_TERM_MEMORY_DETAIL`：由 LLM 根据当前任务和 index 选择后注入。
- `SESSION_SUMMARY`：仍然是单会话滚动摘要，不等于长期记忆。

第一版关键约束：

1. 不做置信度打分。
2. 不做自动写入 `AUTO_ACTIVE`。
3. 不引入 `PENDING_REVIEW` 作为主状态。
4. Agent 可以建议写入 memory，但必须由用户确认或手动触发。
5. memory index 默认注入，memory detail 按需注入。
6. LLM 负责根据 index 判断哪些 detail 对当前任务相关。

## 3. 模块定位

长期记忆和现有上下文模块的边界：

| 能力 | 生命周期 | 主要内容 | 当前状态 |
| --- | --- | --- | --- |
| Redis 短期历史 | 约 2 小时 TTL | 当前 session 最近对话 | 已实现 |
| chat_record 原文 | 长期保存 | 用户消息、模型回复 | 已实现 |
| chat_session_summary | session 级 | 单会话滚动摘要 | 已实现 |
| RAG | 请求级动态检索 | 知识库文档片段 | 已实现 |
| 长期记忆 index | 跨 session / 用户 / 项目 | 记忆名称、简述、类型、加载提示 | 待新增 |
| 长期记忆 detail | 跨 session / 用户 / 项目 | 具体偏好、约束、项目事实、引用说明 | 待新增 |

长期记忆只保存“稳定且未来会复用”的信息，例如：

- `user`：用户协作偏好、表达习惯、响应偏好。
- `feedback`：用户反复给出的工作方式反馈。
- `project`：项目架构事实、长期约束、安全边界。
- `reference`：常用入口、文档路径、排查位置、命令索引。

不应写入：

- 一次性任务。
- 临时上下文。
- 密码、token、邮箱授权码、`.env` 内容。
- 模型推测但用户未确认或没有上下文证据的事实。
- 明显可能过期且没有来源的结论。

## 4. 工作原理

目标流程：

```text
存储
  -> MySQL agent_long_term_memory
  -> name / description / type / load_hint 组成 memory index
  -> content 保存 detail
  -> agent_memory_event 审计
  -> 可选 Markdown 导入/导出

加载
  -> 每轮对话默认加载用户级 + 项目级 ACTIVE memory index
  -> 把 index/description/load_hint 注入 LONG_TERM_MEMORY_INDEX
  -> LLM 根据 index 选择最多 N 条 detail
  -> 把被选中的 detail 注入 SELECTED_LONG_TERM_MEMORY_DETAIL

写入
  -> 用户手动新增 memory
  -> 用户在 Agent 提示后确认写入 memory
  -> 从 AGENTS.md / CLAUDE.md / 项目文档导入 memory
  -> 所有写入都直接成为 ACTIVE，除非用户选择保存为草稿

整理
  -> 定期对同一 user/project 的 memory 去重、合并、剪枝
  -> 生成整理建议
  -> 用户确认后应用
```

与参考章节保持一致的关键点：

- memory index 默认常驻上下文。
- 选择 detail 时先看索引/描述，而不是把所有 detail 都塞给模型。
- 写入长期记忆是显式行为，由用户决定。
- 整理可由系统建议，但覆盖、合并、删除应由用户确认。

## 5. 数据库设计

### 5.1 agent_long_term_memory

新增迁移：

```text
chatbot-service/src/main/resources/db/migration/V12__add_long_term_memory_tables.sql
```

建议字段：

```text
id BIGINT PRIMARY KEY AUTO_INCREMENT
user_id BIGINT NOT NULL
scope_type VARCHAR(32) NOT NULL
scope_key VARCHAR(255) NOT NULL
memory_type VARCHAR(32) NOT NULL
name VARCHAR(128) NOT NULL
description VARCHAR(512) NOT NULL
content TEXT NOT NULL
load_hint VARCHAR(512) NULL
source_type VARCHAR(32) NOT NULL
status VARCHAR(32) NOT NULL
created_time DATETIME DEFAULT CURRENT_TIMESTAMP
updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
last_used_time DATETIME NULL
use_count BIGINT NOT NULL DEFAULT 0
content_hash VARCHAR(64) NULL
```

字段含义：

| 字段 | 说明 |
| --- | --- |
| `scope_type` | `USER` / `PROJECT` / `SESSION` / `GLOBAL` |
| `scope_key` | userId、workspaceId、projectKey、sessionId 等 |
| `memory_type` | `user` / `feedback` / `project` / `reference` |
| `name` | memory 索引标题，默认注入 |
| `description` | memory 简短描述，默认注入 |
| `content` | memory 详细内容，按需注入 |
| `load_hint` | 何时应加载 detail 的提示 |
| `source_type` | `MANUAL` / `IMPORTED` / `SYSTEM` / `AGENT_SUGGESTED` |
| `status` | `ACTIVE` / `DRAFT` / `ARCHIVED` |

索引建议：

```text
idx_memory_user_scope_status (user_id, scope_type, scope_key, status)
idx_memory_user_type_status (user_id, memory_type, status)
idx_memory_hash (user_id, content_hash)
idx_memory_updated_time (updated_time)
```

### 5.2 agent_memory_event

用于审计和回溯来源。

建议字段：

```text
id BIGINT PRIMARY KEY AUTO_INCREMENT
memory_id BIGINT NULL
user_id BIGINT NOT NULL
event_type VARCHAR(32) NOT NULL
source_session_id VARCHAR(255) NULL
source_record_id BIGINT NULL
payload_json JSON NULL
created_time DATETIME DEFAULT CURRENT_TIMESTAMP
```

事件类型：

```text
CREATE
UPDATE
ARCHIVE
DELETE
IMPORT
SUGGEST
LOAD_INDEX
LOAD_DETAIL
CONSOLIDATE_PREVIEW
CONSOLIDATE_APPLY
```

## 6. 后端代码结构

新增包：

```text
chatbot-service/src/main/java/com/example/chatbot/memory/
```

核心类：

```text
LongTermMemoryService
MemoryIndexService
MemorySelectionService
MemorySuggestionService
MemoryConsolidationService
MemoryPromptBuilder
MemoryProperties
MemoryType
MemoryScopeType
MemoryStatus
MemorySourceType
```

职责：

| 类 | 职责 |
| --- | --- |
| `LongTermMemoryService` | CRUD、权限校验、审计 |
| `MemoryIndexService` | 加载默认注入的 index/description/load_hint |
| `MemorySelectionService` | 调用 LLM 从 index 中选择 detail |
| `MemorySuggestionService` | Agent 必要时生成“建议写入 memory”的提示 |
| `MemoryConsolidationService` | 去重、合并、剪枝、归档建议 |
| `MemoryPromptBuilder` | 构建 index segment 和 detail segment |

新增实体和 Mapper：

```text
chatbot-service/src/main/java/com/example/chatbot/entity/AgentLongTermMemory.java
chatbot-service/src/main/java/com/example/chatbot/entity/AgentMemoryEvent.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentLongTermMemoryMapper.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentMemoryEventMapper.java
```

新增 Controller：

```text
chatbot-service/src/main/java/com/example/chatbot/controller/LongTermMemoryController.java
```

## 7. 配置项

新增配置前缀：

```yaml
app:
  chatbot:
    memory:
      enabled: true
      index-enabled: true
      detail-selection-enabled: true
      select-mode: LLM_SIDE_QUERY
      max-index-items: 30
      max-index-chars: 6000
      max-selected-details: 5
      max-detail-chars-per-item: 4096
      max-total-detail-chars: 12000
      suggestion-enabled: true
      consolidation-enabled: false
      consolidation-min-items: 10
```

第一版建议：

- `enabled=true`
- `index-enabled=true`
- `detail-selection-enabled=true`
- `select-mode=LLM_SIDE_QUERY`
- `suggestion-enabled=true`
- `consolidation-enabled=false`

说明：

- index 默认注入。
- detail 由 LLM 根据 index 选择后注入。
- Agent 只建议写入 memory，不自动写入。
- 整理默认只生成建议，不自动覆盖。

## 8. 加载策略

### 8.1 默认加载 Memory Index

每轮对话开始默认加载：

```text
当前 user 的 USER scope ACTIVE memory
当前 project/workspace 的 PROJECT scope ACTIVE memory
必要时 GLOBAL scope SYSTEM memory
```

构建轻量 index：

```text
Long-term memory index:
- id=12 type=user name="User collaboration preference"
  description="User prefers direct implementation, verification, and concise summaries."
  load_hint="Load when deciding response style or task execution approach."
- id=18 type=project name="Gateway production entry"
  description="Production user traffic must go through Gateway :9000."
  load_hint="Load when changing deployment, routes, frontend API paths, or service ports."
```

这个 index 默认注入 prompt，类似 `MEMORY.md`。

控制点：

- 最多 `max-index-items=30`。
- 最多 `max-index-chars=6000`。
- 超限时 project memory 优先于 user feedback，安全边界优先于普通偏好。

### 8.2 LLM 选择 Memory Detail

LLM 根据 index、当前用户输入和任务类型选择需要加载 detail 的 memory。

输入只给 index：

```text
id
memory_type
name
description
load_hint
```

模型输出：

```json
{
  "selectedIds": [12, 18],
  "reason": "The user is asking for implementation planning and deployment-related constraints."
}
```

安全要求：

- LLM 只能选择已有 ID，不能生成新 memory。
- 最多选择 `max-selected-details` 条。
- 解析失败时降级为不加载 detail，只保留 index。
- 不把所有 detail 直接塞进 selection prompt。

## 9. 接入 ChatContextService

当前 `ContextSegmentType` 已有：

```text
USER_MEMORY
```

建议新增：

```text
MEMORY_INDEX
MEMORY_DETAIL
PROJECT_MEMORY
```

如果暂不新增枚举，也可以先复用 `USER_MEMORY`，但 `ContextSegment` 的 `sourceRef` 应标记：

```text
memory:index
memory:detail:{id}
```

接入点：

```text
chatbot-service/src/main/java/com/example/chatbot/service/ChatContextService.java
```

目标顺序：

```text
SYSTEM_FIXED
-> MEMORY_INDEX
-> MEMORY_DETAIL
-> SESSION_SUMMARY
-> RELEVANT_HISTORY
-> RECENT_HISTORY
-> RAG_CONTEXT
-> CURRENT_USER_INPUT
```

`MemoryPromptBuilder` 输出示例：

```text
Long-term memory index:
- id=1 type=feedback name="Response style"
  description="User prefers direct implementation and verification over high-level suggestions."
- id=2 type=project name="Gateway production entry"
  description="Production user traffic must go through Gateway :9000."

Loaded long-term memory detail:
[memory id=2 project]
Production environment user entry must go through Gateway :9000. Do not expose chatbot-service:8080 or file-service:8081 to host ports.
```

注入约束：

- index 默认注入。
- detail 选择后注入。
- `DRAFT` 不注入。
- `ARCHIVED` 不注入。
- `ACTIVE` 注入。
- 长期记忆优先级高于 session summary，但低于 system prompt 和当前用户输入。

## 10. 写入策略

长期记忆写入由用户决定。

写入来源：

```text
用户在 Memory 面板手动新增
用户点击 Agent 提示中的“写入长期记忆”
用户导入 AGENTS.md / CLAUDE.md / Markdown memory 文件
```

Agent 可以在合适时机提示：

```text
这条偏好看起来适合保存为长期记忆，之后我可以默认遵守。
是否写入 memory？
```

但 Agent 不应直接写入。用户确认后才调用：

```text
POST /api/chat/memory
```

建议写入请求体：

```json
{
  "scopeType": "PROJECT",
  "scopeKey": "springaI-chatbot",
  "memoryType": "project",
  "name": "Gateway production entry",
  "description": "Production user traffic must go through Gateway :9000.",
  "content": "Production environment user entry must go through Gateway :9000. Do not expose chatbot-service:8080 or file-service:8081 to host ports.",
  "loadHint": "Load when changing deployment, routes, frontend API paths, or service ports."
}
```

写入前后端仍需做：

- secret pattern 过滤。
- content hash 去重。
- 同 scope + type + name 重复检查。
- project scope 权限校验。

## 11. 整理策略

整理对应参考章节中的 Dream / consolidation。

第一版不默认启用自动整理，第二版接入。

触发条件：

```text
consolidation-enabled=true
同一 user/project memory 数量 >= consolidation-min-items
用户主动点击整理
```

整理操作只生成建议：

```text
merge memory A+B -> C
archive stale memory
update description
split oversized memory
resolve duplicate reference
```

必须由用户确认后 apply，尤其是：

- 删除安全约束。
- 覆盖 project memory。
- 合并存在冲突的事实。

## 12. API 设计

基础管理接口：

```text
GET    /api/chat/memory
GET    /api/chat/memory/{id}
POST   /api/chat/memory
PUT    /api/chat/memory/{id}
POST   /api/chat/memory/{id}/archive
DELETE /api/chat/memory/{id}
```

加载和调试接口：

```text
POST /api/chat/memory/index-preview
POST /api/chat/memory/select-detail-preview
```

建议写入接口：

```text
POST /api/chat/memory/suggest
POST /api/chat/memory/suggest/{suggestionId}/apply
POST /api/chat/memory/suggest/{suggestionId}/dismiss
```

导入接口：

```text
POST /api/chat/memory/import-preview
POST /api/chat/memory/import-apply
```

整理接口：

```text
POST /api/chat/memory/consolidate-preview
POST /api/chat/memory/consolidate-apply
```

所有接口必须从 `AuthInterceptor` 获取当前 `userId`，不能信任前端传入的 `userId`。

## 13. 前端计划

主要文件：

```text
chatbot-service/src/main/resources/templates/chat.html
```

新增入口：

```text
侧栏：Long-term Memory / 长期记忆
```

第一版 UI：

- Memory 列表。
- 类型筛选：`user` / `feedback` / `project` / `reference`。
- 状态筛选：`ACTIVE` / `DRAFT` / `ARCHIVED`。
- 手动新增。
- 编辑。
- 归档。
- 删除。
- 查看默认注入的 memory index。
- 查看本轮 selected detail。
- 接受或忽略 Agent 的 memory 写入建议。
- 导入 `AGENTS.md` / `CLAUDE.md` / Markdown memory。

注意：

- `ACTIVE` 默认进入 index。
- `DRAFT` 只在管理面板展示，不默认进 prompt。
- 用户可以随时编辑、归档或删除 memory。
- 删除和归档需要确认。

## 14. 代码审查 Agent 预置记忆

长期记忆应服务当前项目定位。建议初始化为 `PROJECT` scope 的 `ACTIVE` memory。

第一批项目级 memory index：

```text
project: 当前系统是受控的工程化代码审查 Agent，不是泛用聊天助手。
project: 生产环境用户入口只走 Gateway :9000。
project: chatbot-service:8080 和 file-service:8081 不应暴露给宿主机。
project: GitReviewService 只能做只读 Git 操作。
project: 真正修改 workspace 文件必须经过 Pending Action 二次确认。
project: 不自动 commit / push / 操作真实 Git 仓库。
feedback: 用户喜欢直接推进，实现、验证、总结，不希望只停留在建议层面。
reference: 代码审查阶段文档位于 docs/agent/。
reference: 代码审查核心 Controller 是 CodeReviewAgentController。
```

初始化方式建议：

```text
提供 import-preview，从 AGENTS.md 生成 memory 草案
-> 用户确认
-> 写入 ACTIVE memory
-> 写入 agent_memory_event
```

因为你希望长期记忆像 `CLAUDE.md` 一样默认生效，导入确认后的状态应直接是 `ACTIVE`。

## 15. 安全和权限边界

必须遵守：

- Controller 不信任前端传入 `userId`。
- 所有 memory 查询、更新、删除都按当前用户隔离。
- `PROJECT` scope 必须校验当前用户对 workspace/project 的访问权。
- `ARCHIVED` 不进入 prompt。
- `DRAFT` 不进入 prompt。
- `ACTIVE` 默认进入 memory index。
- 写入逻辑不得保存密钥、密码、token、邮箱授权码、`.env`。
- 长期记忆不得触发文件写入、Git 写入或 shell 执行。
- 代码审查相关 project memory 不得绕过 Pending Action 安全边界。

## 16. 测试计划

新增测试：

```text
chatbot-service/src/test/java/com/example/chatbot/memory/LongTermMemoryServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryIndexServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySelectionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySuggestionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryPromptBuilderTest.java
chatbot-service/src/test/java/com/example/chatbot/service/ChatContextServiceTest.java
```

覆盖点：

- 用户 A 不能读取用户 B 的 memory。
- `ACTIVE` 默认进入 memory index。
- `DRAFT` 不进入 prompt。
- `ARCHIVED` 不进入 prompt。
- index 超过 `max-index-chars` 时正确裁剪。
- LLM detail selection 最多返回 `max-selected-details`。
- selected detail 能被 `ChatContextService` 注入。
- selection 解析失败时只保留 index。
- 用户确认后 memory suggestion 才写入。
- archive 后不再被检索。
- delete 写审计事件。

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=LongTermMemoryServiceTest,MemoryIndexServiceTest,MemorySelectionServiceTest,MemoryPromptBuilderTest,ChatContextServiceTest" test
```

## 17. 分阶段实施

### 阶段 1：基础存储和默认 Index

目标：

- 新增 MySQL 表。
- 新增实体、Mapper、Service。
- 支持 `ACTIVE` / `DRAFT` / `ARCHIVED`。
- 支持默认加载 memory index。
- 完成权限隔离和审计。

交付：

```text
V12__add_long_term_memory_tables.sql
LongTermMemoryService
MemoryIndexService
LongTermMemoryController
LongTermMemoryServiceTest
MemoryIndexServiceTest
```

### 阶段 2：接入 ChatContextService

目标：

- 新增 `MemoryPromptBuilder`。
- 将 memory index 默认注入上下文。
- 支持 selected detail 注入上下文。
- 确保长期记忆位置在 system prompt 之后、session summary 之前。

交付：

```text
MemoryPromptBuilder
ChatContextService 接入
MemoryPromptBuilderTest
ChatContextServiceTest 增补
```

### 阶段 3：LLM Detail 选择

目标：

- 新增 `MemorySelectionService`。
- 用 LLM side-query 从 index 中选择 detail。
- 只从 index 选 ID，再加载 detail。
- 解析失败时只保留 index。

交付：

```text
MemorySelectionService
LLM_SIDE_QUERY select-mode
MemorySelectionServiceTest
```

### 阶段 4：前端管理面板

目标：

- 在 `chat.html` 增加长期记忆管理入口。
- 支持列表、筛选、新增、编辑、归档、删除。
- 支持查看默认 index 和本轮 selected detail。

交付：

```text
Long-term Memory UI
API 联调
手动验证报告
```

### 阶段 5：Agent 写入建议

目标：

- Agent 在识别到稳定偏好、项目约束、常用引用时提示用户可写入 memory。
- 用户确认后写入 ACTIVE。
- 用户忽略后不再反复提示同一条建议。

交付：

```text
MemorySuggestionService
suggest / suggest-apply / suggest-dismiss API
MemorySuggestionServiceTest
```

### 阶段 6：整理和导入导出

目标：

- 支持 consolidation preview/apply。
- 支持 Markdown frontmatter 导入/导出。
- 支持从 `AGENTS.md` / 项目文档生成项目级 ACTIVE memory。

交付：

```text
MemoryConsolidationService
Markdown import/export
consolidate-preview / consolidate-apply API
```

## 18. 待确认问题

1. `PROJECT` scope 的 `scope_key` 使用 workspaceId、项目名还是仓库路径。
2. 项目级 seed memory 是否从当前 `AGENTS.md` 生成 import-preview。
3. memory index 默认注入数量上限是否使用 30，还是更保守的 15。
4. LLM detail selection 是否第一阶段就做，还是阶段 3 再做。
5. Agent 写入建议是否展示在聊天流里，还是展示在 Memory 面板待办区。

## 19. 风险控制

主要风险：

- index 过长导致 prompt 噪声。
- detail 注入过多干扰当前任务。
- LLM 选择了不相关 detail。
- 记忆和 RAG / session summary 内容重复。
- 多用户和 workspace 权限隔离不严。
- 整理逻辑误删仍然有效的项目约束。

控制策略：

- index 默认注入，但只注入 name / description / load_hint。
- detail 必须由 LLM 选择后才注入。
- 每轮最多注入 5 条 detail。
- LLM 只能选择已有 memory ID。
- 写入必须由用户确认。
- `DRAFT` 不进入 prompt。
- 所有变更写审计。
- 整理只生成建议，用户确认后 apply。
- 用户可查看、编辑、归档、删除所有自己的长期记忆。

