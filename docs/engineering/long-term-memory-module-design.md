# AI Studio 长期记忆模块设计

日期：2026-06-25

依据文档：

- `docs/plan/long-term-memory-module-plan.md`
- `docs/engineering/context-compression-module-design.md`

## 1. 设计结论

长期记忆模块用于保存跨 session 生效的稳定信息，包括用户协作偏好、项目长期约束、反复反馈和常用引用。它不是会话摘要，也不是 RAG 文档库，更不是模型自动抽取后直接写入的事实库。

V1 采用显式写入、默认索引注入、按需详情加载的结构：

```text
用户确认写入 memory
-> MySQL 保存 ACTIVE / DRAFT / ARCHIVED memory
-> 每轮默认加载 ACTIVE memory index
-> LLM 只根据 index 选择需要的 detail id
-> 系统按 id 加载 detail 注入本轮上下文
```

核心边界：

- 不自动写入长期记忆。
- Agent 只能建议写入，用户确认后才入库。
- `ACTIVE` 默认进入 index，`DRAFT` 和 `ARCHIVED` 不进入 prompt。
- detail 不全量注入，必须经选择后按上限注入。
- 长期记忆不触发 shell、Git 写入、workspace 文件写入或 Pending Action 绕过。

## 2. 与现有上下文体系的关系

当前上下文顺序将调整为：

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

职责边界：

| 模块 | 生命周期 | 内容 | 是否默认注入 |
| --- | --- | --- | --- |
| Redis recent history | 当前 session，短 TTL | 最近对话 | 是 |
| `chat_session_summary` | 当前 session | 滚动摘要 | 是 |
| RAG | 当前请求 | 知识库检索片段 | 按请求 |
| `agent_long_term_memory` index | 跨 session | name、description、loadHint | 是 |
| `agent_long_term_memory` detail | 跨 session | 完整约束、偏好、引用说明 | 选择后 |

`ChatContextService` 仍负责收集上下文段；长期记忆模块只提供可注入的 memory segment。压缩和预算仍由 `ContextCompressionService` 统一处理。

## 3. V1 决策

计划文档第 18 节的待确认问题在 V1 中按以下方式落定：

| 问题 | V1 决策 |
| --- | --- |
| `PROJECT` scope 的 `scope_key` | 使用稳定项目键。当前本地项目默认 `springaI-chatbot`；后续有 workspace 表后迁移为 workspaceId，并保留 projectKey 别名 |
| 项目级 seed memory 来源 | 从 `AGENTS.md` / 当前会话注入的项目说明生成 `import-preview`，用户确认后写入 `ACTIVE` |
| memory index 默认上限 | 默认 `30` 条、`6000` 字符；超限时按安全边界、project、feedback、user、reference 优先级裁剪 |
| LLM detail selection 阶段 | 阶段 3 实现；阶段 1-2 先提供 index 注入和手动 detail preview |
| Agent 写入建议展示位置 | 聊天流展示轻量提示，Memory 面板展示待处理建议列表 |

## 4. 数据模型

### 4.1 agent_long_term_memory

迁移文件：

```text
chatbot-service/src/main/resources/db/migration/V12__add_long_term_memory_tables.sql
```

字段：

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT,
user_id BIGINT NOT NULL,
scope_type VARCHAR(32) NOT NULL,
scope_key VARCHAR(255) NOT NULL,
memory_type VARCHAR(32) NOT NULL,
name VARCHAR(128) NOT NULL,
description VARCHAR(512) NOT NULL,
content TEXT NOT NULL,
load_hint VARCHAR(512) NULL,
source_type VARCHAR(32) NOT NULL,
status VARCHAR(32) NOT NULL,
created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
last_used_time DATETIME NULL,
use_count BIGINT NOT NULL DEFAULT 0,
content_hash VARCHAR(64) NULL
```

枚举：

```text
scope_type: USER / PROJECT / SESSION / GLOBAL
memory_type: user / feedback / project / reference
source_type: MANUAL / IMPORTED / SYSTEM / AGENT_SUGGESTED
status: ACTIVE / DRAFT / ARCHIVED
```

索引：

```sql
CREATE INDEX idx_memory_user_scope_status
  ON agent_long_term_memory(user_id, scope_type, scope_key, status);

