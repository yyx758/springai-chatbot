# 代码审查专家 Agent 阶段 20 执行报告

## 1. 本阶段目标

本阶段目标是把 Git diff preview 从只读展示升级为可操作面板，让用户能复制 diff，并直接审查当前预览文件。

## 2. 前端改动

Git diff preview 区域新增按钮：

- 复制 Diff
- 审查当前预览文件

新增状态：

- activeGitDiffPreviewPath
- activeGitDiffPreviewText

选择 changed file 后会缓存当前预览文件路径和原始 diff 文本。

## 3. 使用方式

1. Start Review 选择 Git diff 审查。
2. 点击加载变更。
3. 选择某个 changed file。
4. 查看高亮 diff preview。
5. 可点击复制 Diff。
6. 可点击审查当前预览文件，系统只审查当前文件。

## 4. 验证结果

本阶段复验命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 5. 当前限制

- 审查当前预览文件仍复用 Git diff 审查接口。
- 多选时当前预览文件为第一个选中文件。
- 复制 Diff 依赖浏览器剪贴板权限，失败时会 fallback 到 alert 展示文本。

## 6. 下一阶段建议

下一阶段建议做正式 review panel：

1. 将 changed files、diff preview、审查按钮统一为更清晰的两栏布局。
2. 增加当前预览文件标识。
3. 支持在多个选中文件之间切换预览。
