# 代码审查专家 Agent 阶段 21 执行报告

## 1. 本阶段目标

本阶段目标是修复本地项目同步时依赖目录挤占 workspace 的问题。用户上传项目后只看到 `.venv/Lib`，说明虚拟环境目录没有被过滤，并占用了同步文件上限。

## 2. 问题原因

前端本地文件夹同步逻辑原先只跳过：

- .git
- .idea
- .vscode
- node_modules
- target
- dist
- build
- .gradle

没有跳过：

- .venv
- venv
- env
- __pycache__
- site-packages

因此 Python 项目中的虚拟环境依赖文件会被当作普通项目文件同步。因为同步上限是 300 个文件，依赖目录可能把源码文件挤掉。

## 3. 修复内容

新增源码优先策略：

- Java/Python/JS/TS/Go/Rust/C/C++ 等源码扩展优先。
- pom.xml、package.json、requirements.txt、pyproject.toml 等项目配置次优先。
- README 再次优先。
- 其他文本文件最后。

新增跳过目录：

- .venv
- venv
- env
- .env
- __pycache__
- .mypy_cache
- .pytest_cache
- .ruff_cache
- .tox
- site-packages
- vendor
- coverage
- .next
- .nuxt

新增候选扫描上限：

- 最多收集 1500 个候选文件。
- 排序后只同步前 300 个。

这样即使项目里有部分非源码目录，也会优先保留源码和项目配置。

## 4. 验证结果

本阶段复验命令：

    mvn -q -pl chatbot-service -DskipTests package

结果：

    Exit code: 0

## 5. 当前限制

- 过滤逻辑在前端执行。
- 仍然保留 300 个同步文件上限。
- 如果项目源码本身超过 300 个文件，只会同步优先级最高的前 300 个。
- 未来可增加用户可配置 ignore pattern。