CREATE INDEX idx_memory_user_type_status
  ON agent_long_term_memory(user_id, memory_type, status);

CREATE INDEX idx_memory_hash
  ON agent_long_term_memory(user_id, content_hash);

CREATE INDEX idx_memory_updated_time
  ON agent_long_term_memory(updated_time);
```

约束说明：

- `user_id` 始终来自 `AuthInterceptor` 注入的当前用户。
- `GLOBAL` scope 第一版只允许系统初始化，不开放普通用户写入。
- `content_hash` 使用规范化 content 后的 SHA-256，用于去重。
- `DELETE` V1 可做硬删除，但必须写审计事件；更保守实现可先做逻辑归档。

### 4.2 agent_memory_event

用途：记录写入、更新、归档、删除、导入、加载和整理行为，便于回溯。

```sql
id BIGINT PRIMARY KEY AUTO_INCREMENT,
memory_id BIGINT NULL,
user_id BIGINT NOT NULL,
event_type VARCHAR(32) NOT NULL,
source_session_id VARCHAR(255) NULL,
source_record_id BIGINT NULL,
payload_json JSON NULL,
created_time DATETIME DEFAULT CURRENT_TIMESTAMP
```

事件：

```text
CREATE
UPDATE
ARCHIVE
DELETE
IMPORT
SUGGEST
SUGGEST_APPLY
SUGGEST_DISMISS
LOAD_INDEX
LOAD_DETAIL
CONSOLIDATE_PREVIEW
CONSOLIDATE_APPLY
```

## 5. 后端模块

新增包：

```text
chatbot-service/src/main/java/com/example/chatbot/memory/
```

核心类：

| 类 | 职责 |
| --- | --- |
| `LongTermMemoryService` | CRUD、状态变更、去重、secret 过滤、审计 |
| `MemoryIndexService` | 加载当前用户和项目的 ACTIVE index，执行排序和裁剪 |
| `MemoryPromptBuilder` | 构造 `MEMORY_INDEX` 和 `MEMORY_DETAIL` prompt 文本 |
| `MemorySelectionService` | 阶段 3 调 LLM 从 index 中选择 detail id |
| `MemorySuggestionService` | 阶段 5 生成和处理写入建议 |
| `MemoryConsolidationService` | 阶段 6 生成合并、归档、拆分建议 |
| `MemoryProperties` | 读取 `app.chatbot.memory` 配置 |

实体和 Mapper：

```text
chatbot-service/src/main/java/com/example/chatbot/entity/AgentLongTermMemory.java
chatbot-service/src/main/java/com/example/chatbot/entity/AgentMemoryEvent.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentLongTermMemoryMapper.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentMemoryEventMapper.java
```

Controller：

```text
chatbot-service/src/main/java/com/example/chatbot/controller/LongTermMemoryController.java
```

## 6. API 契约

所有接口前缀：

```text
/api/chat/memory
```

所有接口必须从当前请求上下文获取 `userId`，请求体不允许传入或覆盖 `userId`。

### 6.1 管理接口

```text
GET    /api/chat/memory
GET    /api/chat/memory/{id}
POST   /api/chat/memory
PUT    /api/chat/memory/{id}
POST   /api/chat/memory/{id}/archive
DELETE /api/chat/memory/{id}
```

列表查询参数：

```text
scopeType
scopeKey
memoryType
status
keyword
limit
offset
```

创建请求：

```json
{
  "scopeType": "PROJECT",
  "scopeKey": "springaI-chatbot",
  "memoryType": "project",
  "name": "Gateway production entry",
  "description": "Production user traffic must go through Gateway :9000.",
  "content": "Production environment user entry must go through Gateway :9000. Do not expose chatbot-service:8080 or file-service:8081 to host ports.",
  "loadHint": "Load when changing deployment, routes, frontend API paths, or service ports.",
  "sourceType": "MANUAL",
  "status": "ACTIVE"
}
```

返回对象：

```json
{
  "id": 18,
  "scopeType": "PROJECT",
  "scopeKey": "springaI-chatbot",
  "memoryType": "project",
  "name": "Gateway production entry",
  "description": "Production user traffic must go through Gateway :9000.",
  "content": "Production environment user entry must go through Gateway :9000. Do not expose chatbot-service:8080 or file-service:8081 to host ports.",
  "loadHint": "Load when changing deployment, routes, frontend API paths, or service ports.",
  "sourceType": "MANUAL",
  "status": "ACTIVE",
  "lastUsedTime": null,
  "useCount": 0,
  "createdTime": "2026-06-25T21:30:00",
  "updatedTime": "2026-06-25T21:30:00"
}
```

### 6.2 加载预览接口

```text
POST /api/chat/memory/index-preview
POST /api/chat/memory/select-detail-preview
```

`index-preview` 用于前端展示当前请求会默认注入哪些 index，不改变 `last_used_time` 和 `use_count`。

`select-detail-preview` 阶段 3 启用，用于调试 detail selection。解析失败时返回空 selectedIds，并标记 fallback。

### 6.3 建议写入接口

```text
POST /api/chat/memory/suggest
POST /api/chat/memory/suggest/{suggestionId}/apply
POST /api/chat/memory/suggest/{suggestionId}/dismiss
```

V1 阶段 5 才实现。建议本身可先落在 `agent_memory_event`，`payload_json` 保存草案和指纹。用户 apply 后才创建 `agent_long_term_memory`。

### 6.4 导入和整理接口

```text
POST /api/chat/memory/import-preview
POST /api/chat/memory/import-apply
POST /api/chat/memory/consolidate-preview
POST /api/chat/memory/consolidate-apply
```

导入和整理只生成草案，apply 必须由用户显式触发。

## 7. 加载和排序策略

### 7.1 Index 加载范围

默认加载：

```text
USER scope: scope_key = 当前 userId
PROJECT scope: scope_key = 当前 projectKey，当前项目为 springaI-chatbot
GLOBAL scope: 仅系统预置且用户可见的 ACTIVE memory
```

不加载：

```text
DRAFT
ARCHIVED
其他用户 memory
无权限 project memory
```

### 7.2 Index 排序

排序权重：

```text
1. 安全边界类 project memory
2. 当前 project memory
3. 用户反复反馈 feedback
4. 用户协作偏好 user
5. 常用引用 reference
6. 更新时间较新的 memory
```

V1 可通过 `memory_type` 和关键词启发式实现，不引入复杂打分模型。

### 7.3 Detail 选择

阶段 3 的 `MemorySelectionService` 输入只包含：

```text
id
memoryType
scopeType
name
description
loadHint
```

模型输出：

```json
{
  "selectedIds": [12, 18],
  "reason": "The current task touches response style and production gateway constraints."
}
```

硬约束：

- 只接受 index 中存在的 id。
- 最多 `max-selected-details` 条。
- 每条 detail 最多 `max-detail-chars-per-item` 字符。
- detail 总量最多 `max-total-detail-chars` 字符。
- JSON 解析失败、id 不存在或模型超时时，只注入 index，不注入 detail。

## 8. Prompt 格式

`MEMORY_INDEX`：

```text
Long-term memory index:
- id=12 scope=USER type=feedback name="Response style"
  description="User prefers direct implementation, verification, and concise summaries."
  load_hint="Load when deciding response style or task execution approach."
