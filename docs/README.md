# AI Studio 文档导航

这是项目文档的统一入口。当前权威信息优先级：

1. 源码和配置。
2. 根目录 README.md、AGENTS.md、CLAUDE.md。
3. docs 下的当前专题文档。
4. docs/archive 归档材料。

archive 目录只用于追溯历史，不作为当前实现的权威说明。

## 推荐阅读顺序

| 顺序 | 文档 | 说明 |
| --- | --- | --- |
| 1 | [项目总览](../README.md) | 项目能力、启动方式和模块概览。 |
| 2 | 本地 AGENTS.md | AI/Codex 快速上下文，默认不提交到 GitHub。 |
| 3 | [代码审查 Agent 导航](agent/README.md) | 当前主线：代码审查 Agent 转型和阶段状态。 |
| 4 | [混合 RAG 文档导航](RAG/README.md) | RAG 召回、ES、索引和测试报告。 |
| 5 | [工程治理文档](engineering/README.md) | Kafka Outbox、上下文、优化审阅和测试报告。 |
| 6 | [面试材料导航](interview/README.md) | 项目讲解、面试追问和排障场景题。 |

## 目录职责

| 目录 | 职责 |
| --- | --- |
| [agent/](agent/README.md) | 代码审查专家 Agent、工具治理、阶段执行报告。 |
| [RAG/](RAG/README.md) | Hybrid RAG、Elasticsearch 关键词召回、索引优化、召回测试。 |
| [engineering/](engineering/README.md) | 工程治理、Kafka Outbox、上下文一致性、优化路线和实现报告。 |
| [interview/](interview/README.md) | 面试问答、场景题、排障手册。 |
| [archive/legacy-md/](archive/legacy-md/) | 早期教程、学习笔记、阶段材料和历史截图。 |

## 当前主线：代码审查 Agent

当前项目已从泛用 AI 客服系统转型为代码审查专家 Agent。主线文档看：

- [代码审查专家 Agent 转型计划](agent/代码审查专家Agent转型计划.md)
- [阶段 23 执行报告](agent/代码审查专家Agent阶段23执行报告.md)
- [Agent 工具治理说明](agent/tools-guide.md)

当前能力包括：

- 单文件审查。
- 多文件 / workspace 审查。
- Git diff 审查。
- review run / issue 持久化。
- issue card 状态流转。
- Agent 修复草案生成。
- Pending Action 二次确认后修改 workspace 文件。

## 历史归档规则

- 不直接删除历史正文。
- 历史教程和阶段材料放入 archive/legacy-md。
- 求职材料放入本地 career 目录，默认不提交。
- 面试材料放入 interview。
- 当前工程实现说明放入 agent、RAG 或 engineering。
- 如果文档与源码冲突，以源码和当前专题 README 为准。
