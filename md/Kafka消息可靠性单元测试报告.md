# Kafka 消息可靠性单元测试报告

## 一、测试背景

### 1.1 改造目标

将 Kafka 消息处理从"异步回调 + 手动 try-catch"改造为"同步发送 + 重试 + 死信队列"，提升消息可靠性：

| 改造前 | 改造后 |
|--------|--------|
| `CompletableFuture` 异步回调，失败只记日志 | 同步发送 + 3 次重试（5s 超时） |
| 消费者手动 try-catch + 失败也 ACK | `DefaultErrorHandler` 接管，失败重试 3 次后发往 DLT |
| Redis 更新缓存（可能写入脏数据） | Redis 删除缓存（Cache-Aside，保证最终一致性） |
| 无死信队列 | 新增 `chat.events.DLT` 死信 Topic + DLT 消费者 |

### 1.2 测试范围

- `ChatEventProducer` — 生产者同步发送 + 重试逻辑
- `ChatEventConsumer` — 消费者正常消费、异常传播、Redis 容错、数据清洗
- 覆盖正常路径、异常路径、边界情况共 9 个测试用例

---

## 二、测试环境

### 2.1 基础设施

| 项目 | 配置 |
|------|------|
| 服务器 | 111.229.127.171（腾讯云轻量，4GB 内存） |
| 操作系统 | Ubuntu |
| Java | 17.0.19 (Eclipse Temurin) |
| Spring Boot | 3.2.0 |
| Spring Kafka | 与 Spring Boot 3.2.0 绑定 |
| Kafka | Confluent 7.5.0（KRaft 模式，单节点） |

### 2.2 测试框架

| 组件 | 版本 | 用途 |
|------|------|------|
| JUnit 5 (Jupiter) | 随 Spring Boot 3.2.0 | 测试引擎、断言、生命周期管理 |
| Mockito | 随 spring-boot-starter-test | Mock 依赖对象，隔离被测代码 |
| MockitoExtension | — | JUnit 5 集成，自动初始化 `@Mock` 注解 |
| MockitoSettings(LENIENT) | — | 允许未使用的 stubbing，避免 `@BeforeEach` 中的预设干扰消费者测试 |

### 2.3 测试类型

**纯单元测试**，不依赖真实 Kafka、MySQL、Redis。所有外部依赖通过 Mockito Mock 注入。

```
┌─────────────────────────────────────┐
│         KafkaReliabilityTest        │
│                                     │
│  @Mock KafkaTemplate<String, ChatEvent>  ← 模拟 Kafka Broker
│  @Mock ChatRecordMapper             ← 模拟 MySQL
│  @Mock RedisTemplate<String, Object>← 模拟 Redis
│  @Mock Acknowledgment               ← 模拟 Kafka ACK
│                                     │
│  被测对象：                          │
│  ├── ChatEventProducer(kafkaTemplate)│
│  └── ChatEventConsumer(mapper, redis)│
└─────────────────────────────────────┘
```

**选择单元测试而非集成测试的原因：**
- 集成测试需要真实 Kafka Broker（`localhost:9092`），在 Docker 容器内无法访问宿主机
- 单元测试隔离性好，只验证业务逻辑，不验证 Kafka 网络通信
- 执行速度快（1.59 秒 vs 集成测试 180 秒超时）

---

## 三、测试用例设计

### 3.1 Mock 策略

#### `@BeforeEach` 公共 Setup

```java
@BeforeEach
void setUp() {
    producer = new ChatEventProducer(kafkaTemplate);
    consumer = new ChatEventConsumer(chatRecordMapper, redisTemplate);

    // 预创建成功 Future（避免在 when() 中嵌套 mock 导致 UnfinishedStubbing）
    RecordMetadata metadata = new RecordMetadata(
            new TopicPartition("chat.events", 0), 0L, 0, 0L, 0, 0);
    SendResult<String, ChatEvent> mockResult = mock(SendResult.class);
    when(mockResult.getRecordMetadata()).thenReturn(metadata);
    successFuture = CompletableFuture.completedFuture(mockResult);
}
```

#### 辅助方法

