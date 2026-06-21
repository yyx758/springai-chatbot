package com.example.chatbot.kafka;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.service.ChatContextService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KafkaReliabilityTest {

    @Mock
    private ChatRecordMapper chatRecordMapper;
    @Mock
    private ChatContextService chatContextService;
    @Mock
    private Acknowledgment acknowledgment;

    private ChatEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ChatEventConsumer(chatRecordMapper, chatContextService);
    }

    @Test
    @DisplayName("Consumer ACKs after persisted record exists and context side effects are triggered")
    void consumer_ackWhenPersistedRecordExists() {
        ChatEvent event = buildTestEvent();
        ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
                "chat.events", 0, 0L, "test-session-001", event);
        ChatRecord existedRecord = ChatRecord.builder()
                .id(100L)
                .sessionId("test-session-001")
                .eventId(event.getEventId())
                .build();
        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existedRecord);

        consumer.onChatEvent(record, acknowledgment);

        verify(chatRecordMapper, never()).insert(any(ChatRecord.class));
        verify(chatContextService).appendPersistedRecordToCacheStrict(existedRecord);
        verify(chatContextService).refreshSummaryIfNeededAsync(existedRecord.getSessionId());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Consumer throws when persisted record is missing so DefaultErrorHandler can retry")
    void consumer_throwWhenPersistedRecordMissing() {
        ChatEvent event = buildTestEvent();
        ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
                "chat.events", 0, 0L, "test-session-002", event);
        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThrows(RuntimeException.class, () -> consumer.onChatEvent(record, acknowledgment));

        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("Consumer throws when eventId is missing so invalid events are retried or sent to DLT")
    void consumer_throwWhenEventIdMissing() {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .sessionId("legacy-session")
                .userMessage("legacy")
                .botResponse("legacy")
                .eventTime(LocalDateTime.now())
                .build();
        ConsumerRecord<String, ChatEvent> record = new ConsumerRecord<>(
                "chat.events", 0, 0L, "legacy-session", event);

        assertThrows(RuntimeException.class, () -> consumer.onChatEvent(record, acknowledgment));

        verify(chatRecordMapper, never()).insert(any(ChatRecord.class));
        verify(acknowledgment, never()).acknowledge();
    }

    private ChatEvent buildTestEvent() {
        return ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId("test-event-id-001")
                .sessionId("test-session-001")
                .userMessage("hello")
                .botResponse("hello, how can I help?")
                .eventTime(LocalDateTime.now())
                .userId("user-1")
                .build();
    }
}
