package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.ChatEventOutbox;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.kafka.ChatEvent;
import com.example.chatbot.mapper.ChatEventOutboxMapper;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 聊天记录持久化服务。
 * 在同一事务内写入 chat_record 和 chat_event_outbox，确保原子性。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRecordPersistenceService {

    private final ChatRecordMapper chatRecordMapper;
    private final ChatEventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;

    /**
     * 在同一事务内保存聊天记录和 outbox 事件。
     *
     * @param event 聊天事件
     * @return 保存的聊天记录
     */
    @Transactional
    public ChatRecord saveChatAndOutbox(ChatEvent event) {
        ensureEventId(event);

        // 幂等检查：如果 eventId 已存在，直接返回
        ChatRecord existed = chatRecordMapper.selectOne(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getEventId, event.getEventId())
                .last("LIMIT 1"));
        if (existed != null) {
            log.info("【Persistence】事件已存在，跳过保存，EventId: {}, RecordId: {}",
                    event.getEventId(), existed.getId());
            return existed;
        }

        // 1. 写入 chat_record
        String cleanBotRes = cleanBotResponse(event.getBotResponse());
        ChatRecord chatRecord = ChatRecord.builder()
                .sessionId(event.getSessionId())
                .eventId(event.getEventId())
                .userId(resolveUserId(event))
                .userMessage(event.getUserMessage())
                .botResponse(cleanBotRes)
                .imageData(resolveImageData(event))
                .createdTime(LocalDateTime.now())
                .build();
        chatRecordMapper.insert(chatRecord);
        log.info("【Persistence】chat_record 写入成功，RecordId: {}, EventId: {}",
                chatRecord.getId(), event.getEventId());

        // 2. 写入 chat_event_outbox
        ChatEventOutbox outbox = ChatEventOutbox.builder()
                .eventId(event.getEventId())
                .sessionId(event.getSessionId())
                .payloadJson(toJson(event))
                .status("PENDING")
                .retryCount(0)
                .nextRetryTime(LocalDateTime.now())
                .createdTime(LocalDateTime.now())
                .build();
        outboxMapper.insert(outbox);
        log.info("【Persistence】chat_event_outbox 写入成功，EventId: {}", event.getEventId());

        return chatRecord;
    }

    private void ensureEventId(ChatEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        }
    }

    private String resolveImageData(ChatEvent event) {
        if (event.getImageFileKey() != null && !event.getImageFileKey().isBlank()) {
            return "filekey:" + event.getImageFileKey();
        }
        if (event.getImageBytes() != null && event.getImageBytes().length > 0) {
            log.warn("【Persistence】忽略 imageBytes 入库，请优先使用 fileKey 多模态链路，SessionId: {}",
                    event.getSessionId());
            return null;
        }
        if (event.getImageData() != null && !event.getImageData().isBlank()) {
            return event.getImageData();
        }
        return null;
    }

    private String cleanBotResponse(String botResponse) {
        if (botResponse == null) {
            return "";
        }
        if (botResponse.contains("data:{\"content\":")) {
            return botResponse.replaceAll("data:\\{\"content\":\"|\"\\}", "");
        }
        return botResponse;
    }

    private Long resolveUserId(ChatEvent event) {
        if (event.getUserId() != null && !event.getUserId().isBlank()) {
            try {
                return Long.valueOf(event.getUserId());
            } catch (NumberFormatException ignored) {
            }
        }
        String sessionId = event.getSessionId();
        if (sessionId == null) {
            return null;
        }
        int split = sessionId.indexOf('_');
        if (split <= 0) {
            return null;
        }
        try {
            return Long.valueOf(sessionId.substring(0, split));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String toJson(ChatEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("【Persistence】序列化 ChatEvent 失败，EventId: {}", event.getEventId(), e);
            throw new IllegalStateException("Serialize ChatEvent failed, eventId=" + event.getEventId(), e);
        }
    }
}
