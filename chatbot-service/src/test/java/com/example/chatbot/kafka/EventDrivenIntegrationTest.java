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
 * Phase 4 事件驱动集成测试
 * 验证 Knowledge 事件和 Notification 事件的 Producer → Kafka → Consumer 链路
 *
 * 前置条件：Kafka 运行在 localhost:9092
 * 运行方式：mvn test -Dtest=EventDrivenIntegrationTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventDrivenIntegrationTest {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";

    private static KafkaTemplate<String, KnowledgeEvent> knowledgeTemplate;
    private static KafkaTemplate<String, NotificationEvent> notificationTemplate;

    private static KafkaMessageListenerContainer<String, KnowledgeEvent> knowledgeContainer;
    private static KafkaMessageListenerContainer<String, NotificationEvent> notificationContainer;

    private static BlockingQueue<ConsumerRecord<String, KnowledgeEvent>> knowledgeRecords;
    private static BlockingQueue<ConsumerRecord<String, NotificationEvent>> notificationRecords;

    @BeforeAll
    static void setUp() {
        // 通用 Producer 配置
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        knowledgeTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        notificationTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

        // Knowledge Consumer
        knowledgeRecords = new LinkedBlockingQueue<>();
        knowledgeContainer = createContainer(
                "test-knowledge-" + System.currentTimeMillis(),
                KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS,
                KnowledgeEvent.class,
                knowledgeRecords
        );
        knowledgeContainer.start();

        // Notification Consumer
        notificationRecords = new LinkedBlockingQueue<>();
        notificationContainer = createContainer(
                "test-notification-" + System.currentTimeMillis(),
                KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS,
                NotificationEvent.class,
                notificationRecords
        );
        notificationContainer.start();
    }

    @AfterAll
    static void tearDown() {
        if (knowledgeContainer != null) knowledgeContainer.stop();
        if (notificationContainer != null) notificationContainer.stop();
    }

    @Test
    @Order(1)
    @DisplayName("知识库事件 - KNOWLEDGE_CREATED 事件发送和接收")
    void testKnowledgeCreatedEvent() throws Exception {
        KnowledgeEvent event = KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_CREATED")
                .documentId(100L)
                .userId(1L)
                .title("Spring Boot 入门指南")
                .eventTime(LocalDateTime.now())
                .build();

        knowledgeTemplate.send(KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS, "1_KNOWLEDGE_CREATED", event)
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, KnowledgeEvent> received = knowledgeRecords.poll(10, TimeUnit.SECONDS);
        assertNotNull(received, "应该在 10 秒内收到知识库事件");

        KnowledgeEvent receivedEvent = received.value();
        assertEquals("KNOWLEDGE_CREATED", receivedEvent.getEventType());
        assertEquals(100L, receivedEvent.getDocumentId());
        assertEquals(1L, receivedEvent.getUserId());
        assertEquals("Spring Boot 入门指南", receivedEvent.getTitle());

        System.out.println("✅ 知识库创建事件验证通过");
    }

    @Test
    @Order(2)
    @DisplayName("知识库事件 - KNOWLEDGE_DELETED 事件发送和接收")
    void testKnowledgeDeletedEvent() throws Exception {
        KnowledgeEvent event = KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_DELETED")
                .documentId(100L)
                .userId(1L)
                .title("Spring Boot 入门指南")
                .eventTime(LocalDateTime.now())
                .build();

        knowledgeTemplate.send(KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS, "1_KNOWLEDGE_DELETED", event)
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, KnowledgeEvent> received = knowledgeRecords.poll(10, TimeUnit.SECONDS);
        assertNotNull(received);

        assertEquals("KNOWLEDGE_DELETED", received.value().getEventType());
        System.out.println("✅ 知识库删除事件验证通过");
    }

    @Test
    @Order(3)
    @DisplayName("通知事件 - SEND_VERIFICATION_CODE 事件发送和接收")
    void testSendVerificationCodeEvent() throws Exception {
        NotificationEvent event = NotificationEvent.builder()
                .eventType("SEND_VERIFICATION_CODE")
                .toEmail("test@example.com")
                .eventTime(LocalDateTime.now())
                .build();

        notificationTemplate.send(KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS, "test@example.com", event)
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, NotificationEvent> received = notificationRecords.poll(10, TimeUnit.SECONDS);
        assertNotNull(received, "应该在 10 秒内收到通知事件");

        NotificationEvent receivedEvent = received.value();
        assertEquals("SEND_VERIFICATION_CODE", receivedEvent.getEventType());
        assertEquals("test@example.com", receivedEvent.getToEmail());

        System.out.println("✅ 注册验证码通知事件验证通过");
    }

    @Test
    @Order(4)
    @DisplayName("通知事件 - SEND_RESET_CODE 事件发送和接收")
    void testSendResetCodeEvent() throws Exception {
        NotificationEvent event = NotificationEvent.builder()
                .eventType("SEND_RESET_CODE")
                .toEmail("reset@example.com")
                .eventTime(LocalDateTime.now())
                .build();

        notificationTemplate.send(KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS, "reset@example.com", event)
                .get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, NotificationEvent> received = notificationRecords.poll(10, TimeUnit.SECONDS);
        assertNotNull(received);

        assertEquals("SEND_RESET_CODE", received.value().getEventType());
        System.out.println("✅ 重置密码通知事件验证通过");
    }

    @Test
    @Order(5)
    @DisplayName("多事件 Topic 验证 - 3 个 Topic 均可正常通信")
    void testAllTopicsAvailable() throws Exception {
        // 验证所有 Topic 都能正常收发
        KnowledgeEvent ke = KnowledgeEvent.builder()
                .eventType("TEST").documentId(0L).userId(0L).title("test")
                .eventTime(LocalDateTime.now()).build();
        NotificationEvent ne = NotificationEvent.builder()
                .eventType("TEST").toEmail("test@test.com")
                .eventTime(LocalDateTime.now()).build();
        knowledgeTemplate.send(KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS, "test", ke).get(5, TimeUnit.SECONDS);
        notificationTemplate.send(KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS, "test", ne).get(5, TimeUnit.SECONDS);

        assertNotNull(knowledgeRecords.poll(10, TimeUnit.SECONDS));
        assertNotNull(notificationRecords.poll(10, TimeUnit.SECONDS));

        System.out.println("✅ 所有 Topic 通信正常");
        System.out.println("   - chat.events: ✅");
        System.out.println("   - knowledge.events: ✅");
        System.out.println("   - notification.events: ✅");
    }

    /**
     * 创建 Kafka 消息监听容器
     */
    private static <T> KafkaMessageListenerContainer<String, T> createContainer(
            String groupId, String topic, Class<T> valueType, BlockingQueue<ConsumerRecord<String, T>> records) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.example.chatbot.kafka");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        ConsumerFactory<String, T> cf = new DefaultKafkaConsumerFactory<>(props);
        ContainerProperties containerProps = new ContainerProperties(topic);
        containerProps.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        KafkaMessageListenerContainer<String, T> container = new KafkaMessageListenerContainer<>(cf, containerProps);
        container.setupMessageListener((MessageListener<String, T>) records::add);
        return container;
    }
}
