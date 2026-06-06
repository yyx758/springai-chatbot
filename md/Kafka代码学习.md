# Kafka 代码学习：Consumer Factory、Listener Factory、错误处理器和发送重试

这份文档专门解释项目里的 Kafka 代码，重点讲清楚下面几个问题：

1. `ConsumerFactory` 是什么。
2. `ConcurrentKafkaListenerContainerFactory` 是什么。
3. `factory.setCommonErrorHandler(...)` 到底做了什么。
4. 消费失败后为什么会重试。
5. 生产者发送失败现在怎么处理，哪里还不够。

对应代码主要在：

- `chatbot-service/src/main/java/com/example/chatbot/kafka/KafkaConsumerConfig.java`
- `chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventConsumer.java`
- `chatbot-service/src/main/java/com/example/chatbot/kafka/ChatEventProducer.java`

## 1. 先搞清楚 Spring Kafka 的几个角色

你可以把 Spring Kafka 消费消息理解成下面这条链路：

```text
Kafka Broker
  -> Consumer
  -> Listener Container
  -> @KafkaListener 方法
  -> 你的业务代码
```

项目里对应关系是：

| 名称 | 作用 | 项目里的代码 |
| --- | --- | --- |
| Kafka Broker | 真正保存消息的 Kafka 服务 | `docker-compose.yml` 里的 kafka 容器 |
| Consumer | 从 Kafka 拉消息的客户端对象 | `ConsumerFactory` 创建 |
| Listener Container | Spring Kafka 包装出来的监听容器，负责拉消息、调用方法、处理 ACK、处理异常 | `ConcurrentKafkaListenerContainerFactory` 创建 |
| `@KafkaListener` | 你写的消费方法入口 | `ChatEventConsumer.onChatEvent` |
| ErrorHandler | 消费异常后的重试、死信处理 | `DefaultErrorHandler` |

关键点：

> 你写的 `@KafkaListener` 方法不是自己主动去 Kafka 拉消息，而是 Spring Kafka 的 listener container 在背后拉消息，然后调用你的方法。

## 2. `ConsumerFactory` 是什么

代码在 `KafkaConsumerConfig.java`：

```java
@Bean
public ConsumerFactory<String, ChatEvent> consumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
    props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
    props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());

    props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.chatbot.kafka");
    props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ChatEvent.class.getName());
    props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

    return new DefaultKafkaConsumerFactory<>(props);
}
```

白话解释：

`ConsumerFactory` 就是 **Kafka Consumer 的工厂**。它不直接消费消息，而是告诉 Spring：

> 如果你要创建 Kafka Consumer，请按照这些配置创建。

这些配置包括：

- 连哪个 Kafka：`BOOTSTRAP_SERVERS_CONFIG`
- 属于哪个消费组：`GROUP_ID_CONFIG`
- key 怎么反序列化
- value 怎么反序列化
- 是否自动提交 offset
- JSON 类型是什么

## 3. `BOOTSTRAP_SERVERS_CONFIG`

```java
props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
```

意思是：

> Kafka 客户端启动时先连接哪个 Kafka 地址。

你的 `application.yml` 里是：

```yaml
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

Docker 里覆盖为：

```yaml
SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:29092
```

所以：

```text
本地运行：localhost:9092
Docker 内运行：kafka:29092
```

## 4. `GROUP_ID_CONFIG`

```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
```

消费组的作用：

> 同一个 group 里的多个消费者会分摊 topic 的 partition 消息。

比如 topic 有 3 个 partition，你配置：

```java
factory.setConcurrency(3);
```

Spring Kafka 可能启动 3 个消费者线程，同一个 group 里一起消费。

一个消息只会被同一个 group 里的一个消费者处理。

## 5. 反序列化配置

你的消息 key 是字符串：

```java
props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
```

你的消息 value 是 JSON：

```java
props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
```

并且告诉 Spring Kafka：

```java
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ChatEvent.class.getName());
```

意思是：

> Kafka 里 value 是 JSON，消费出来后请转成 `ChatEvent` 对象。

## 6. `ErrorHandlingDeserializer` 是什么

你写的是：

```java
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
```

注意：这里不是直接用 `StringDeserializer` 和 `JsonDeserializer`，而是外面套了一层 `ErrorHandlingDeserializer`。

它的作用是：

> 如果反序列化失败，不要让 Kafka Consumer 直接崩掉，而是把异常包装起来交给 Spring Kafka 处理。

举例：

Kafka 里本来应该是一条 `ChatEvent` JSON：

```json
{"sessionId":"1","userMessage":"你好"}
```

结果来了一个坏消息：

```text
abc 不是 json
```

如果没有 `ErrorHandlingDeserializer`，反序列化异常可能会卡住消费流程。

有了它，Spring Kafka 可以把这个异常交给错误处理器。

注意：

| 配置 | 处理什么问题 |
| --- | --- |
| `ErrorHandlingDeserializer` | 反序列化失败 |
| `DefaultErrorHandler` | 业务消费失败，比如 MySQL 写入失败 |

这两个不是一回事。

## 7. 为什么关闭自动提交 offset

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
```

