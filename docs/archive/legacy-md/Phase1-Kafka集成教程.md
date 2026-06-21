# Phase 1: Kafka 异步解耦 —— 聊天记录持久化

## 一、本阶段目标

将原来 `ChatbotService` 中使用 `@Async` 线程池直接写 MySQL + Redis 的方式，改造为 **Kafka 生产者-消费者模式**。

### 改造前 vs 改造后

```
【改造前】
用户请求 → AI 流式返回 → @Async 线程池 → 直接写 MySQL + Redis
                          ⚠️ 线程池满会丢任务
                          ⚠️ 进程崩溃丢失未处理的任务
                          ⚠️ 无法水平扩展

【改造后】
用户请求 → AI 流式返回 → Kafka Producer → chat.events Topic → Kafka Consumer → 写 MySQL + Redis
                          ✅ 消息持久化到 Kafka，不丢失
                          ✅ 支持重试（消费失败重新消费）
                          ✅ 支持多消费者水平扩展
```

---

## 二、前置环境准备

### 2.1 安装 Kafka（本地开发）

**方式一：Docker Compose（推荐，KRaft 模式，无需 Zookeeper）**

项目根目录已提供 `docker-compose.yml`，使用 KRaft 模式：

- 无 Zookeeper 依赖，少占 300-500MB 内存
- Kafka 自身管理元数据，架构更简单
- 基于 Confluent 7.5.0（Kafka 3.5，KRaft 已稳定）

启动：
```bash
docker-compose up -d
```

**方式二：本地安装**

1. 下载 Kafka：https://kafka.apache.org/downloads
2. 解压后启动：
```bash
# 启动 Zookeeper
bin/zookeeper-server-start.sh config/zookeeper.properties

# 启动 Kafka
bin/kafka-server-start.sh config/server.properties
```

### 2.2 验证 Kafka 运行

```bash
# 创建测试 Topic
kafka-topics.sh --create --topic test --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1

# 发送测试消息
kafka-console-producer.sh --topic test --bootstrap-server localhost:9092
> hello kafka

# 消费测试消息（另开终端）
kafka-console-consumer.sh --topic test --from-beginning --bootstrap-server localhost:9092
```

---

## 三、代码改动详解

### 3.1 新增文件清单

```
src/main/java/com/example/chatbot/kafka/
├── ChatEvent.java           # 消息体（事件模型）
├── KafkaTopicConfig.java    # Topic 配置
├── ChatEventProducer.java   # 生产者
├── ChatEventConsumer.java   # 消费者（负责持久化）
└── KafkaConsumerConfig.java # 消费者配置

src/test/java/com/example/chatbot/kafka/
└── KafkaIntegrationTest.java # 集成测试
```

### 3.2 依赖添加（pom.xml）

```xml
<!-- Spring Kafka -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>

<!-- Kafka 测试支持 -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

### 3.3 Kafka 配置（application.yml）

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      retries: 3        # 发送失败重试 3 次
      acks: 1           # Leader 确认即可（平衡性能和可靠性）
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      group-id: chatbot-group
      auto-offset-reset: earliest
      properties:
        spring.json.trusted.packages: "com.example.chatbot.kafka"
    listener:
      ack-mode: manual_immediate  # 手动确认，确保消息不丢失
```

**关键配置说明：**

| 配置项 | 含义 |
|--------|------|
| `bootstrap-servers` | Kafka 地址，支持逗号分隔多个 |
| `retries` | 发送失败自动重试次数 |
| `acks=1` | Leader 写入成功即返回（acks=all 最可靠但最慢） |
| `auto-offset-reset=earliest` | 新消费者从最早消息开始消费 |
| `ack-mode=manual_immediate` | 手动确认，消费成功后才提交 offset |

### 3.4 消息体设计（ChatEvent.java）

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent implements Serializable {
    private String eventType;      // 事件类型：CHAT_COMPLETED / CHAT_ERROR
    private String sessionId;      // 会话 ID
    private String userMessage;    // 用户消息
    private String botResponse;    // AI 回复
    private String imageData;      // 图片 Base64
    private String imageMimeType;  // 图片 MIME 类型
    private byte[] imageBytes;     // 图片原始字节
    private LocalDateTime eventTime; // 事件时间
    private String userId;         // 用户 ID
}
```

**设计要点：**
- 实现 `Serializable` 接口，支持 JSON 序列化
- 包含完整的聊天上下文，消费者无需额外查询
- 预留 `eventType` 字段，后续扩展事件类型（如 `CHAT_ERROR`）

### 3.5 生产者（ChatEventProducer.java）

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventProducer {
    private final KafkaTemplate<String, ChatEvent> kafkaTemplate;

    public void sendChatEvent(ChatEvent event) {
        String key = event.getSessionId(); // 用 sessionId 做 key
        CompletableFuture<SendResult<String, ChatEvent>> future =
                kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("消息发送成功，Topic: {}, Partition: {}, Offset: {}",
                    result.getRecordMetadata().topic(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            } else {
                log.error("消息发送失败: {}", ex.getMessage());
            }
        });
    }
}
```

**为什么用 `sessionId` 做 key？**
- Kafka 保证同一 key 的消息路由到同一 partition
- 同一会话的消息有序，避免乱序导致历史记录混乱

### 3.6 消费者（ChatEventConsumer.java）

```java
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventConsumer {
    private final ChatRecordMapper chatRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @KafkaListener(topics = "chat.events", groupId = "chatbot-persistence-group")
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        try {
            // 1. 清洗 botResponse
            // 2. 处理图片数据
            // 3. 写入 MySQL
            // 4. 更新 Redis 缓存
            // 5. 手动确认
            ack.acknowledge();
        } catch (Exception e) {
            throw e; // 不 ACK，消息会重试
        }
    }
}
```

