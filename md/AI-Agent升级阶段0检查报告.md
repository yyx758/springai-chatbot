# AI Agent 升级阶段 0 检查报告

检查时间：2026-06-04

## 1. 阶段目标

阶段 0 目标是确认当前项目基线状态，判断是否可以进入阶段 1：Agent Runtime 最小可用版。

本阶段不修改业务代码。

## 2. 已执行检查

### 2.1 工作区状态

已执行：

```bash
git status --short
```

结果：

- 工作区存在大量既有未提交改动。
- 包含 `.idea`、Kafka 相关代码、`ChatbotService`、前端模板、compose、Gateway 配置、文档等。
- 后续阶段实现时必须避免回退这些既有改动。

重要说明：

- `md/AI-Agent升级分阶段执行计划.md` 是本次新增计划文档。
- `md/AI-Agent升级阶段0检查报告.md` 是本次阶段 0 新增检查报告。

### 2.2 Maven 构建

已执行：

```bash
mvn -q -DskipTests package
```

结果：通过。

结论：

- 当前父工程和三个子模块可以完成跳过测试的打包构建。
- 当前代码具备进入阶段 1 的编译基线。

### 2.3 Docker Compose 配置验证

已执行：

```bash
docker compose config
docker compose -f docker-compose.prod.yml config
```

结果：均通过。

附带现象：

- Docker 命令提示无法读取当前用户 Docker config：
  `C:\Users\29146\.docker\config.json: Access is denied`
- 该提示没有导致 compose config 失败。

结论：

- 本地 compose 和生产 compose 语法有效。
- 生产 compose 已经有针对 4G 服务器的保守内存参数，例如 Kafka heap、Nacos JVM、Java 服务 `JAVA_OPTS`、Redis maxmemory、MySQL buffer 限制。

### 2.4 8080/8081 硬编码扫描

已执行：

```bash
rg -n "8080|8081" chatbot-service/src/main/resources gateway/src/main/resources file-service/src/main/resources
rg -n "localhost:8080|localhost:8081|:8080|:8081" chatbot-service/src/main/resources/templates file-service/src/main/resources/templates gateway/src/main/resources
rg -n "8080|8081" docker-compose.yml docker-compose.prod.yml chatbot-service/src/main/java file-service/src/main/java gateway/src/main/java
```

结果：

- `chatbot-service` 配置自身端口为 `8080`。
- `file-service` 配置自身端口为 `8081`。
- compose 中 `chatbot-service` 和 `file-service` 使用 `expose` 暴露容器内部端口。
- `FILE_SERVICE_URL` 使用 `http://file-service:8081` 作为容器内服务调用地址。
- 前端模板未发现 `:8080` 或 `:8081` 的硬编码访问。
- `chat.html` 文件接口使用 `/api/files`。
- 文件管理入口使用 `/admin/files`。

结论：

- 当前前端没有绕过 Gateway 直接访问业务服务端口。
- 当前 `8080/8081` 出现位置属于服务自身端口、容器内通信或启动提示，阶段 0 不需要修改。

## 3. 发现的风险

### 3.1 敏感配置风险

扫描发现：

- `docker-compose.yml`、`docker-compose.prod.yml`、部分 `application.yml` 存在默认密码或环境变量默认值。
- 本地 `.env` 会被 `docker compose config` 展开，导致 config 输出中出现真实 API Key、SMTP 授权码等敏感值。

风险等级：高。

建议：

- 后续阶段不要把 `docker compose config` 的完整输出提交到文档或聊天记录。
- 生产环境必须通过 `.env` 或服务器环境变量注入敏感值。
- 不要在代码或 compose 默认值中保留真实密钥。
- 如果这些密钥已经暴露过，建议后续轮换 DeepSeek API Key、SMTP 授权码和生产 JWT Secret。

阶段 0 处理策略：

- 仅记录风险，不立即修改，避免影响当前部署。
- 后续可单独开安全治理阶段处理。

### 3.2 4G 服务器资源风险

当前生产 compose 已经做了部分收紧：

- `chatbot-service`: `-Xms128m -Xmx256m -XX:+UseSerialGC -XX:MaxRAM=350m`
- `file-service`: `-Xms64m -Xmx128m -XX:+UseSerialGC -XX:MaxRAM=160m`
- `chatbot-gateway`: `-Xms64m -Xmx128m -XX:+UseSerialGC -XX:MaxRAM=160m`
- Kafka heap: `-Xms100m -Xmx200m`
- Nacos JVM: `JVM_XMS=96m`, `JVM_XMX=128m`
- Redis maxmemory: `32mb`
- MySQL innodb buffer pool: `32M`

结论：

- Agent Runtime 和工具审计可以继续推进。
- 不建议在 4G 云服务器本地部署 embedding 模型。
- PGVector 后续必须配置为可选/可关闭，不能作为聊天服务强依赖。

## 4. 阶段 0 通过标准对照

| 标准 | 结果 |
|---|---|
| Maven 构建成功 | 通过 |
| 本地 compose 配置可解析 | 通过 |
| 生产 compose 配置可解析 | 通过 |
| 未发现前端硬编码访问 8080/8081 | 通过 |
| 明确已有未提交改动边界 | 通过 |
| 4G 服务器资源风险已评估 | 通过 |

## 5. 阶段 0 结论

阶段 0 通过。

可以进入阶段 1：Agent Runtime 最小可用版。

进入阶段 1 前需要遵守：

- 不回退当前既有未提交改动。
- Agent 先只做只读工具。
- 不新增重型服务。
- 不引入 PGVector、MCP、写操作工具。
- 不把 embedding 模型部署在 4G 云服务器上。
