# 长期记忆模块执行报告

日期：2026-06-25

依据：

- `docs/plan/long-term-memory-module-plan.md`
- `docs/engineering/long-term-memory-module-design.md`

## 1. 执行结论

长期记忆模块已完成 V1 后端闭环和前端管理入口。

当前实现覆盖：

- MySQL 长期记忆存储和审计表。
- Memory CRUD / archive / delete。
- `ACTIVE` memory index 默认注入上下文。
- `DRAFT` / `ARCHIVED` 不进入 prompt。
- detail selection preview，支持 LLM side-query 和规则 fallback。
- 配置开启后可把 selected detail 注入上下文。
- Agent 系统提示具备“建议用户写入长期记忆”的规则。
- suggest / apply / dismiss API。
- import-preview / import-apply。
- consolidate-preview / consolidate-apply。
- 前端长期记忆管理面板。
- 单测覆盖基础存储、index、prompt、selection、suggestion、import、consolidation、ChatContext 注入顺序、Agent memory 提示规则。

## 2. 新增数据库对象

迁移文件：

```text
chatbot-service/src/main/resources/db/migration/V12__add_long_term_memory_tables.sql
```

新增表：

- `agent_long_term_memory`
- `agent_memory_event`

关键字段：

- `scope_type`：`USER` / `PROJECT` / `SESSION` / `GLOBAL`
- `memory_type`：`user` / `feedback` / `project` / `reference`
- `status`：`ACTIVE` / `DRAFT` / `ARCHIVED`
- `content_hash`：SHA-256，用于去重
- `payload_json`：审计事件和 suggestion 草案载荷

## 3. 后端实现

新增核心路径：

```text
chatbot-service/src/main/java/com/example/chatbot/memory/
chatbot-service/src/main/java/com/example/chatbot/controller/LongTermMemoryController.java
chatbot-service/src/main/java/com/example/chatbot/entity/AgentLongTermMemory.java
chatbot-service/src/main/java/com/example/chatbot/entity/AgentMemoryEvent.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentLongTermMemoryMapper.java
chatbot-service/src/main/java/com/example/chatbot/mapper/AgentMemoryEventMapper.java
```

核心类：

| 类 | 状态 | 说明 |
| --- | --- | --- |
| `LongTermMemoryService` | 已完成 | CRUD、archive、delete、审计、secret 拦截、SHA-256 去重、suggest apply/dismiss |
| `MemoryIndexService` | 已完成 | 加载当前用户和项目 ACTIVE memory index，按安全/project/feedback/user/reference 优先级裁剪 |
| `MemoryPromptBuilder` | 已完成 | 构建 index prompt 和 detail prompt |
| `MemorySelectionService` | 已完成 | 配置开启且模型可用时使用 LLM side-query 选择 detail id，失败时降级规则选择 |
| `MemorySuggestionService` | 已完成 | 识别稳定偏好/项目约束，并向 Agent 注入建议规则 |
| `MemoryImportService` | 已完成 | 从文本或默认 seed 生成 ACTIVE memory 草案，用户 apply 后写入 |
| `MemoryConsolidationService` | V1 已完成 | 基于 content hash 查重，用户确认后归档重复 memory |

## 4. API 实现

基础管理：

```text
GET    /api/chat/memory
GET    /api/chat/memory/{id}
POST   /api/chat/memory
PUT    /api/chat/memory/{id}
POST   /api/chat/memory/{id}/archive
DELETE /api/chat/memory/{id}
```

加载和调试：

```text
POST /api/chat/memory/index-preview
POST /api/chat/memory/select-detail-preview
```

建议写入：

```text
POST /api/chat/memory/suggest
POST /api/chat/memory/suggest/{suggestionId}/apply
POST /api/chat/memory/suggest/{suggestionId}/dismiss
```

导入：

```text
POST /api/chat/memory/import-preview
POST /api/chat/memory/import-apply
```

整理：

```text
POST /api/chat/memory/consolidate-preview
POST /api/chat/memory/consolidate-apply
```

所有接口都从 `AuthInterceptor.AUTH_USER_ID_ATTR` 获取当前用户，不信任前端传入 `userId`。

## 5. 上下文接入

修改文件：

```text
chatbot-service/src/main/java/com/example/chatbot/service/ChatContextService.java
chatbot-service/src/main/java/com/example/chatbot/context/ContextSegmentType.java
chatbot-service/src/main/java/com/example/chatbot/context/ContextCompressionService.java
```

新增 segment：

```text
MEMORY_INDEX
MEMORY_DETAIL
```

当前上下文顺序：

```text
SYSTEM_FIXED
-> MEMORY_INDEX
-> MEMORY_DETAIL（配置开启且选中 detail 时）
-> SESSION_SUMMARY
-> RECENT_HISTORY
-> RAG_CONTEXT
-> CURRENT_USER_INPUT
```

默认行为：

- `app.chatbot.memory.enabled=true`
- `app.chatbot.memory.index-enabled=true`
- `app.chatbot.memory.detail-selection-enabled=false`

