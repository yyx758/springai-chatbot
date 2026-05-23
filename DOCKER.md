# Docker 部署说明

## 快速开始

```bash
# 一键构建并启动所有服务（基础设施 + 3 个应用服务）
docker-compose up -d

# 仅重建应用镜像（代码改了之后）
docker-compose build

# 重启应用服务（配置改了之后）
docker-compose up -d

# 停止并清理所有数据卷
docker-compose down -v
```

## 架构总览

```
项目根目录
├── docker-compose.yml        ← 编排 7 个服务
├── chatbot-service/
│   ├── Dockerfile            ← 主服务构建脚本
│   └── src/                  ← Java 源码
├── file-service/
│   ├── Dockerfile            ← 文件服务构建脚本
│   └── src/
├── gateway/
│   ├── Dockerfile            ← 网关构建脚本
│   └── src/
└── pom.xml                   ← 父 POM（Maven 多模块）
```

## 7 个服务

| 服务 | 端口 | 类型 | 说明 |
|------|------|------|------|
| chatbot-mysql | 3307 | 基础设施 | MySQL 8.0 数据库 |
| chatbot-redis | 6379 | 基础设施 | Redis 7 缓存 |
| chatbot-kafka | 9092 | 基础设施 | Kafka 消息队列 |
| chatbot-nacos | 8848 | 基础设施 | Nacos 服务注册中心 |
| chatbot-service | 8080 | 应用 | 聊天主服务 |
| file-service | 8081 | 应用 | 文件管理服务 |
| chatbot-gateway | 9000 | 应用 | Spring Cloud Gateway 网关 |

## Dockerfile 工作流程（以 chatbot-service 为例）

```
你写代码 → docker-compose build → docker-compose up -d

         ┌──────── Dockerfile 执行过程 ────────┐
         │                                      │
         │  FROM maven:3.9-eclipse-temurin-17   │ ← 拉一个自带 Maven + JDK 的 Linux
         │  COPY pom.xml .                      │ ← 复制 POM
         │  RUN mvn dependency:go-offline       │ ← 在容器里下载依赖 jar 包
         │  COPY src ./src                      │ ← 复制源码
         │  RUN mvn package -DskipTests         │ ← 在容器里编译打包（你不需要本地装 Maven）
         │                                      │
         │  FROM eclipse-temurin:17-jre-alpine  │ ← 扔掉编译环境，换小镜像
         │  COPY --from=builder .../target/*.jar│ ← 只把 jar 扣出来
         │  ENTRYPOINT ["java", "-jar", "app.jar"] ← 启动命令
         │                                      │
         └──────────────────────────────────────┘
```

**关键点：你不需要本地安装 Maven 或 JDK。** Docker 在容器里完成所有编译工作。

## Docker 核心概念

| 概念 | 比喻 | 对应物 |
|------|------|--------|
| **Dockerfile** | 菜谱 | `chatbot-service/Dockerfile` |
| **Image（镜像）** | 菜谱的快照 | `springai-chatbot-chatbot-service:latest` |
| **Container（容器）** | 按菜谱炒出来的菜 | 运行中的进程 |
| **build context** | 厨房能拿到的食材 | 项目根目录（docker-compose 中的 `context: .`） |
| **Volume（数据卷）** | 冰箱 | 存 MySQL 数据、Redis 数据、上传的文件 |

## Dockerfile vs 本地 mvn package 的区别

| 方式 | 需要本地 JDK/Maven？ | 环境一致性 | 推荐场景 |
|------|----------------------|------------|----------|
| **Dockerfile 内编译** | 不需要 | 完全一致（容器内 Linux 环境） | 部署/CI |
| **本地 mvn package 再 COPY jar** | 需要 | 可能不一致（Windows vs Linux） | 本地快速调试 |

当前项目用的是 **Dockerfile 内编译**（多阶段构建），好处是任何机器上都能构建，不依赖本地环境。

## 为什么 build context 是项目根目录

这是 Maven 多模块项目的必然要求。子模块的 pom.xml 写了：

```xml
<parent>
    <groupId>com.example</groupId>
    <artifactId>springai-chatbot</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</parent>
```

Docker 构建时，Maven 必须先读取 `./pom.xml`（父 POM），再读 `./chatbot-service/pom.xml`（子模块）。如果 build context 只给 `./chatbot-service/`，Docker 根本看不到父 POM，构建报错。

## 数据库初始化

Flyway 在 chatbot-service 启动时自动执行 `V1__init_schema.sql`（db/migration 目录下），创建三张表：

- `user_account` — 用户账号
- `knowledge_document` — 知识库文档
- `chat_record` — 聊天记录

每次 chatbot-service 容器启动，Flyway 会检查 `flyway_schema_history` 表，只执行未跑过的迁移脚本。

## 配置注入

docker-compose.yml 通过 `environment` 把配置注入容器：

```yaml
environment:
  SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/chatbot  # mysql = 容器名，Docker 内部 DNS 自动解析
  SPRING_DATA_REDIS_HOST: redis                            # redis = 容器名
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092               # kafka = 容器名
```

Docker 内部的 DNS 会自动把 `mysql` / `redis` / `kafka` 解析为对应容器的 IP，所以**不要写成 localhost**。
