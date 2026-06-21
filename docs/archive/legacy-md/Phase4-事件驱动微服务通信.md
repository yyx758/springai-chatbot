# Phase 4: 事件驱动微服务通信

## 一、本阶段目标

通过 Kafka 实现服务间的事件驱动通信，完成三大业务流的异步解耦：

| 事件流 | 生产者 | 消费者 | 效果 |
|--------|--------|--------|------|
| 聊天记录持久化 | ChatbotService | ChatEventConsumer | @Async → Kafka，消息不丢失 |
| 知识库变更通知 | RagService | KnowledgeEventConsumer | 文档变更自动刷新 RAG 缓存 |
| 邮件异步发送 | AuthController/AuthService | NotificationEventConsumer | 注册/重置密码邮件异步化 |

### 架构全景

```
┌─────────────────────────────────────────────────────────────┐
│                        Kafka Cluster                         │
│                                                              │
│  ┌──────────────┐  ┌──────────────────┐  ┌────────────────┐ │
│  │ chat.events   │  │knowledge.events  │  │notification.   │ │
│  │ (3 partitions)│  │(3 partitions)    │  │events          │ │
│  └──────┬───────┘  └────────┬─────────┘  └───────┬────────┘ │
└─────────┼───────────────────┼─────────────────────┼──────────┘
          │                   │                     │
          ▼                   ▼                     ▼
┌─────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ ChatEventConsumer│ │KnowledgeEvent    │ │NotificationEvent │
│ → MySQL + Redis  │ │Consumer          │ │Consumer          │
│                  │ │→ 清除 RAG 缓存   │ │→ EmailService    │
└─────────────────┘ └──────────────────┘ │→ 发送邮件        │
                                         └──────────────────┘
          ▲                   ▲                     ▲
          │                   │                     │
┌─────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ ChatbotService   │ │ RagService       │ │ AuthController   │
│ (Producer)       │ │ (Producer)       │ │ AuthService      │
│ 聊天完成→发事件   │ │ 文档增删→发事件   │ │ (Producer)       │
└─────────────────┘ └──────────────────┘ │ 注册/重置→发事件  │
                                         └──────────────────┘
```

---

## 二、新增 Topic 设计

### 2.1 Topic 列表

| Topic | 分区数 | 用途 | Key 策略 |
|-------|--------|------|----------|
| `chat.events` | 3 | 聊天记录持久化 | sessionId（保证会话有序） |
| `knowledge.events` | 3 | 知识库变更通知 | userId_eventType |
| `notification.events` | 3 | 邮件发送请求 | email（保证同一邮箱有序） |

### 2.2 事件类型枚举

```
chat.events:
  - CHAT_COMPLETED    聊天完成，需要持久化

knowledge.events:
  - KNOWLEDGE_CREATED 知识文档创建
  - KNOWLEDGE_DELETED 知识文档删除

notification.events:
  - SEND_VERIFICATION_CODE  发送注册验证码
  - SEND_RESET_CODE         发送重置密码验证码
```

---

## 三、代码改动详解

### 3.1 新增文件

```
kafka/
├── KnowledgeEvent.java           # 知识库事件模型
├── KnowledgeEventProducer.java   # 知识库事件生产者
├── KnowledgeEventConsumer.java   # 知识库事件消费者
├── KnowledgeConsumerConfig.java  # 知识库消费者配置
├── NotificationEvent.java        # 通知事件模型
├── NotificationEventProducer.java# 通知事件生产者
├── NotificationEventConsumer.java# 通知事件消费者
└── NotificationConsumerConfig.java# 通知消费者配置
```

### 3.2 修改文件

| 文件 | 改动 |
|------|------|
| `KafkaTopicConfig.java` | 新增 knowledge.events 和 notification.events Topic |
| `RagService.java` | createDocument/deleteDocument 时发布知识库事件 |
| `AuthController.java` | sendVerificationCode 改为发布通知事件 |
| `AuthService.java` | sendForgotPasswordCode 改为发布通知事件 |

### 3.3 知识库事件流

```
用户创建知识文档
    │
    ▼
RagService.createDocument()
    ├─ 写入 MySQL
    └─ 发布 KNOWLEDGE_CREATED 事件
         │
         ▼
    Kafka: knowledge.events
         │
         ▼
KnowledgeEventConsumer.onKnowledgeEvent()
    └─ 清除该用户的 Redis 聊天缓存
       （下次聊天时重新加载，包含最新 RAG 引用）
```

### 3.4 通知事件流

```
用户点击"发送验证码"
    │
    ▼
AuthController.sendVerificationCode()
    └─ 发布 SEND_VERIFICATION_CODE 事件
         │
         ▼
    Kafka: notification.events
         │
         ▼
NotificationEventConsumer.onNotificationEvent()
    └─ 调用 EmailService.sendVerificationCode()
       └─ 发送邮件
```

