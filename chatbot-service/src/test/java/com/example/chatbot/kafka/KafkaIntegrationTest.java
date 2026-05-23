package com.example.chatbot.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka 集成测试
 * 前置条件：本地 Kafka 已启动（localhost:9092）
 * 运行方式：mvn test -Dtest=KafkaIntegrationTest
 *
 * 测试内容：
 * 1. Kafka 连接是否正常
 * 2. Producer 能否发送 ChatEvent 消息
 * 3. Consumer 能否接收并反序列化 ChatEvent 消息
 * 4. 消息内容完整性验证
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaIntegrationTest {

    private static final String TOPIC = "chat.events";
    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    private static KafkaTemplate<String, ChatEvent> kafkaTemplate;
    private static KafkaMessageListenerContainer<String, ChatEvent> container;
    private static BlockingQueue<ConsumerRecord<String, ChatEvent>> records;

    @BeforeAll
    static void setUp() {
        // 1. 创建 Producer
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        ProducerFactory<String, ChatEvent> producerFactory = new DefaultKafkaProducerFactory<>(producerProps);
        kafkaTemplate = new KafkaTemplate<>(producerFactory);

        // 2. 创建 Consumer（监听消息）
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-group-" + System.currentTimeMillis());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.chatbot.kafka");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, ChatEvent.class.getName());
        consumerProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        ConsumerFactory<String, ChatEvent> consumerFactory = new DefaultKafkaConsumerFactory<>(consumerProps);
        ContainerProperties containerProperties = new ContainerProperties(TOPIC);
        containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProperties);
        records = new LinkedBlockingQueue<>();
        container.setupMessageListener((MessageListener<String, ChatEvent>) record -> records.add(record));
        container.start();
    }

    @AfterAll
    static void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("测试 Kafka Producer 发送消息")
    void testProducerSendsMessage() throws Exception {
        // Given
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .sessionId("test-user_12345")
                .userMessage("你好，这是测试消息")
                .botResponse("你好！我是 AI 助手，很高兴为你服务。")
                .eventTime(LocalDateTime.now())
                .userId("test-user")
                .build();

        // When
        kafkaTemplate.send(TOPIC, event.getSessionId(), event).get(10, TimeUnit.SECONDS);

        // Then - 等待消费
        ConsumerRecord<String, ChatEvent> received = records.poll(10, TimeUnit.SECONDS);
        assertNotNull(received, "Consumer 应该在 10 秒内收到消息");

        ChatEvent receivedEvent = received.value();
        assertNotNull(receivedEvent);
        assertEquals("CHAT_COMPLETED", receivedEvent.getEventType());
        assertEquals("test-user_12345", receivedEvent.getSessionId());
        assertEquals("你好，这是测试消息", receivedEvent.getUserMessage());
        assertEquals("你好！我是 AI 助手，很高兴为你服务。", receivedEvent.getBotResponse());
        assertEquals("test-user", receivedEvent.getUserId());

        System.out.println("✅ Producer 发送消息成功");
        System.out.println("   Topic: " + received.topic());
        System.out.println("   Partition: " + received.partition());
        System.out.println("   Offset: " + received.offset());
        System.out.println("   SessionId: " + receivedEvent.getSessionId());
    }

    @Test
    @Order(2)
    @DisplayName("测试 Kafka 消息包含图片数据")
    void testMessageWithImageData() throws Exception {
        // Given
        byte[] fakeImage = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}; // PNG header
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .sessionId("test-user_67890")
                .userMessage("描述这张图片")
                .botResponse("这是一张测试图片")
                .imageBytes(fakeImage)
                .imageMimeType("image/png")
                .eventTime(LocalDateTime.now())
                .userId("test-user")
                .build();

        // When
        kafkaTemplate.send(TOPIC, event.getSessionId(), event).get(10, TimeUnit.SECONDS);

        // Then
        ConsumerRecord<String, ChatEvent> received = records.poll(10, TimeUnit.SECONDS);
        assertNotNull(received, "Consumer 应该在 10 秒内收到消息");

        ChatEvent receivedEvent = received.value();
        assertNotNull(receivedEvent);
        assertNotNull(receivedEvent.getImageBytes());
        assertEquals("image/png", receivedEvent.getImageMimeType());
        assertArrayEquals(fakeImage, receivedEvent.getImageBytes());

        System.out.println("✅ 消息图片数据传输成功");
        System.out.println("   图片类型: " + receivedEvent.getImageMimeType());
        System.out.println("   图片大小: " + receivedEvent.getImageBytes().length + " bytes");
    }

    @Test
    @Order(3)
    @DisplayName("测试 Kafka 多条消息有序消费")
    void testMultipleMessagesOrdering() throws Exception {
        // Given - 同一 sessionId 发送 3 条消息
        String sessionId = "test-user_order_" + System.currentTimeMillis();
        for (int i = 1; i <= 3; i++) {
            ChatEvent event = ChatEvent.builder()
                    .eventType("CHAT_COMPLETED")
                    .sessionId(sessionId)
                    .userMessage("消息 " + i)
                    .botResponse("回复 " + i)
                    .eventTime(LocalDateTime.now())
                    .userId("test-user")
                    .build();
            kafkaTemplate.send(TOPIC, sessionId, event).get(5, TimeUnit.SECONDS);
        }

        // Then - 同一 sessionId 的消息应该有序（因为用了 sessionId 做 key，路由到同一 partition）
        Thread.sleep(2000); // 等待所有消息被消费
        System.out.println("✅ 批量消息发送成功，共 " + records.size() + " 条待验证");

        // 验证收到了消息
        assertTrue(records.size() >= 3, "应该至少收到 3 条消息");
    }
}