**手动 ACK 的意义：**
- `auto-commit` 模式下，消息被 poll() 就算消费成功，崩溃时会丢消息
- `manual` 模式下，必须显式调用 `ack.acknowledge()` 才算消费成功
- 如果消费过程中抛异常，不 ACK，Kafka 会在下次 poll 时重新投递

### 3.7 消费者配置（KafkaConsumerConfig.java）

```java
@Configuration
public class KafkaConsumerConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChatEvent> kafkaListenerContainerFactory() {
        // 配置 JSON 反序列化
        // 配置 ErrorHandlingDeserializer（反序列化失败不丢消息）
        // 配置手动 ACK 模式
        // 设置并发数 = Topic 分区数（3）
    }
}
```

### 3.8 ChatbotService 改动

**改动前：**
```java
@Async
public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
    // 直接写 MySQL
    chatRecordMapper.insert(record);
    // 直接写 Redis
    redisTemplate.opsForList().rightPush(key, record);
}
```

**改动后：**
```java
public void asyncSaveChatRecord(String sessionId, String userMsg, String botRes) {
    ChatEvent event = ChatEvent.builder()
            .eventType("CHAT_COMPLETED")
            .sessionId(sessionId)
            .userMessage(userMsg)
            .botResponse(botRes)
            .eventTime(LocalDateTime.now())
            .build();
    chatEventProducer.sendChatEvent(event); // 发送到 Kafka
}
```

---

## 四、运行和测试

### 4.1 启动 Kafka

```bash
docker-compose up -d
# 验证
docker ps | grep kafka
```

### 4.2 启动应用

```bash
mvn spring-boot:run
```

启动日志应包含：
```
Chat models available - OpenAI: ..., Ollama: ..., Vision: ...
```

### 4.3 运行集成测试

```bash
mvn test -Dtest=KafkaIntegrationTest
```

预期输出：
```
✅ Producer 发送消息成功
   Topic: chat.events
   Partition: 0
   Offset: 0
   SessionId: test-user_12345

✅ 消息图片数据传输成功
   图片类型: image/png
   图片大小: 4 bytes

✅ 批量消息发送成功，共 3 条待验证
```

### 4.4 端到端验证

1. 打开浏览器访问 http://localhost:8080
2. 登录并发送一条聊天消息
3. 观察控制台日志：

```
【Kafka Producer】消息发送成功，Topic: chat.events, Partition: 0, Offset: 5
【Kafka Consumer】收到消息，Topic: chat.events, Partition: 0, Offset: 5, SessionId: 1_xxxx
【Kafka Consumer】MySQL 持久化成功，RecordId: 42, SessionId: 1_xxxx
【Kafka Consumer】消息处理完成并 ACK，SessionId: 1_xxxx
```

---

## 五、核心概念速查

### 5.1 Kafka 基础概念

| 概念 | 类比 | 说明 |
|------|------|------|
| **Broker** | 邮局 | Kafka 服务器，负责接收、存储、投递消息 |
| **Topic** | 邮箱分类 | 消息的逻辑分类，如 `chat.events` |
| **Partition** | 分拣线 | Topic 的物理分区，支持并行消费 |
| **Producer** | 寄件人 | 发送消息到 Topic |
| **Consumer** | 收件人 | 从 Topic 消费消息 |
| **Consumer Group** | 公司收件 | 同一组内消息只被消费一次（负载均衡） |
| **Offset** | 快递单号 | 消费者在 Partition 中的位置标记 |
| **ACK** | 签收确认 | 确认消息已被成功处理 |

### 5.2 消息投递语义

| 语义 | 含义 | 本项目选择 |
|------|------|-----------|
| **At most once** | 最多一次，可能丢消息 | ❌ |
| **At least once** | 至少一次，可能重复消费 | ✅ 需要幂等处理 |
| **Exactly once** | 恰好一次，最理想 | ⚠️ Kafka 事务支持，复杂度高 |

本项目选择 **At least once**，通过以下方式保证：
- `acks=1`：Producer 确保消息写入 Kafka
- 手动 ACK：Consumer 确保消息处理成功
- 消费端做幂等（基于 sessionId + 时间戳去重）

---

## 六、常见问题

### Q1: Kafka 启动失败怎么办？

```bash
# 检查端口占用
netstat -an | findstr 9092

# 查看 Kafka 日志
docker logs kafka
```

### Q2: 消费者收不到消息？

1. 检查 Topic 是否创建成功：`kafka-topics.sh --list --bootstrap-server localhost:9092`
2. 检查消费者组：`kafka-consumer-groups.sh --list --bootstrap-server localhost:9092`
3. 检查 `spring.json.trusted.packages` 配置是否包含消息类的包名

### Q3: 消息反序列化失败？

确保 `ChatEvent` 类实现了 `Serializable`，且 `trusted.packages` 配置正确。

### Q4: 生产环境需要注意什么？

1. **多 Broker 集群**：至少 3 个 Broker，`replication-factor=3`
2. **监控**：使用 Kafka Manager 或 Grafana + Prometheus
3. **死信队列（DLQ）**：消费多次失败的消息转入 DLQ，避免阻塞
4. **消息保留时间**：默认 7 天，根据业务调整
5. **分区数**：决定了最大并发消费数

---

## 七、下一步（Phase 2 预告）

Phase 2 将引入 **Spring Cloud Gateway**，实现：
- 统一路由和负载均衡
- JWT 鉴权过滤器（替代现有 AuthInterceptor）
- 限流和熔断
- 为后续微服务拆分做准备
