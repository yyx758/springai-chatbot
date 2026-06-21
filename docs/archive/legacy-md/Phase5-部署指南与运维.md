# Phase 5: 部署指南与运维

## 一、项目最终架构

```
┌─────────────────────────────────────────────────────────────┐
│                      Docker Compose                          │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │  MySQL    │  │  Redis   │  │  Kafka   │  │  Nacos   │    │
│  │  :3306    │  │  :6379   │  │  :9092   │  │  :8848   │    │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘    │
│       │              │              │              │          │
│  ┌────┴──────────────┴──────────────┴──────────────┴─────┐   │
│  │              chatbot-service (:8080)                   │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │   │
│  │  │Auth模块   │  │Chat模块   │  │Knowledge模块     │    │   │
│  │  └──────────┘  └──────────┘  └──────────────────┘    │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │   │
│  │  │Kafka     │  │Kafka     │  │Kafka              │    │   │
│  │  │Producer  │  │Consumer  │  │Consumer           │    │   │
│  │  └──────────┘  └──────────┘  └──────────────────┘    │   │
│  └───────────────────────┬───────────────────────────────┘   │
│                          │                                    │
│  ┌───────────────────────┴───────────────────────────────┐   │
│  │              chatbot-gateway (:9000)                   │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────┐    │   │
│  │  │JWT鉴权   │  │路由转发   │  │请求日志           │    │   │
│  │  └──────────┘  └──────────┘  └──────────────────┘    │   │
│  └───────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、本地开发环境

### 2.1 一键启动

```bash
# 启动基础设施（MySQL + Redis + Kafka + Nacos）
docker-compose up -d mysql redis kafka nacos

# 等待服务就绪（约 30 秒）
docker-compose ps

# 启动主服务（IDE 或命令行）
mvn spring-boot:run

# 启动 Gateway（另一个终端）
cd gateway && mvn spring-boot:run
```

### 2.2 验证服务

```bash
# 检查所有容器状态
docker-compose ps

# 检查主服务
curl http://localhost:8080/api/chat/health

# 检查 Gateway
curl http://localhost:9000/api/chat/health

# 检查 Nacos 管理界面
# 浏览器打开 http://localhost:8848/nacos (nacos/nacos)

# 检查 Kafka Topic
docker exec chatbot-kafka kafka-topics --list --bootstrap-server localhost:9092
```

### 2.3 运行所有测试

```bash
# Phase 1: Kafka 集成测试
mvn test -Dtest=KafkaIntegrationTest

# Phase 4: 事件驱动测试
mvn test -Dtest=EventDrivenIntegrationTest

# Phase 5: 端到端测试（需要所有服务运行中）
mvn test -Dtest=EndToEndTest
```

---

## 三、Docker Compose 全量部署

### 3.1 构建镜像

```bash
# 构建主服务镜像
docker build -t chatbot-service:latest .

# 构建 Gateway 镜像
docker build -t chatbot-gateway:latest ./gateway
```

### 3.2 启动所有服务

```bash
# 一键启动全部（基础设施 + 应用）
docker-compose up -d

# 查看日志
docker-compose logs -f chatbot-service
docker-compose logs -f chatbot-gateway
```

### 3.3 停止和清理

```bash
# 停止所有服务
docker-compose down

# 停止并删除数据卷（慎用！会清空数据库）
docker-compose down -v
```

---

## 四、阿里云 2GB 部署方案

### 4.1 内存分配

| 组件 | 内存 | 说明 |
|------|------|------|
| MySQL | 用云 RDS | 不占本机内存 |
| Redis | 用云 Redis | 不占本机内存 |
| Kafka | 512MB | JVM -Xms128m -Xmx384m |
| Nacos | 384MB | JVM 默认 |
| chatbot-service | 512MB | JVM -Xms256m -Xmx384m |
| chatbot-gateway | 256MB | JVM -Xms128m -Xmx192m |
| 系统预留 | 300MB | OS + Docker |
| **合计** | **~2GB** | 刚好够用 |

### 4.2 使用云服务的 docker-compose

创建 `docker-compose.prod.yml`：

```yaml
services:
  # 只启动应用层，数据库用云服务
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: chatbot-kafka
    environment:
      KAFKA_HEAP_OPTS: "-Xms128m -Xmx384m"
      # ... 同 docker-compose.yml

  nacos:
    image: nacos/nacos-server:v2.3.0
    container_name: chatbot-nacos
    environment:
      JVM_XMS: "128m"
      JVM_XMX: "256m"
      # ...

  chatbot-service:
    environment:
      # 使用云数据库
      SPRING_DATASOURCE_URL: jdbc:mysql://rm-xxx.mysql.rds.aliyuncs.com:3306/chatbot
      SPRING_DATASOURCE_USERNAME: admin
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      # 使用云 Redis
      SPRING_DATA_REDIS_HOST: r-xxx.redis.rds.aliyuncs.com
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
      # Kafka 容器内通信
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      NACOS_SERVER_ADDR: nacos:8848

  chatbot-gateway:
    environment:
      NACOS_SERVER_ADDR: nacos:8848
```

### 4.3 部署步骤

```bash
# 1. 购买阿里云 ECS（2GB 内存）
# 2. 安装 Docker
curl -fsSL https://get.docker.com | sh

# 3. 安装 Docker Compose
sudo apt install docker-compose-plugin

# 4. 上传代码
scp -r . root@your-ecs-ip:/opt/chatbot/