也就是说，V1 默认稳定启用 index 注入；detail 注入能力已实现，但默认关闭，避免在没有充分线上观测前改变 prompt 体量。

## 6. Agent 写入建议

修改文件：

```text
chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java
chatbot-service/src/main/java/com/example/chatbot/memory/MemorySuggestionService.java
```

Agent system prompt 已增加长期记忆行为规则：

- 用户表达稳定偏好、反复反馈、项目约束、常用引用时，可以提示适合保存为长期记忆。
- Agent 不允许自己写入 memory。
- 不允许声称已保存，除非用户确认且 memory API 成功。
- 不保存 secret、password、token、`.env` 或一次性任务细节。

对应测试：

```text
chatbot-service/src/test/java/com/example/chatbot/agent/AgentServiceMemoryPromptTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySuggestionServiceTest.java
```

## 7. 前端实现

修改文件：

```text
chatbot-service/src/main/resources/templates/chat.html
```

新增入口：

```text
侧栏 -> 长期记忆
```

当前面板支持：

- Memory 列表。
- 类型筛选。
- 状态筛选。
- 关键词搜索。
- 新增 / 编辑。
- 归档 / 删除。
- Index Preview。
- Detail Preview。
- 从输入生成 memory suggestion。
- 接受 / 忽略 memory suggestion。
- 导入预览 / 确认导入。
- 整理预览 / 确认整理。

## 8. 安全边界

已实现：

- Controller 不接受前端 `userId`。
- 查询、详情、更新、归档、删除按当前用户隔离。
- `DRAFT` / `ARCHIVED` 不进入 index。
- 写入前执行 secret pattern 检查。
- `content_hash` 使用 SHA-256。
- 所有写入、更新、归档、删除、建议 apply/dismiss 写审计事件。
- 长期记忆只影响 prompt，不触发 shell、Git、workspace 文件写入。
- Agent 只能建议写入 memory，用户确认后才通过 API 创建 memory。

当前保守处理：

- `PROJECT` scope V1 使用 `scope_key=springaI-chatbot`。
- 多 workspace 权限校验尚未接入独立 workspace 权限表，后续多项目化时必须补上。

## 9. 测试覆盖

新增或增补测试：

```text
chatbot-service/src/test/java/com/example/chatbot/memory/LongTermMemoryServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryIndexServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryPromptBuilderTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySuggestionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemorySelectionServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryImportServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/memory/MemoryConsolidationServiceTest.java
chatbot-service/src/test/java/com/example/chatbot/agent/AgentServiceMemoryPromptTest.java
chatbot-service/src/test/java/com/example/chatbot/service/ChatContextServiceTest.java
```

覆盖点：

- 创建 memory 使用当前用户并写审计。
- secret 内容拒绝写入。
- USER scope 强制使用当前用户 id 作为 `scope_key`。
- archive 只操作当前用户 memory。
- project 安全边界类 memory 在 index 中优先。
- index prompt 不泄露 detail content。
- detail prompt 包含 memory id 和 content。
- detail selection 只从 index 选择 id。
- import preview 生成 ACTIVE 项目级草案。
- consolidation preview 查找重复 content hash。
- consolidation apply 只归档用户确认的重复项。
- Agent prompt 包含 memory suggestion 规则，且禁止自动写入。
- `ChatContextService` 将 memory index 注入到 system prompt 后、session summary 前。

## 10. 验证结果

已执行：

```bash
mvn -q -pl chatbot-service "-Dtest=LongTermMemoryServiceTest,MemoryIndexServiceTest,MemoryPromptBuilderTest,MemorySuggestionServiceTest,MemorySelectionServiceTest,MemoryImportServiceTest,MemoryConsolidationServiceTest,AgentServiceMemoryPromptTest,ChatContextServiceTest" test
```

结果：通过。

已执行：

```bash
mvn -q -pl chatbot-service -DskipTests package
```

结果：通过。

## 11. 当前限制

1. `detail-selection-enabled` 默认关闭。上线前建议先通过 `select-detail-preview` 观察 LLM 选择质量，再开启。
2. `PROJECT` scope 当前使用固定 `springaI-chatbot`，多 workspace 化后需要接入 workspaceId/projectKey 权限校验。
3. consolidation V1 只处理 content hash 重复归档，不做语义合并、拆分或冲突解决。
4. import V1 从文本行生成草案，还没有 Markdown frontmatter 完整解析。
5. 前端使用现有 modal/card 风格完成管理面板，后续可继续细化为正式审计历史页和 diff-like 导入确认页。

## 12. 后续建议

优先级建议：

1. 增加 `agent_memory_event` 历史审计页。
2. 给 `PROJECT` scope 接入 workspace 权限校验。
3. 对 import 增加 Markdown frontmatter 解析。
4. 增加 memory token/命中观测，记录每轮 index/detail 注入数量和字符数。
5. 为 LLM side-query 增加独立 selection 模型配置、超时和调用指标。