```java
// 构造标准测试事件
private ChatEvent buildTestEvent() {
    return ChatEvent.builder()
            .eventType("CHAT_COMPLETED")
            .sessionId("test-session-001")
            .userMessage("你好")
            .botResponse("你好！有什么可以帮助你的？")
            .eventTime(LocalDateTime.now())
            .userId("user-1")
            .build();
}

// 构造失败 Future
private CompletableFuture<SendResult<String, ChatEvent>> failFuture(String msg) {
    CompletableFuture<SendResult<String, ChatEvent>> f = new CompletableFuture<>();
    f.completeExceptionally(new RuntimeException(msg));
    return f;
}
```

---

### 3.2 生产者测试用例

#### 测试 1：第 1 次发送成功

| 项目 | 内容 |
|------|------|
| **方法名** | `producer_firstAttemptSuccess` |
| **测试目标** | 验证正常情况下消息一次发送成功 |
| **Mock 设置** | `kafkaTemplate.send()` 返回 `successFuture` |
| **断言** | `verify(kafkaTemplate, times(1)).send(...)` — 只调用 1 次 |
| **预期结果** | 生产者发送成功后立即返回，不触发重试 |

```java
@Test
@DisplayName("生产者：第 1 次发送成功")
void producer_firstAttemptSuccess() {
    ChatEvent event = buildTestEvent();
    when(kafkaTemplate.send(anyString(), anyString(), any(ChatEvent.class)))
            .thenReturn(successFuture);

    producer.sendChatEvent(event);

    verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any(ChatEvent.class));
}
```

#### 测试 2：前 2 次失败，第 3 次成功（重试生效）

| 项目 | 内容 |
|------|------|
| **方法名** | `producer_retryUntilSuccess` |
| **测试目标** | 验证重试机制：前两次失败后自动重试，第三次成功 |
| **Mock 设置** | `kafkaTemplate.send()` 依次返回：fail → fail → success |
| **断言** | `verify(kafkaTemplate, times(3)).send(...)` — 共调用 3 次 |
| **预期结果** | 重试机制生效，第 3 次成功后正常返回 |

```java
@Test
@DisplayName("生产者：前 2 次失败，第 3 次成功（重试机制生效）")
void producer_retryUntilSuccess() {
    ChatEvent event = buildTestEvent();
    when(kafkaTemplate.send(anyString(), anyString(), any(ChatEvent.class)))
            .thenReturn(failFuture("第 1 次失败"))
            .thenReturn(failFuture("第 2 次失败"))
            .thenReturn(successFuture);

    producer.sendChatEvent(event);

    verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(ChatEvent.class));
}
```

#### 测试 3：3 次全部失败，不抛异常（静默失败）

| 项目 | 内容 |
|------|------|
| **方法名** | `producer_allAttemptsFail_noException` |
| **测试目标** | 验证重试耗尽后不向上抛异常（静默失败） |
| **Mock 设置** | `kafkaTemplate.send()` 始终返回 `failFuture` |
| **断言** | `assertDoesNotThrow()` + `verify(times(3))` |
| **预期结果** | 生产者内部捕获异常，不中断业务流程 |

```java
@Test
@DisplayName("生产者：3 次全部失败，不抛异常（静默失败）")
void producer_allAttemptsFail_noException() {
    ChatEvent event = buildTestEvent();
    when(kafkaTemplate.send(anyString(), anyString(), any(ChatEvent.class)))
            .thenReturn(failFuture("Kafka 不可用"));

    assertDoesNotThrow(() -> producer.sendChatEvent(event));

    verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(ChatEvent.class));
}
```

---

### 3.3 消费者测试用例