**为什么邮件要走 Kafka？**
1. 邮件发送是 I/O 操作，耗时 1-3 秒，阻塞用户体验
2. SMTP 服务不稳定时，Kafka 提供重试机制
3. 后续拆分微服务时，Notification Service 独立部署，无需改代码

---

## 四、消费者配置详解

每个事件类型有独立的消费者配置，关键点：

```java
@Configuration
public class KnowledgeConsumerConfig {
    @Bean
    public ConsumerFactory<String, KnowledgeEvent> knowledgeConsumerFactory() {
        // 1. JSON 反序列化
        // 2. ErrorHandlingDeserializer（反序列化失败不丢消息）
        // 3. 手动 ACK（消费成功才确认）
        // 4. 独立 groupId（各消费者组独立消费）
    }
}
```

**为什么每个 Topic 独立 groupId？**
- 同一 groupId 内，消息只被一个消费者消费（负载均衡）
- 不同 groupId 各自独立消费全量消息（发布-订阅）
- chat.events 和 knowledge.events 需要各自独立消费

---

## 五、运行和测试

### 5.1 启动顺序

```bash
# 1. 启动 Kafka + Nacos
docker-compose up -d

# 2. 启动主服务
mvn spring-boot:run

# 3. 启动 Gateway
cd gateway && mvn spring-boot:run
```

### 5.2 运行测试

```bash
mvn test -Dtest=EventDrivenIntegrationTest
```

预期输出：
```
✅ 知识库创建事件验证通过
✅ 知识库删除事件验证通过
✅ 注册验证码通知事件验证通过
✅ 重置密码通知事件验证通过
✅ 所有 Topic 通信正常
   - chat.events: ✅
   - knowledge.events: ✅
   - notification.events: ✅
```

### 5.3 端到端验证

```bash
# 1. 注册用户 → 观察控制台应看到通知事件日志
# POST /api/auth/send-code → Kafka → NotificationEventConsumer → EmailService

# 2. 创建知识文档 → 观察控制台应看到知识库事件日志
# POST /api/knowledge/documents → Kafka → KnowledgeEventConsumer → 清除缓存

# 3. 发送聊天消息 → 观察控制台应看到聊天事件日志
# POST /api/chat/stream → Kafka → ChatEventConsumer → MySQL + Redis
```

---

## 六、消息可靠性保证

### 6.1 Producer 端

```java
// application.yml
spring.kafka.producer:
  retries: 3        # 发送失败重试 3 次
  acks: 1           # Leader 确认即可
```

### 6.2 Broker 端

```
- Topic 3 分区，支持并行消费
- 消息默认保留 7 天
- 单机部署副本数为 1（生产环境建议 3）
```

### 6.3 Consumer 端

```java
// 手动 ACK：消费成功后才确认
ack.acknowledge();

// 消费失败抛异常，不 ACK，消息会重新投递
catch (Exception e) {
    throw e; // 不 ACK，下次重试
}
```

### 6.4 消息丢失风险点

| 环节 | 风险 | 应对 |
|------|------|------|
| Producer 发送 | 网络异常 | retries=3 自动重试 |
| Broker 存储 | 磁盘故障 | 生产环境 replicas=3 |
| Consumer 消费 | 处理失败 | 手动 ACK + 重试 |
| 反序列化 | 消息格式错误 | ErrorHandlingDeserializer |

---

## 七、常见问题

### Q1: 消费者报 ClassNotFoundException？

确保 `spring.json.trusted.packages` 配置包含事件类的包名：
```yaml
spring.kafka.consumer.properties.spring.json.trusted.packages: "com.example.chatbot.kafka"
```

### Q2: 同一事件被消费多次？

这是 At-least-once 语义的正常现象。解决方案：
- 数据库操作做幂等（基于 ID 去重）
- Redis 操作天然幂等（SET 覆盖）

### Q3: 消费者处理太慢积压了？

增加消费者实例数（不超过 Topic 分区数）：
```java
factory.setConcurrency(3); // 3 个消费者线程
```

### Q4: 如何查看 Topic 中的消息？

```bash
# 查看 Topic 列表
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# 消费消息（调试用）
docker exec kafka kafka-console-consumer --topic knowledge.events --from-beginning --bootstrap-server localhost:9092
```

---

## 八、下一步（Phase 5 预告）

Phase 5 将进行 **Docker Compose 编排和集成测试**：
- 所有服务（Kafka + Nacos + 主服务 + Gateway）一键启动
- 端到端集成测试
- 性能调优
- 生产环境部署指南
