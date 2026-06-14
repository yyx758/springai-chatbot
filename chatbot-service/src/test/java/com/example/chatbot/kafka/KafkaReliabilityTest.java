package com.example.chatbot.kafka;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.service.ChatContextService;
import com.example.chatbot.service.ChatEventOutboxService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kafka 消息可靠性单元测试
 * 不依赖真实 Kafka，使用 Mockito mock 所有依赖
 *
 * 测试内容：
 * 1. 生产者：发送成功（第 1 次成功）
 * 2. 生产者：前 2 次失败、第 3 次成功（重试生效）
 * 3. 生产者：3 次全部失败（最终失败，不抛异常）
 * 4. 消费者：正常消费（MySQL 写入 + 上下文缓存维护）
 * 5. 消费者：消费异常时由 DefaultErrorHandler 接管（不手动 catch）
 * 6. 消费者：上下文缓存维护失败不影响主流程
 * 7. 消费者：SSE 协议残留清洗
 * 8. 消费者：图片数据 fileKey / Base64 模式
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaReliabilityTest {

    @Mock
    private KafkaTemplate<String, ChatEvent> kafkaTemplate;

    @Mock
    private ChatRecordMapper chatRecordMapper;

    @Mock
    private ChatContextService chatContextService;

    @Mock
    private ChatEventOutboxService outboxService;

    @Mock
    private Acknowledgment acknowledgment;

    private ChatEventProducer producer;
    private ChatEventConsumer consumer;
    private CompletableFuture<SendResult<String, ChatEvent>> successFuture;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        producer = new ChatEventProducer(kafkaTemplate, outboxService);
        consumer = new ChatEventConsumer(chatRecordMapper, chatContextService);

        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition("chat.events", 0), 0L, 0, 0L, 0, 0);
        SendResult<String, ChatEvent> mockResult = mock(SendResult.class);
        when(mockResult.getRecordMetadata()).thenReturn(metadata);
        successFuture = CompletableFuture.completedFuture(mockResult);
    }

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

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, ChatEvent>> failFuture(String msg) {
        CompletableFuture<SendResult<String, ChatEvent>> f = new CompletableFuture<>();
        f.completeExceptionally(new RuntimeException(msg));
        return f;
    }

    // ========== 生产者测试 ==========

    @Test
    @DisplayName("生产者：第 1 次发送成功")
    void producer_firstAttemptSuccess() {
        ChatEvent event = buildTestEvent();
        when(kafkaTemplate.send(anyString(), anyString(), any(ChatEvent.class)))
                .thenReturn(successFuture);

        producer.sendChatEvent(event);

        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any(ChatEvent.class));
    }

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

    @Test
    @DisplayName("生产者：3 次全部失败，写入 outbox 等待补偿")
    void producer_allAttemptsFail_noException() {
        ChatEvent event = buildTestEvent();
        when(kafkaTemplate.send(anyString(), anyString(), any(ChatEvent.class)))
                .thenReturn(failFuture("Kafka 不可用"));

        assertDoesNotThrow(() -> producer.sendChatEvent(event));

        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(ChatEvent.class));
        verify(outboxService).savePending(eq(event), anyString());
    }

    // ========== 消费者测试 ==========

    @Test
    @DisplayName("消费者：正常消费 — MySQL 写入 + 上下文缓存维护")
    void consumer_normalFlow() {
        ChatEvent event = buildTestEvent();
        ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
                "chat.events", 0, 0L, "test-session-001", event);

        consumer.onChatEvent(record, acknowledgment);

        // 验证 MySQL 写入
        ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(captor.capture());
        ChatRecord saved = captor.getValue();
        assertEquals("test-session-001", saved.getSessionId());
        assertEquals("你好", saved.getUserMessage());
        assertEquals("你好！有什么可以帮助你的？", saved.getBotResponse());

        verify(chatContextService).appendPersistedRecordToCache(saved);

        // 验证手动 ACK
        verify(acknowledgment).acknowledge();
    }

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
        verify(chatContextService, never()).appendPersistedRecordToCache(any());
    }

    @Test
    @DisplayName("消费者：上下文缓存维护失败不影响主流程")
    void consumer_contextCacheFailureDoesNotAffectMainFlow() {
        ChatEvent event = buildTestEvent();
        ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
                "chat.events", 0, 0L, "test-session-003", event);

        doThrow(new RuntimeException("Redis 超时"))
                .when(chatContextService).appendPersistedRecordToCache(any(ChatRecord.class));

        assertDoesNotThrow(() -> consumer.onChatEvent(record, acknowledgment));

        verify(chatRecordMapper).insert(any(ChatRecord.class));
        verify(acknowledgment).acknowledge();
    }

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

        consumer.onChatEvent(record, acknowledgment);

        ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(captor.capture());
        assertEquals("清洗后的内容", captor.getValue().getBotResponse());
    }

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

        consumer.onChatEvent(record, acknowledgment);

        ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(captor.capture());
        assertEquals("filekey:abc123", captor.getValue().getImageData());
    }

    @Test
    @DisplayName("消费者：图片数据 — imageBytes 不再写入 Base64")
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

        consumer.onChatEvent(record, acknowledgment);

        ArgumentCaptor<ChatRecord> captor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(captor.capture());
        assertNull(captor.getValue().getImageData());
    }
}