#### 测试 4：正常消费（MySQL 写入 + Redis 删除）

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_normalFlow` |
| **测试目标** | 验证完整消费链路：MySQL 插入 + Redis 删除 + 手动 ACK |
| **Mock 设置** | `redisTemplate.delete()` 返回 `true` |
| **断言** | 1. `ArgumentCaptor` 捕获 `ChatRecord`，验证字段值<br>2. `verify(redisTemplate).delete("chat:history:test-session-001")`<br>3. `verify(acknowledgment).acknowledge()` |
| **预期结果** | MySQL 写入成功，Redis 缓存已删除，消息已 ACK |

```java
@Test
@DisplayName("消费者：正常消费 — MySQL 写入 + Redis 缓存删除")
void consumer_normalFlow() {
    ChatEvent event = buildTestEvent();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-session-001", event);

    when(redisTemplate.delete(anyString())).thenReturn(true);

    consumer.onChatEvent(record, acknowledgment);

    // 验证 MySQL 写入
    ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
    verify(chatRecordMapper).insert(captor.capture());
    ChatRecord saved = captor.getValue();
    assertEquals("test-session-001", saved.getSessionId());
    assertEquals("你好", saved.getUserMessage());
    assertEquals("你好！有什么可以帮助你的？", saved.getBotResponse());

    // 验证 Redis 缓存删除
    verify(redisTemplate).delete("chat:history:test-session-001");

    // 验证手动 ACK
    verify(acknowledgment).acknowledge();
}
```

#### 测试 5：异常时由 DefaultErrorHandler 接管

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_exceptionPropagatedToErrorHandler` |
| **测试目标** | 验证消费者不再手动 catch，异常上抛给框架 |
| **Mock 设置** | `chatRecordMapper.insert()` 抛出 `RuntimeException` |
| **断言** | 1. `assertThrows(RuntimeException.class, ...)`<br>2. `verify(acknowledgment, never()).acknowledge()`<br>3. `verify(redisTemplate, never()).delete(anyString())` |
| **预期结果** | 异常上抛，不调用 ACK（由框架重试或发往 DLT），Redis 删除未执行 |

```java
@Test
@DisplayName("消费者：异常时由 DefaultErrorHandler 接管，不手动 catch")
void consumer_exceptionPropagatedToErrorHandler() {
    ChatEvent event = buildTestEvent();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-session-002", event);

    when(chatRecordMapper.insert(any(ChatRecord.class)))
            .thenThrow(new RuntimeException("MySQL 连接失败"));

    assertThrows(RuntimeException.class,
            () -> consumer.onChatEvent(record, acknowledgment));

    verify(acknowledgment, never()).acknowledge();
    verify(redisTemplate, never()).delete(anyString());
}
```

#### 测试 6：Redis 删除失败不影响主流程

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_redisFailureDoesNotAffectMainFlow` |
| **测试目标** | 验证 Redis 故障被 catch，MySQL 写入和 ACK 正常执行 |
| **Mock 设置** | `redisTemplate.delete()` 抛出 `RuntimeException` |
| **断言** | 1. `assertDoesNotThrow()`<br>2. `verify(chatRecordMapper).insert(...)`<br>3. `verify(acknowledgment).acknowledge()` |
| **预期结果** | Redis 异常被内部 catch，消息消费成功 |

```java
@Test
@DisplayName("消费者：Redis 删除失败不影响主流程")
void consumer_redisFailureDoesNotAffectMainFlow() {
    ChatEvent event = buildTestEvent();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-session-003", event);

    when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis 超时"));

    assertDoesNotThrow(() -> consumer.onChatEvent(record, acknowledgment));

    verify(chatRecordMapper).insert(any(ChatRecord.class));
    verify(acknowledgment).acknowledge();
}
```

#### 测试 7：SSE 协议残留清洗

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_cleanBotResponse` |
| **测试目标** | 验证 `data:{"content":"..."}` 格式被清洗为纯文本 |
| **输入** | `botResponse = "data:{\"content\":\"清洗后的内容\"}"` |
| **断言** | `assertEquals("清洗后的内容", captor.getValue().getBotResponse())` |
| **预期结果** | SSE 协议前缀和包裹符被正确去除 |

```java
@Test
@DisplayName("消费者：SSE 协议残留清洗")
void consumer_cleanBotResponse() {
    ChatEvent event = ChatEvent.builder()
            .sessionId("test-sse")
            .userMessage("测试")
            .botResponse("data:{\"content\":\"清洗后的内容\"}")
            .eventTime(LocalDateTime.now())
            .build();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-sse", event);

    when(redisTemplate.delete(anyString())).thenReturn(true);

    consumer.onChatEvent(record, acknowledgment);

    ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
    verify(chatRecordMapper).insert(captor.capture());
    assertEquals("清洗后的内容", captor.getValue().getBotResponse());
}
```

