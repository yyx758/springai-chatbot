# Claude Code Skills 安装指南

## 概述

Claude Code Skills 是可扩展的插件系统，可以为 Claude 添加特定领域的能力，如 UI/UX 设计、代码生成等。

## 安装流程

### 第一步：添加 Marketplace（插件市场）

首先需要添加插件市场的源：

```bash
claude plugin marketplace add <marketplace-name>
```

**示例：**
```bash
claude plugin marketplace add nextlevelbuilder/ui-ux-pro-max-skill
```

这个命令会从 GitHub 克隆市场仓库到本地。

---

### 第二步：更新 Marketplace 缓存

添加市场后，建议更新缓存以获取最新插件列表：

```bash
claude plugin marketplace update <marketplace-name>
```

**示例：**
```bash
claude plugin marketplace update ui-ux-pro-max-skill
```

---

### 第三步：安装插件

```bash
claude plugin install <plugin-name>@<marketplace-name>
```

**示例：**
```bash
claude plugin install ui-ux-pro-max@ui-ux-pro-max-skill
```

---

## 常见问题及解决方案

### 问题 1：插件名称与市场名称混淆

**错误现象：**
```
✘ Failed to install plugin "ui-ux-pro-max-skill@ui-ux-pro-max-skill":
Plugin "ui-ux-pro-max-skill" not found in marketplace "ui-ux-pro-max-skill"
```

**原因分析：**
- 市场名称（marketplace）和插件名称（plugin）是**不同的**
- 市场是一个仓库，里面包含多个插件
- 插件名称需要在市场的 `skill.json` 或 `.claude/skills/` 目录中查找

**解决方案：**

1. 查看市场的插件列表：
```bash
ls -la ~/.claude/plugins/marketplaces/<marketplace-name>/.claude/skills/
```

2. 或者查看 `skill.json` 文件获取正确的插件名称：
```bash
cat ~/.claude/plugins/marketplaces/<marketplace-name>/skill.json
```

3. 使用正确的插件名称安装：
```bash
claude plugin install <正确的插件名>@<市场名>
```

**实际案例：**
- 市场名称：`ui-ux-pro-max-skill`
- 插件名称：`ui-ux-pro-max`（注意没有 `-skill` 后缀）
- 正确命令：`claude plugin install ui-ux-pro-max@ui-ux-pro-max-skill`

---

### 问题 2：本地缓存过期

**错误现象：**
```
Your local copy may be out of date — try `claude plugin marketplace update <name>`
```

**解决方案：**
```bash
claude plugin marketplace update <marketplace-name>
```

---

### 问题 3：找不到已添加的市场

**查看已添加的市场：**
```bash
claude plugin marketplace list
```

---

## 常用命令汇总

| 命令 | 说明 |
|------|------|
| `claude plugin marketplace add <name>` | 添加插件市场 |
| `claude plugin marketplace update <name>` | 更新市场缓存 |
| `claude plugin marketplace list` | 列出所有已添加的市场 |
| `claude plugin install <plugin>@<marketplace>` | 安装指定插件 |
| `claude plugin list` | 列出已安装的插件 |

---

## 重要提示

1. **市场名称 ≠ 插件名称**：市场是一个仓库，插件是仓库中的具体技能
2. **安装前先更新**：添加市场后建议先执行 `update` 命令
3. **查看 skill.json**：不确定插件名称时，查看市场根目录的 `skill.json` 文件
4. **插件路径**：市场文件存储在 `~/.claude/plugins/marketplaces/` 目录下

---

## 安装验证

安装成功后，可以通过以下方式验证：

```bash
# 查看已安装的插件
claude plugin list

# 或者直接在 Claude Code 中使用斜杠命令调用
/ui-ux-pro-max
```

---

## 参考资源

- 官方文档：https://claude.ai/docs
- Claude Code GitHub：https://github.com/anthropics/claude-code