Kafka 消费消息时有一个 offset，表示当前消费到哪里。

如果自动提交，可能出现这种情况：

```text
Consumer 收到消息
  -> offset 自动提交了
  -> 业务代码写 MySQL 失败
  -> Kafka 认为这条消息已经处理过
  -> 这条消息可能丢失
```

所以你的项目关闭自动提交，改成手动 ACK：

```java
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
```

这表示：

> 只有你的代码主动调用 `ack.acknowledge()`，Kafka 才认为这条消息处理成功。

## 8. `ConcurrentKafkaListenerContainerFactory` 是什么

代码：

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, ChatEvent> kafkaListenerContainerFactory(
        @Qualifier("kafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {

    ConcurrentKafkaListenerContainerFactory<String, ChatEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
    factory.setConcurrency(3);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    factory.setCommonErrorHandler(defaultErrorHandler(kafkaTemplate));

    return factory;
}
```

这个名字很长，但拆开就好懂：

```text
Concurrent     支持并发消费
Kafka          Kafka 的
Listener       监听器
Container      容器
Factory        工厂
```

白话：

> 它是用来创建 Kafka 监听容器的工厂。

`@KafkaListener` 方法本身只是一个普通方法，真正负责启动消费者线程、拉消息、调用这个方法、处理 ACK 和异常的是 listener container。

所以这个 factory 的作用是告诉 Spring：

> 你创建监听容器时，请使用我这里配置的 consumerFactory、并发数、ACK 模式、错误处理器。

## 9. `containerFactory = "kafkaListenerContainerFactory"`

在消费者代码里：

```java
@KafkaListener(
        topics = KafkaTopicConfig.TOPIC_CHAT_EVENTS,
        groupId = "chatbot-persistence-group",
        containerFactory = "kafkaListenerContainerFactory"
)
public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
    ...
}
```

这里的：

```java
containerFactory = "kafkaListenerContainerFactory"
```

意思是：

> 这个 `@KafkaListener` 请使用名叫 `kafkaListenerContainerFactory` 的监听容器工厂。

所以 `onChatEvent` 才会使用你配置的：

- `ConsumerFactory<String, ChatEvent>`
- `concurrency = 3`
- `MANUAL_IMMEDIATE`
- `DefaultErrorHandler`

如果没有指定这个 containerFactory，就可能使用 Spring Boot 默认的 Kafka listener factory。

## 10. `setConcurrency(3)` 是什么

```java
factory.setConcurrency(3);
```

意思是：

> Spring Kafka 为这个 listener 启动 3 个消费者线程。

但实际并发效果还要看 topic 有几个 partition。

例如：

| partition 数 | concurrency | 实际最多并发 |
| --- | --- | --- |
| 1 | 3 | 1 |
| 3 | 3 | 3 |
| 6 | 3 | 3 |

因为同一个消费组里，一个 partition 同一时刻只能被一个 consumer 消费。

## 11. 手动 ACK 模式

配置：

```java
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
```

消费代码：

```java
public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
    ...
    chatRecordMapper.insert(chatRecord);
    evictRedisCache(event.getSessionId());
    ack.acknowledge();
}
```

含义：

> 只有执行到 `ack.acknowledge()`，Spring Kafka 才提交 offset。

你的代码里 ACK 在最后：

```java
ack.acknowledge();
```

这说明：

```text
写 MySQL 成功
  -> 删除 Redis 缓存
  -> ACK
```

如果 MySQL 写入失败，程序会在这里抛异常：

```java
chatRecordMapper.insert(chatRecord);
```

后面的：

```java
ack.acknowledge();
```

就不会执行。

这就是为什么失败消息不会被确认。

## 12. `DefaultErrorHandler` 是什么

代码：

```java
private DefaultErrorHandler defaultErrorHandler(KafkaTemplate<String, Object> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
}
```

白话：

> `DefaultErrorHandler` 是 Spring Kafka 提供的默认消费异常处理器。你的 listener 方法抛异常后，它负责决定要不要重试、重试几次、失败后怎么处理。

## 13. `FixedBackOff(1000L, 3L)` 是什么

```java
new FixedBackOff(1000L, 3L)
```

两个参数：

```text
1000L = 每次重试间隔 1000 毫秒
3L    = 最多重试 3 次
```

所以大概流程：

```text
第 1 次消费失败
等待 1 秒
第 1 次重试
等待 1 秒
第 2 次重试
等待 1 秒
第 3 次重试
如果还失败，进入死信队列
```

注意：不同 Spring Kafka 版本对“最大重试次数”的计数描述可能略有差异。你面试时说“失败后按固定间隔最多重试 3 次”就够准确。

## 14. `DeadLetterPublishingRecoverer` 是什么

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
```