#### 测试 8：图片数据 — fileKey 模式

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_imageData_fileKeyMode` |
| **测试目标** | 验证 fileKey 图片模式：存储格式为 `filekey:xxx` |
| **输入** | `imageFileKey = "abc123"` |
| **断言** | `assertEquals("filekey:abc123", captor.getValue().getImageData())` |
| **预期结果** | fileKey 正确转换为 `filekey:` 前缀格式 |

```java
@Test
@DisplayName("消费者：图片数据 — fileKey 模式")
void consumer_imageData_fileKeyMode() {
    ChatEvent event = ChatEvent.builder()
            .sessionId("test-img")
            .userMessage("描述图片")
            .botResponse("这是一张图片")
            .imageFileKey("abc123")
            .eventTime(LocalDateTime.now())
            .build();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-img", event);

    when(redisTemplate.delete(anyString())).thenReturn(true);

    consumer.onChatEvent(record, acknowledgment);

    ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
    verify(chatRecordMapper).insert(captor.capture());
    assertEquals("filekey:abc123", captor.getValue().getImageData());
}
```

#### 测试 9：图片数据 — Base64 模式（兼容旧格式）

| 项目 | 内容 |
|------|------|
| **方法名** | `consumer_imageData_base64Mode` |
| **测试目标** | 验证旧版 byte[] 图片兼容为 Base64 data URI |
| **输入** | `imageBytes = {0x89, 0x50, 0x4E, 0x47}` (PNG header), `imageMimeType = "image/png"` |
| **断言** | `assertTrue(captor.getValue().getImageData().startsWith("data:image/png;base64,"))` |
| **预期结果** | byte[] 正确编码为 `data:image/png;base64,...` 格式 |

```java
@Test
@DisplayName("消费者：图片数据 — Base64 模式（兼容旧格式）")
void consumer_imageData_base64Mode() {
    byte[] fakeImage = new byte[]{(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47};
    ChatEvent event = ChatEvent.builder()
            .sessionId("test-img-b64")
            .userMessage("描述图片")
            .botResponse("这是一张图片")
            .imageBytes(fakeImage)
            .imageMimeType("image/png")
            .eventTime(LocalDateTime.now())
            .build();
    ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
            "chat.events", 0, 0L, "test-img-b64", event);

    when(redisTemplate.delete(anyString())).thenReturn(true);

    consumer.onChatEvent(record, acknowledgment);

    ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
    verify(chatRecordMapper).insert(captor.capture());
    assertTrue(captor.getValue().getImageData().startsWith("data:image/png;base64,"));
}
```

---

## 四、测试结果

### 4.1 执行命令

```bash
# 在远程服务器上启动 Maven 容器执行测试
docker run --rm --name maven-test \
  --network springai-chatbot_default \
  -v /opt/springai-chatbot:/app \
  maven:3.9-eclipse-temurin-17 \
  bash -c 'cd /app && mvn test -Dtest=KafkaReliabilityTest -pl chatbot-service -B'
```

### 4.2 执行结果

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
Time elapsed: 1.590 s
BUILD SUCCESS
```

### 4.3 结果明细

| # | 测试用例 | 类型 | 耗时 | 结果 | 验证点 |
|---|----------|------|------|------|--------|
| 1 | `producer_firstAttemptSuccess` | 生产者 | <0.01s | PASS | `send()` 调用 1 次 |
| 2 | `producer_retryUntilSuccess` | 生产者 | <0.01s | PASS | `send()` 调用 3 次（2 次失败 + 1 次成功） |
| 3 | `producer_allAttemptsFail_noException` | 生产者 | 0.04s | PASS | `send()` 调用 3 次，不抛异常 |
| 4 | `consumer_normalFlow` | 消费者 | 0.006s | PASS | MySQL insert + Redis delete + ACK |
| 5 | `consumer_exceptionPropagatedToErrorHandler` | 消费者 | 0.036s | PASS | 异常上抛，不调用 ACK |
| 6 | `consumer_redisFailureDoesNotAffectMainFlow` | 消费者 | 0.007s | PASS | Redis 异常被 catch，主流程继续 |
| 7 | `consumer_cleanBotResponse` | 消费者 | 0.010s | PASS | SSE 协议残留被清洗 |
| 8 | `consumer_imageData_fileKeyMode` | 消费者 | 0.005s | PASS | fileKey → `filekey:xxx` |
| 9 | `consumer_imageData_base64Mode` | 消费者 | 0.009s | PASS | byte[] → `data:image/png;base64,...` |