- id=18 scope=PROJECT type=project name="Gateway production entry"
  description="Production user traffic must go through Gateway :9000."
  load_hint="Load when changing deployment, routes, frontend API paths, or service ports."
```

`MEMORY_DETAIL`：

```text
Loaded long-term memory detail:
[memory id=18 scope=PROJECT type=project]
Production environment user entry must go through Gateway :9000. Do not expose chatbot-service:8080 or file-service:8081 to host ports.
```

要求：

- index 中不输出 `content`。
- detail 中必须带 memory id，方便审计和调试。
- 不把 `DRAFT` / `ARCHIVED` 记忆输出到 prompt。
- prompt 文本不包含密钥、token、邮箱授权码或 `.env` 内容。

## 9. 安全和权限

### 9.1 用户隔离

所有 Mapper 查询必须包含 `user_id = currentUserId`，除非读取系统 `GLOBAL` memory。Controller 禁止信任前端传入 `userId`。

### 9.2 Project scope 权限

V1 当前项目先用 `scope_key=springaI-chatbot`。后续接入多 workspace 后，必须在读写 `PROJECT` memory 前检查当前用户对 workspace/project 的访问权限。

### 9.3 Secret 过滤

创建、更新、导入和建议 apply 前执行 secret pattern 检查。至少拦截：

```text
API key
Bearer token
password
邮箱授权码
private key
.env 原文
```

命中后拒绝写入，并返回明确错误，不保存到 memory content。

### 9.4 行为边界

长期记忆只影响 prompt 上下文，不允许：

- 执行 shell。
- 写 workspace 文件。
- 执行 Git commit / push / reset。
- 创建或确认 Pending Action。
- 绕过代码审查 Agent 的二次确认流程。

## 10. 配置

默认配置：

```yaml
app:
  chatbot:
    memory:
      enabled: true
      index-enabled: true
      detail-selection-enabled: false
      select-mode: LLM_SIDE_QUERY
      max-index-items: 30
      max-index-chars: 6000
      max-selected-details: 5
      max-detail-chars-per-item: 4096
      max-total-detail-chars: 12000
      suggestion-enabled: false
      consolidation-enabled: false
      consolidation-min-items: 10