白话：

> 它负责把最终处理不了的消息发送到死信 Topic。

默认死信 topic 命名通常是：

```text
原 topic + .DLT
```

如果原 topic 是：

```text
chat.events
```

死信 topic 就是：

```text
chat.events.DLT
```

你的项目里也定义了 DLT 消费者：

```java
@KafkaListener(
        topics = KafkaTopicConfig.TOPIC_DLT,
        groupId = "chatbot-dlt-group",
        containerFactory = "dltContainerFactory"
)
public void onDLTMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
    log.error("【DLT】消息进入死信队列，Partition: {}, Offset: {}, Key: {}, Value: {}",
            record.partition(), record.offset(), record.key(), record.value());

    ack.acknowledge();
}
```

这个方法目前只做了：

```text
记录日志
手动 ACK
```

生产环境可以扩展为：

```text
写入死信审计表
发送告警
人工补偿
```

## 15. 消费失败重试的完整流程

以 MySQL 写入失败为例：

```text
Kafka 有一条 chat.events 消息
  -> Spring Kafka listener container 拉到消息
  -> 调用 ChatEventConsumer.onChatEvent
  -> chatRecordMapper.insert(chatRecord) 写 MySQL
  -> MySQL 异常
  -> onChatEvent 抛异常
  -> ack.acknowledge() 没有执行
  -> Spring Kafka 把异常交给 DefaultErrorHandler
  -> DefaultErrorHandler 等 1 秒后重试
  -> 最多重试 3 次
  -> 如果重试成功，执行 ACK，offset 提交
  -> 如果一直失败，DeadLetterPublishingRecoverer 发送到 chat.events.DLT
  -> onDLTMessage 消费死信消息并记录日志
```

你要记住一句：

> 消费失败能重试，是因为业务异常没有被吞掉，而且 ACK 放在业务成功之后，异常会交给 `DefaultErrorHandler`。

## 16. 为什么 Redis 删除失败不触发重试

消费者里有一步：

```java
evictRedisCache(event.getSessionId());
```

这个方法内部是：

```java
try {
    String key = "chat:history:" + sessionId;
    redisTemplate.delete(key);
} catch (Exception e) {
    log.warn("Redis 缓存删除失败...");
}
```

因为它自己 catch 了异常，所以 Redis 删除失败不会抛出去。

结果是：

```text
MySQL 写入失败 -> 抛异常 -> Kafka 重试
Redis 删除失败 -> 只打印日志 -> 不重试 -> 继续 ACK
```

这是一个设计取舍：

- MySQL 是最终数据源，必须成功。
- Redis 是缓存，失败可以短暂容忍。

面试可以说：

> 我让数据库写入失败触发 Kafka 重试，但 Redis 删除失败不会阻塞主流程，因为 Redis 是缓存层。生产环境可以进一步做延迟双删或缓存补偿任务。

## 17. 生产者发送消息的代码

代码在 `ChatEventProducer.java`：

```java
public void sendChatEvent(ChatEvent event) {
    String key = event.getSessionId();

    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        try {
            SendResult<String, ChatEvent> result =
                    kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, key, event)
                            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            log.info("【Kafka Producer】消息发送成功，Partition: {}, Offset: {}, SessionId: {}",
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset(),
                    event.getSessionId());
            return;

        } catch (Exception e) {
            log.warn("【Kafka Producer】消息发送失败，第 {}/{} 次重试，SessionId: {}",
                    attempt, MAX_RETRIES, event.getSessionId(), e);

            if (attempt == MAX_RETRIES) {
                log.error("【Kafka Producer】消息发送最终失败，SessionId: {}，消息将丢失。生产环境应写入本地消息表做补偿",
                        event.getSessionId());
            }
        }
    }
}
```

## 18. `KafkaTemplate.send(...).get(...)` 是什么意思

```java
kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, key, event)
        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
```

拆开看：

```java
kafkaTemplate.send(topic, key, event)
```

意思是发送消息到 Kafka。

返回值是一个异步结果。你后面调用：

```java
.get(5, TimeUnit.SECONDS)
```

意思是：

> 我最多阻塞等 5 秒，等待 Kafka 返回发送结果。

如果 5 秒内发送成功，就拿到 `SendResult`。

如果失败，可能抛异常，比如：

