# AI Agent 升级阶段 5B：本地文件夹工作区桥接报告

## 目标

让用户可以在聊天页选择本机文件夹作为当前对话工作区来源，使 Agent 能读取、修改、新建工作区文件，并在用户授权后写回本地文件夹。

## 实现方式

- 前端新增本地文件夹入口，优先使用浏览器 File System Access API：`showDirectoryPicker({ mode: 'readwrite' })`。
- 用户选择文件夹后，浏览器只读取白名单文本文件，并同步到后端当前对话 Workspace。
- Agent 仍然只通过 `createWorkspaceFile`、`readWorkspaceFile`、`updateWorkspaceFile` 等受控工具操作服务端 Workspace。
- 用户点击 `Local` 或 `Sync to Local` 时，浏览器把 Workspace 文件写回用户授权的同一个本地文件夹。
- 后端不会接收、保存或扫描用户本地绝对路径，只处理 Workspace 相对路径和文本内容。

## 安全约束

- 本地文件夹必须由用户主动选择，Agent 和服务器不能主动打开本地目录。
- 同步范围限制为文本类扩展名：`.md`、`.txt`、`.json`、`.csv`、`.html`、`.css`、`.js`、`.ts`、`.java`、`.xml`、`.yml`、`.yaml`、`.properties`。
- 单文件最大 2MB，最多同步 100 个文件，递归深度最大 6。
- 自动跳过 `.git`、`.idea`、`.vscode`、`node_modules`、`target`、`dist`、`build`、`.gradle`。
- 写回本地时拒绝绝对路径、`..`、`.`、包含冒号的路径，防止路径穿越。
- 批量写回只写入用户已经授权的目录，不允许写到任意本地路径。

## 部署限制

File System Access API 通常要求 HTTPS 或 localhost 安全上下文。当前生产环境通过 `http://111.229.127.171:9000` 访问时，浏览器可能只支持目录只读导入兜底，不能直接写回本地。

要完整启用“写回本地/本地新建文件”，建议后续二选一：

1. 给 Gateway 配置 HTTPS 域名和证书。
2. 做桌面客户端或浏览器扩展，把本地文件系统权限交给客户端承载。

## 本次改动

- `chatbot-service/src/main/resources/templates/chat.html`
  - 新增本地文件夹按钮。
  - 新增目录扫描、白名单过滤、同步到 Workspace、写回本地、批量写回逻辑。
  - Workspace 文件卡片新增 `Local` 写回动作。
- `chatbot-service/src/main/java/com/example/chatbot/workspace/AgentWorkspaceProperties.java`
  - Workspace 从文档型白名单扩展为文本代码工作区白名单。
  - 默认文件数上限从 100 提升到 300。
- `chatbot-service/src/main/resources/db/migration/V6__expand_agent_workspace_code_paths.sql`
  - 将 Workspace 文件相对路径从 255 扩展到 512，适配真实项目目录结构。
- `chatbot-service/src/main/java/com/example/chatbot/agent/AgentService.java`
  - 系统提示明确：Agent 不能直接访问本地磁盘，只能操作已同步的 Workspace 文件。
- `chatbot-service/src/main/java/com/example/chatbot/agent/tool/WorkspaceTools.java`
  - 工具描述明确支持源码、配置文件和项目文件。

## 验证项

- 本地构建：`mvn -q -DskipTests package`
- 生产健康检查：`bash scripts/prod-health-check.sh`
- 生产端到端验证：`bash scripts/prod-e2e-verify.sh`
- Workspace 接口验证：`bash scripts/prod-workspace-status-check.sh`

本地文件夹写回属于浏览器权限能力，需在 HTTPS 或 localhost 环境中进行人工验证。

## 本次部署验证结果

- `mvn -q -DskipTests package`：通过。
- `bash scripts/prod-health-check.sh`：通过。
- `bash scripts/prod-e2e-verify.sh`：通过。
- `bash scripts/prod-workspace-status-check.sh`：通过。
- `/chat` 页面包含 `openLocalFolderWorkspace` 入口：通过。
- Workspace 生产脚本已覆盖 `.java` 源码文件创建和读取：通过。