```

说明：

- 阶段 1-2：`detail-selection-enabled=false`，先保证基础存储和 index 注入稳定。
- 阶段 3：开启 `detail-selection-enabled=true`。
- 阶段 5：开启 `suggestion-enabled=true`。
- 整理能力默认关闭，只在用户主动触发或后续明确开启。

## 11. 前端设计

入口：

```text
chatbot-service/src/main/resources/templates/chat.html
侧栏：长期记忆
```

V1 面板包含：

- 列表：name、description、scope、type、status、updatedTime、useCount。
- 筛选：scopeType、memoryType、status、keyword。
- 新增和编辑表单。
- 查看 detail。
- 归档和删除，需确认。
- Index Preview。
- Selected Detail Preview，阶段 3 后启用。
- Memory Suggestion 待办区，阶段 5 后启用。
- Import Preview，从 `AGENTS.md` / Markdown 生成草案，阶段 6 后启用。

前端不展示其他用户 memory，不发送 `userId`。

## 12. 种子记忆

当前项目建议从 `AGENTS.md` 生成 import-preview，由用户确认后写入 `PROJECT scope`、`scope_key=springaI-chatbot`、`status=ACTIVE`。

第一批建议：

| type | name | description |
| --- | --- | --- |
| project | Code review Agent positioning | 当前系统是受控的工程化代码审查 Agent，不是泛用聊天助手 |
| project | Gateway production entry | 生产环境用户入口只走 Gateway :9000 |
| project | Internal service ports | chatbot-service:8080 和 file-service:8081 不应暴露给宿主机 |
| project | Read-only Git review | GitReviewService 只能做只读 Git 操作 |
| project | Pending Action boundary | 真正修改 workspace 文件必须经过 Pending Action 二次确认 |
| project | No automatic repository mutation | 不自动 commit / push / 操作真实 Git 仓库 |
| feedback | User collaboration preference | 用户喜欢直接推进，实现、验证、总结，不希望只停留在建议层面 |
| reference | Agent docs location | 代码审查阶段文档位于 docs/agent/ |
| reference | Review controller | 代码审查核心 Controller 是 CodeReviewAgentController |

## 13. 测试计划

新增测试：

```text
chatbot-service/src/test/java/com/example/chatbot/memory/LongTermMemoryServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryIndexServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryPromptBuilderTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySelectionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySuggestionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/service/ChatContextServiceTest.java
```

阶段 1-2 必须覆盖：

- 用户 A 不能读取用户 B 的 memory。
- 创建、更新、归档、删除都会写审计事件。
- `ACTIVE` 进入 index。
- `DRAFT` 和 `ARCHIVED` 不进入 index。
- index 超过 `max-index-items` / `max-index-chars` 时正确裁剪。
- `MemoryPromptBuilder` 不输出 detail content 到 index。
- `ChatContextService` 将 memory 放在 system prompt 后、session summary 前。
- secret pattern 命中时拒绝写入。

阶段 3 后补充：

- detail selection 只能选择已有 id。
- 超过上限时裁剪 selected ids。
- 模型返回非法 JSON 时降级为只注入 index。
- detail 注入会更新 `last_used_time` 和 `use_count`。

验证命令：

```bash
mvn -q -pl chatbot-service "-Dtest=LongTermMemoryServiceTest,MemoryIndexServiceTest,MemoryPromptBuilderTest,MemorySelectionServiceTest,ChatContextServiceTest" test
```

## 14. 分阶段交付

### 阶段 1：基础存储和管理 API

交付：

- `V12__add_long_term_memory_tables.sql`
- entity / mapper / enum
- `LongTermMemoryService`
- `LongTermMemoryController`
- CRUD、归档、删除、审计、secret 过滤
- `LongTermMemoryServiceTest`

不做：

- LLM detail selection
- Agent 自动建议
- consolidation

### 阶段 2：默认 index 注入上下文

交付：

- `MemoryIndexService`
- `MemoryPromptBuilder`
- `ChatContextService` 接入
- `ContextSegmentType` 增加 `MEMORY_INDEX` / `MEMORY_DETAIL`，或复用 `USER_MEMORY` 并设置 `sourceRef`
- `MemoryIndexServiceTest`
- `MemoryPromptBuilderTest`
- `ChatContextServiceTest` 增补

验收：

- 每轮 prompt 默认包含当前用户和当前项目的 ACTIVE memory index。
- 长期记忆位于 system prompt 后、session summary 前。

### 阶段 3：LLM detail selection

交付：

- `MemorySelectionService`
- `select-detail-preview`
- detail 加载、裁剪、审计、use_count 更新
- `MemorySelectionServiceTest`

验收：

- 模型只从 index 选择 id。
- 失败降级不影响主聊天。

### 阶段 4：前端管理面板

交付：

- `chat.html` 长期记忆入口
- 列表、筛选、新增、编辑、归档、删除
- index preview 和 selected detail preview
- 手动验证报告

### 阶段 5：Agent 写入建议

交付：

- `MemorySuggestionService`
- suggest / apply / dismiss API
- 聊天流轻提示
- Memory 面板待处理建议

验收：

- Agent 不直接写入 memory。
- 用户 apply 后才创建 ACTIVE memory。
- dismiss 后同一指纹不重复提示。

### 阶段 6：导入、导出和整理

交付：

- import-preview / import-apply
- Markdown frontmatter 导入导出
- consolidation-preview / consolidation-apply

验收：

- 从 `AGENTS.md` 生成草案必须经用户确认。
- consolidation 不自动覆盖、合并或删除 memory。

## 15. 验收标准

功能验收：

- 用户可管理自己的长期记忆。
- ACTIVE memory index 每轮默认注入。
- detail 按需注入，不全量塞入 prompt。
- 用户可从 `AGENTS.md` 生成项目级 seed memory 草案并确认写入。

安全验收：

- 所有接口按当前用户隔离。
- 前端不能通过传 `userId` 访问其他用户 memory。
- `DRAFT` / `ARCHIVED` 不进入 prompt。
- secret 内容不能写入 memory。
- 长期记忆不会触发真实文件、Git 或 shell 写操作。

工程验收：

- 新增核心单测通过。
- `ChatContextService` 既有测试通过。
- 配置可关闭整个 memory 模块。
- 模型 detail selection 失败不影响主对话链路。

## 16. 当前实现优先级

建议下一步直接进入阶段 1 和阶段 2，先把“显式写入 + 默认 index 注入”闭环做完。LLM detail selection、Agent 建议写入和 consolidation 都属于增强能力，应在基础权限、审计、上下文顺序稳定后再接入。
