package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.ChatEventOutbox;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.kafka.ChatEvent;
import com.example.chatbot.mapper.ChatEventOutboxMapper;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatRecordPersistenceService 单元测试
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatRecordPersistenceServiceTest {

    @Mock
    private ChatRecordMapper chatRecordMapper;

    @Mock
    private ChatEventOutboxMapper outboxMapper;

    @Mock
    private ObjectMapper objectMapper;

    private ChatRecordPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        persistenceService = new ChatRecordPersistenceService(chatRecordMapper, outboxMapper, objectMapper);
    }

    private ChatEvent buildTestEvent() {
        return ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId("test-event-id-001")
                .sessionId("test-session-001")
                .userMessage("你好")
                .botResponse("你好！有什么可以帮助你的？")
                .userId("1")
                .eventTime(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("正常保存：chat_record 和 outbox 都写入成功")
    void saveChatAndOutbox_success() throws JsonProcessingException {
        ChatEvent event = buildTestEvent();

        // 模拟 chat_record 不存在
        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        // 模拟 JSON 序列化
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ChatRecord result = persistenceService.saveChatAndOutbox(event);

        // 验证 chat_record 写入
        ArgumentCaptor<ChatRecord> recordCaptor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(recordCaptor.capture());
        ChatRecord savedRecord = recordCaptor.getValue();
        assertEquals("test-session-001", savedRecord.getSessionId());
        assertEquals("test-event-id-001", savedRecord.getEventId());
        assertEquals("你好", savedRecord.getUserMessage());

        // 验证 outbox 写入
        ArgumentCaptor<ChatEventOutbox> outboxCaptor = ArgumentCaptor.forClass(ChatEventOutbox.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        ChatEventOutbox savedOutbox = outboxCaptor.getValue();
        assertEquals("test-event-id-001", savedOutbox.getEventId());
        assertEquals("PENDING", savedOutbox.getStatus());
        assertEquals(0, savedOutbox.getRetryCount());

        assertNotNull(result);
    }

    @Test
    @DisplayName("幂等检查：eventId 已存在时跳过保存")
    void saveChatAndOutbox_idempotent() throws JsonProcessingException {
        ChatEvent event = buildTestEvent();

        // 模拟 chat_record 已存在
        ChatRecord existedRecord = ChatRecord.builder()
                .id(100L)
                .sessionId("test-session-001")
                .eventId("test-event-id-001")
                .build();
        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existedRecord);

        ChatRecord result = persistenceService.saveChatAndOutbox(event);

        // 验证没有插入
        verify(chatRecordMapper, never()).insert(any(ChatRecord.class));
        verify(outboxMapper, never()).insert(any(ChatEventOutbox.class));

        // 返回已存在的记录
        assertEquals(existedRecord, result);
    }

    @Test
    @DisplayName("自动生成 eventId：当 eventId 为空时")
    void saveChatAndOutbox_autoGenerateEventId() throws JsonProcessingException {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId(null)  // 空 eventId
                .sessionId("test-session-002")
                .userMessage("测试")
                .botResponse("响应")
                .eventTime(LocalDateTime.now())
                .build();

        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ChatRecord result = persistenceService.saveChatAndOutbox(event);

        // 验证 eventId 被自动生成
        assertNotNull(event.getEventId());
        assertFalse(event.getEventId().isEmpty());

        // 验证写入使用了自动生成的 eventId
        ArgumentCaptor<ChatRecord> recordCaptor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(recordCaptor.capture());
        assertEquals(event.getEventId(), recordCaptor.getValue().getEventId());
    }

    @Test
    @DisplayName("清理 botResponse：SSE 协议残留清洗")
    void saveChatAndOutbox_cleanBotResponse() throws JsonProcessingException {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId("test-event-id-003")
                .sessionId("test-session-003")
                .userMessage("测试")
                .botResponse("data:{\"content\":\"清洗后的内容\"}")
                .eventTime(LocalDateTime.now())
                .build();

        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        persistenceService.saveChatAndOutbox(event);

        ArgumentCaptor<ChatRecord> recordCaptor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(recordCaptor.capture());
        assertEquals("清洗后的内容", recordCaptor.getValue().getBotResponse());
    }

    @Test
    @DisplayName("图片数据：fileKey 模式")
    void saveChatAndOutbox_imageData_fileKeyMode() throws JsonProcessingException {
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId("test-event-id-004")
                .sessionId("test-session-004")
                .userMessage("描述图片")
                .botResponse("这是一张图片")
                .imageFileKey("abc123")
                .eventTime(LocalDateTime.now())
                .build();

        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        persistenceService.saveChatAndOutbox(event);

        ArgumentCaptor<ChatRecord> recordCaptor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(recordCaptor.capture());
        assertEquals("filekey:abc123", recordCaptor.getValue().getImageData());
    }

    @Test
    @DisplayName("图片数据：imageBytes 不写入 Base64")
    void saveChatAndOutbox_imageData_base64Mode() throws JsonProcessingException {
        byte[] fakeImage = new byte[]{(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47};
        ChatEvent event = ChatEvent.builder()
                .eventType("CHAT_COMPLETED")
                .eventId("test-event-id-005")
                .sessionId("test-session-005")
                .userMessage("描述图片")
                .botResponse("这是一张图片")
                .imageBytes(fakeImage)
                .imageMimeType("image/png")
                .eventTime(LocalDateTime.now())
                .build();

        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        persistenceService.saveChatAndOutbox(event);

        ArgumentCaptor<ChatRecord> recordCaptor = ArgumentCaptor.forClass(ChatRecord.class);
        verify(chatRecordMapper).insert(recordCaptor.capture());
        assertNull(recordCaptor.getValue().getImageData());
    }

    @Test
    @DisplayName("JSON 序列化失败时抛异常，由事务回滚 chat_record")
    void saveChatAndOutbox_serializeFailureThrows() throws JsonProcessingException {
        ChatEvent event = buildTestEvent();

        when(chatRecordMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("serialize failed") {});

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> persistenceService.saveChatAndOutbox(event));

        assertTrue(exception.getMessage().contains("Serialize ChatEvent failed"));
        verify(chatRecordMapper).insert(any(ChatRecord.class));
        verify(outboxMapper, never()).insert(any(ChatEventOutbox.class));
    }
}
