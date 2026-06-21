# 代码审查专家 Agent 阶段 22 执行报告

## 1. 本阶段目标

本阶段目标是修正 issue 修复流程的交互误导，并修复前端把 HTML 错误页当 JSON 解析导致的 `Unexpected token '<'` 报错。

## 2. 交互修正

原按钮：

- 生成建议文件
- 预览申请应用

容易让用户误解“生成建议文件”会修改源码。实际它只是把建议保存为 workspace markdown。

本阶段改为：

- 保存建议说明：只保存说明文档，不修改源码。
- 预览并申请修复：用于生成 replacementContent 预览并创建 Pending Action。

保存建议说明成功提示也明确说明：

- 文件保存到当前会话 workspace。
- 这只是说明文档。
- 要改源码请使用“预览并申请修复”。

## 3. 错误处理修正

新增前端函数：

    readJsonResponse(response, fallbackMessage)

作用：

- 先读取 response.text()。
- 尝试 JSON.parse。
- 如果服务端返回 HTML、登录页或错误页，给出明确 non-JSON response 错误。
- 如果 HTTP 非 2xx 或 success=false，显示后端 error/message。

已覆盖：

- Start Review 发起审查。
- Git diff 文件加载。
- Git diff preview。
- 保存建议说明。
- 预览并申请修复。
- 创建 apply-request。

## 4. 正确修复流程

用户应该按这个流程修改内容：

1. 完成审查。
2. 在 Issue Cards 中选择要修复的 issue。
3. 点击“预览并申请修复”。
4. 查看 diff/警告。
5. 确认后创建 Pending Action。
6. 在 Pending Apply Actions 中点击确认执行。
7. workspace 文件才会被修改。

“保存建议说明”只是保存 markdown 说明文件，不修改源码。

## 5. 验证结果

本阶段复验命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0