# 5. 配置环境变量
export DB_PASSWORD=your_db_password
export REDIS_PASSWORD=your_redis_password
export DEEPSEEK_API_KEY=your_api_key

# 6. 启动
cd /opt/chatbot
docker compose -f docker-compose.prod.yml up -d
```

---

## 五、Nginx 反向代理配置

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # Gateway 代理
    location / {
        proxy_pass http://127.0.0.1:9000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

        # SSE 支持（聊天流式响应）
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 600s;
    }

    # 安全配置
    server_tokens off;
}
```

---

## 六、监控和日志

### 6.1 查看服务状态

```bash
# 容器状态
docker-compose ps

# 服务日志
docker-compose logs -f --tail=100 chatbot-service
docker-compose logs -f --tail=100 chatbot-gateway

# Kafka 消费者组状态
docker exec chatbot-kafka kafka-consumer-groups --list --bootstrap-server localhost:9092
docker exec chatbot-kafka kafka-consumer-groups --describe --group chatbot-persistence-group --bootstrap-server localhost:9092
```

### 6.2 关键监控指标

| 指标 | 正常范围 | 告警阈值 |
|------|---------|---------|
| Kafka Consumer Lag | < 100 | > 1000 |
| MySQL 连接数 | < 50 | > 80 |
| Redis 内存使用 | < 500MB | > 800MB |
| 服务响应时间 | < 200ms | > 2s |
| JVM 堆使用率 | < 70% | > 85% |

### 6.3 Actuator 健康检查

```bash
# Gateway 健康检查
curl http://localhost:9000/actuator/health

# 主服务健康检查
curl http://localhost:8080/actuator/health
```

---

## 七、常见运维操作

### 7.1 Kafka Topic 管理

```bash
# 查看所有 Topic
docker exec chatbot-kafka kafka-topics --list --bootstrap-server localhost:9092

# 查看 Topic 详情
docker exec chatbot-kafka kafka-topics --describe --topic chat.events --bootstrap-server localhost:9092

# 查看消费者组
docker exec chatbot-kafka kafka-consumer-groups --list --bootstrap-server localhost:9092

# 查看消费者 Lag
docker exec chatbot-kafka kafka-consumer-groups --describe --group chatbot-persistence-group --bootstrap-server localhost:9092
```

### 7.2 数据库迁移

```bash
# Flyway 自动迁移（服务启动时执行）
# 手动迁移：
mvn flyway:migrate
```

### 7.3 服务重启

```bash
# 重启单个服务
docker-compose restart chatbot-service

# 重建并重启
docker-compose up -d --build chatbot-service
```

---

## 八、生产环境 Checklist

- [ ] 修改 JWT 密钥（`APP_JWT_SECRET` 环境变量）
- [ ] 修改数据库密码
- [ ] 修改 Redis 密码
- [ ] 配置 DEEPSEEK_API_KEY
- [ ] 配置 SMTP 邮件服务
- [ ] 配置 Nginx 反向代理
- [ ] 配置 HTTPS 证书
- [ ] Kafka 设置 `replication-factor=3`（多 Broker 时）
- [ ] MySQL 开启 binlog（数据恢复用）
- [ ] Redis 设置 maxmemory-policy
- [ ] 配置日志收集（ELK 或阿里云 SLS）
- [ ] 配置监控告警

---

## 九、项目文件总览

```
springaI-chatbot/
├── src/main/java/com/example/chatbot/
│   ├── ChatbotApplication.java
│   ├── config/                    # 配置类
│   ├── controller/                # REST 控制器
│   ├── dto/                       # 数据传输对象
│   ├── entity/                    # 实体类
│   ├── kafka/                     # Kafka 事件驱动
│   │   ├── ChatEvent.java
│   │   ├── ChatEventProducer.java
│   │   ├── ChatEventConsumer.java
│   │   ├── KnowledgeEvent.java
│   │   ├── KnowledgeEventProducer.java
│   │   ├── KnowledgeEventConsumer.java
│   │   ├── NotificationEvent.java
│   │   ├── NotificationEventProducer.java
│   │   ├── NotificationEventConsumer.java
│   │   ├── KafkaTopicConfig.java
│   │   ├── KafkaConsumerConfig.java
│   │   ├── KnowledgeConsumerConfig.java
│   │   └── NotificationConsumerConfig.java
│   ├── mapper/                    # MyBatis Mapper
│   ├── security/                  # JWT 认证
│   └── service/                   # 业务逻辑
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/              # Flyway 迁移脚本
│   └── templates/                 # Thymeleaf 模板
├── src/test/java/                 # 测试
│   ├── com/example/chatbot/kafka/
│   │   ├── KafkaIntegrationTest.java
│   │   └── EventDrivenIntegrationTest.java
│   └── com/example/chatbot/e2e/
│       └── EndToEndTest.java
├── gateway/                       # Gateway 网关服务
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/example/chatbot/gateway/
│       ├── GatewayApplication.java
│       ├── config/JwtConfig.java
│       └── filter/
│           ├── AuthGlobalFilter.java
│           └── RequestLoggingFilter.java
├── md/                            # 文档
│   ├── Phase1-Kafka集成教程.md
│   ├── Phase2-SpringCloudGateway教程.md
│   ├── Phase3-Nacos服务注册与发现.md
│   ├── Phase4-事件驱动微服务通信.md
│   └── Phase5-部署指南与运维.md
├── docker-compose.yml             # Docker 编排
├── Dockerfile                     # 主服务镜像
├── pom.xml
└── .dockerignore
```
