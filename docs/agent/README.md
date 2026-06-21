# 代码审查 Agent 文档导航

本目录保存“代码审查专家 Agent”转型相关文档。当前已完成到阶段 23。

## 当前权威文档

| 文档 | 说明 |
| --- | --- |
| [代码审查专家 Agent 转型计划](代码审查专家Agent转型计划.md) | 总体定位、架构、数据结构、阶段计划和验收标准。 |
| [阶段 23 执行报告](代码审查专家Agent阶段23执行报告.md) | 当前最新状态：Agent 修复模式、full-file replacementContent、diff 预览、Pending Action 确认。 |
| [Agent 工具治理说明](tools-guide.md) | Agent 工具调用、审计、风险分级、确认流和安全边界。 |

## 阶段索引

| 阶段 | 主题 | 文档 |
| --- | --- | --- |
| 1 | 单文件只读审查 MVP | [阶段 1](代码审查专家Agent阶段1执行报告.md) |
| 2 | 多文件 / workspace 审查 | [阶段 2](代码审查专家Agent阶段2执行报告.md) |
| 3 | 显式 Agent Runtime | [阶段 3](代码审查专家Agent阶段3执行报告.md) |
| 4 | Git diff / PR 风格只读审查 | [阶段 4](代码审查专家Agent阶段4执行报告.md) |
| 5 | Patch 建议文件生成 | [阶段 5](代码审查专家Agent阶段5执行报告.md) |
| 6 | Pending Action 应用 workspace patch | [阶段 6](代码审查专家Agent阶段6执行报告.md) |
| 7 | review run / issue 持久化 | [阶段 7](代码审查专家Agent阶段7执行报告.md) |
| 8 | 前端 issue card 展示 | [阶段 8](代码审查专家Agent阶段8执行报告.md) |
| 9 | issue 状态流转 | [阶段 9](代码审查专家Agent阶段9执行报告.md) |
| 10 | issue 筛选与建议文件绑定 issueId | [阶段 10](代码审查专家Agent阶段10执行报告.md) |
| 11 | patch preview 和 replacementContent 草案 | [阶段 11](代码审查专家Agent阶段11执行报告.md) |
| 12 | Pending Apply Actions 列表与确认 | [阶段 12](代码审查专家Agent阶段12执行报告.md) |
| 13 | Pending Action 取消 | [阶段 13](代码审查专家Agent阶段13执行报告.md) |
| 14 | 前端 Start Review 入口 | [阶段 14](代码审查专家Agent阶段14执行报告.md) |
| 15 | workspace 文件选择器 | [阶段 15](代码审查专家Agent阶段15执行报告.md) |
| 16 | Git changed files 选择审查 | [阶段 16](代码审查专家Agent阶段16执行报告.md) |
| 17 | Git diff 新增/删除行数摘要 | [阶段 17](代码审查专家Agent阶段17执行报告.md) |
| 18 | Git diff 文件级预览 | [阶段 18](代码审查专家Agent阶段18执行报告.md) |
| 19 | diff 轻量高亮 | [阶段 19](代码审查专家Agent阶段19执行报告.md) |
| 20 | 复制 diff / 审查当前预览文件 | [阶段 20](代码审查专家Agent阶段20执行报告.md) |
| 21 | 本地项目同步过滤依赖目录 | [阶段 21](代码审查专家Agent阶段21执行报告.md) |
| 22 | 修复流程文案和 JSON 错误处理 | [阶段 22](代码审查专家Agent阶段22执行报告.md) |
| 23 | Agent 修复完整文件草案 | [阶段 23](代码审查专家Agent阶段23执行报告.md) |

## 当前安全边界

- 审查可以自动执行。
- patch preview 只读。
- apply-request 只创建 Pending Action。
- confirm 后才写 workspace 文件。
- 不自动修改真实 Git 工作区。
- 不自动 commit / push。

## 后续优先方向

1. 用正式 diff modal 替代浏览器 confirm。
2. Pending Action 增加状态筛选、详情页和历史审计。
3. 增加 eval harness，评估审查准确率和误报率。
4. 增强 Agent 修复结果校验，避免无关大改。