### 4.4 日志关键输出

```
ERROR c.e.chatbot.kafka.ChatEventProducer —
【Kafka Producer】消息发送最终失败，SessionId: test-session-001，
消息将丢失。生产环境应写入本地消息表做补偿
```

> 此日志来自测试 3（3 次全部失败），验证了生产者在重试耗尽后输出错误日志而非抛异常。

---

## 五、测试覆盖分析

### 5.1 生产者覆盖

| 路径 | 覆盖 |
|------|------|
| 首次发送成功 | 测试 1 |
| 重试后成功 | 测试 2 |
| 重试耗尽失败 | 测试 3 |
| 发送超时（5s） | 由 `CompletableFuture.get(5, SECONDS)` 控制，集成测试覆盖 |

### 5.2 消费者覆盖

| 路径 | 覆盖 |
|------|------|
| 正常消费（MySQL + Redis + ACK） | 测试 4 |
| MySQL 写入失败（异常传播） | 测试 5 |
| Redis 删除失败（容错） | 测试 6 |
| SSE 协议残留清洗 | 测试 7 |
| 图片 fileKey 模式 | 测试 8 |
| 图片 Base64 兼容模式 | 测试 9 |
| DLT 消费者 | 框架层自动处理，需集成测试验证 |

### 5.3 未覆盖（需集成测试）

| 场景 | 原因 |
|------|------|
| DefaultErrorHandler 重试 + DLT 投递 | 需要真实 Kafka + Spring 容器 |
| DLT 消费者接收死信消息 | 同上 |
| 生产者 → Kafka → 消费者端到端 | 需要完整 Kafka 环境 |

---

## 六、测试中遇到的问题及解决

### 6.1 Bean 冲突：`dltKafkaTemplate` 覆盖自动配置

**问题**：自定义 `@Bean("dltKafkaTemplate") KafkaTemplate<String, Object>` 覆盖了 Spring Boot 自动配置的 `KafkaTemplate`，导致 `NotificationEventProducer` 找不到 Bean。

**解决**：删除自定义 Bean，直接用 `@Qualifier("kafkaTemplate")` 注入 Spring Boot 自动配置的实例。

### 6.2 Mockito `UnfinishedStubbing`

**问题**：`successFuture()` 方法内嵌套 `mock()` + `when()`，在外部 `when().thenReturn()` 中调用时导致 Mockito 上下文冲突。

**解决**：将 `successFuture` 提升为 `@BeforeEach` 中预创建的字段，避免嵌套 stubbing。

### 6.3 Mockito `UnnecessaryStubbing`

**问题**：`@BeforeEach` 中 stub 的 `mockResult.getRecordMetadata()` 在消费者测试中未使用，触发 Strict Stubbing 检查。

**解决**：添加 `@MockitoSettings(strictness = Strictness.LENIENT)` 放宽检查。

### 6.4 集成测试网络隔离

**问题**：原有 `KafkaIntegrationTest` 硬编码 `localhost:9092`，在 Docker 容器内 `localhost` 指向容器自身。

**解决**：集成测试需在宿主机运行（通过 Docker 端口映射访问 Kafka），本次使用纯 Mockito 单元测试替代。

---

## 七、结论

| 维度 | 结论 |
|------|------|
| **生产者重试** | 3 个场景全部通过，首次成功/重试成功/最终失败均符合预期 |
| **消费者可靠性** | 6 个场景全部通过，正常消费/异常传播/Redis 容错/数据清洗均正确 |
| **Cache-Aside** | 写 MySQL 后删除 Redis 缓存，读侧 cache miss 时从 MySQL 加载并回填 |
| **代码质量** | 编译通过，无 Error，仅有 Lombok 空安全 Warning |
| **部署验证** | Docker 构建成功，服务正常启动，模型加载正常 |