- Kafka 不可用
- 网络超时
- topic 异常
- 序列化失败
- broker 没响应

然后进入 catch。

## 19. 生产者现在怎么处理发送失败

代码：

```java
for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
    try {
        ...
        return;
    } catch (Exception e) {
        ...
        if (attempt == MAX_RETRIES) {
            log.error("消息将丢失。生产环境应写入本地消息表做补偿");
        }
    }
}
```

你的处理是：

```text
最多尝试 3 次
每次最多等 5 秒
成功就 return
失败就打印日志
3 次都失败后打印 error
```

这个是“基础处理”，但不是生产级可靠处理。

## 20. 生产者目前的不足

如果 Kafka 临时挂了：

```text
模型已经回答完
  -> sendChatEvent
  -> Kafka 不可用
  -> 重试 3 次都失败
  -> 只打印日志
  -> 方法结束
  -> 消息没有进入 Kafka
  -> 消费者永远收不到
  -> MySQL 没有聊天记录
```

所以你不能说：

> 生产者保证消息不丢。

你应该说：

> 生产者侧做了 3 次同步重试，但最终失败后没有落库补偿，所以生产级还应该引入本地消息表或 Outbox Pattern。

## 21. 什么是 Outbox Pattern

Outbox Pattern 可以理解成：

> 先把要发送的消息写进本地数据库，再慢慢补偿发送到 Kafka。

流程：

```text
聊天完成
  -> 保存 ChatEvent 到 outbox_message 表，状态 PENDING
  -> 后台任务扫描 PENDING 消息
  -> 发送 Kafka
  -> 发送成功后改成 SENT
  -> 发送失败则增加 retry_count，下次继续重试
  -> 多次失败后标记 FAILED 并告警
```

这样 Kafka 暂时挂了也没关系，因为消息已经在数据库里，不会丢。

## 22. 如果要改造，你可以怎么设计

可以新增一张表：

```sql
CREATE TABLE outbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128),
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME,
    created_time DATETIME NOT NULL,
    updated_time DATETIME NOT NULL
);
```

发送流程：

```text
ChatbotService
  -> 先 insert outbox_message
  -> 返回用户响应不阻塞
  -> 定时任务发送 Kafka
  -> 成功标记 SENT
```

面试说法：

> 我当前版本做了 producer 重试，但最终失败只打日志。生产级我会加 Outbox 表，把待发送事件先持久化，再由定时任务或后台线程补偿发送 Kafka。

## 23. 你项目目前三类 Consumer 的差异

你项目里有三类事件：

| 事件 | Container Factory | 是否配置 `DefaultErrorHandler` |
| --- | --- | --- |
| `ChatEvent` | `kafkaListenerContainerFactory` | 是 |
| `KnowledgeEvent` | `knowledgeKafkaListenerContainerFactory` | 否 |
| `NotificationEvent` | `notificationKafkaListenerContainerFactory` | 否 |

所以准确说法是：

> 当前只有聊天事件消费者配置了业务异常重试和 DLT。知识库事件、通知事件配置了手动 ACK 和反序列化异常包装，但还没有统一接入 `DefaultErrorHandler`。

## 24. 一段可以背的解释

> 我的聊天事件消费者使用了自定义的 `kafkaListenerContainerFactory`。这个 factory 里配置了 consumerFactory、并发数、手动 ACK，以及 `DefaultErrorHandler`。消费者关闭自动提交 offset，只有业务处理完成后才调用 `ack.acknowledge()`。如果 MySQL 写入失败，异常不会被业务代码吞掉，ACK 也不会执行，Spring Kafka 会把异常交给 `DefaultErrorHandler`。错误处理器通过 `FixedBackOff(1000L, 3L)` 做固定间隔重试，重试耗尽后通过 `DeadLetterPublishingRecoverer` 把消息发送到 DLT。生产者侧现在通过 `KafkaTemplate.send(...).get(...)` 同步等待发送结果，失败最多重试 3 次，但最终失败只记录日志，后续可以用 Outbox Pattern 做可靠补偿。

## 25. 最小记忆版

如果你只能记五句话，记这五句：

1. `ConsumerFactory` 负责创建 Kafka Consumer，配置 Kafka 地址、消费组、反序列化、是否自动提交 offset。
2. `ConcurrentKafkaListenerContainerFactory` 负责创建监听容器，配置并发数、ACK 模式、错误处理器。
3. `@KafkaListener(containerFactory = "...")` 表示这个消费方法使用指定的监听容器配置。
4. 消费失败会重试，是因为 ACK 放在业务成功之后，异常抛给了 `DefaultErrorHandler`。
5. 生产者现在做了 3 次同步重试，但最终失败没有落库补偿，生产级应加 Outbox。