当前已部署到远程 `chatbot-service`。生产 HTTP 环境下若浏览器不暴露 `showDirectoryPicker`，页面会降级为目录只读导入；完整写回本地需要后续补 HTTPS、localhost 隧道或客户端承载。

## 代码项目支持补充

当前 Workspace 不只支持 Markdown。支持的文本代码/配置文件包括：

`.md`、`.txt`、`.json`、`.csv`、`.html`、`.css`、`.js`、`.ts`、`.jsx`、`.tsx`、`.java`、`.kt`、`.py`、`.go`、`.rs`、`.c`、`.cpp`、`.h`、`.hpp`、`.cs`、`.php`、`.rb`、`.swift`、`.sql`、`.sh`、`.bat`、`.ps1`、`.gradle`、`.vue`、`.scss`、`.less`、`.xml`、`.yml`、`.yaml`、`.properties`、`.toml`、`.ini`、`.conf`。

使用方式：

1. 用户打开聊天页，点击本地文件夹工作区按钮。
2. 选择自己的项目目录，浏览器把允许的文本源码文件同步到当前对话 Workspace。
3. 用户用 Agent 模式提出“分析这个项目”“修改某个 bug”“补一个接口”等需求。
4. Agent 先调用 `listWorkspaceFiles`，再调用 `readWorkspaceFile` 读取相关文件，最后用 `updateWorkspaceFile` 或 `createWorkspaceFile` 生成修改。
5. 用户在 Workspace 卡片中查看变更后，点击 `Local` 或 `Sync to Local` 写回本地授权目录。

不支持直接上传/修改二进制文件、依赖目录、构建产物，也不会同步 `.git`、`node_modules`、`target`、`dist`、`build` 等目录。

## springai-demo 导入问题复盘

用户本地项目 `D:\develop\workspace\idea\ai\springai-demo` 共 112 个文件。旧规则成功导入 46 个，原因如下：

- 旧白名单只接收文本源码/配置扩展名，因此 `.class`、`.pdf`、`.sample`、无扩展名 Git 内部对象不会导入。
- HTTP 降级目录导入路径之前没有显式跳过 `.idea`、`.vscode`、`target`，导致部分 IDE 配置和 `target/classes/application.yml` 可能被误导入。
- `.gitignore`、`.gitattributes` 这类项目根配置之前未放行。

本次补充后：

- 新增支持 `.gitignore`、`.gitattributes`、`.dockerignore`、`.editorconfig`。
- 新增支持特殊项目文件名：`Dockerfile`、`Containerfile`、`Jenkinsfile`、`Makefile`、`mvnw`、`gradlew`、`README`、`LICENSE`、`NOTICE`。
- 前后端都显式拦截 `.git`、`.idea`、`.vscode`、`node_modules`、`target`、`dist`、`build`、`.gradle`。
- 对 `springai-demo` 重新按新规则统计，有效同步文件为 40 个，包含源码、`pom.xml`、`application.yml`、`.gitignore`、`.gitattributes`、`.mvn/wrapper/maven-wrapper.properties` 等真正适合 Agent 分析和修改的项目文件。

## Workspace 项目树展示补充

旧版 Workspace 弹窗按文件平铺展示，虽然后端保留了 `relativePath`，但用户无法像 IDE 一样看见目录结构。

本次补充：

- Workspace 弹窗改为左侧目录树、右侧文件详情与预览。
- 目录树按 `relativePath` 分组，保留项目结构，例如 `src/main/java/...`、`src/main/resources/...`。
- 文件节点按目录优先、文件名排序。
- 右侧展示文件名、相对路径、版本、类型，并保留 `Open`、下载、`Local`、`KB` 操作。
- 默认预览当前选中文件内容，长文件预览截断到 20000 字符，避免页面卡顿。
