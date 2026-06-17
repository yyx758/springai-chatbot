package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.ChatEventOutbox;
import com.example.chatbot.kafka.ChatEvent;
import com.example.chatbot.kafka.KafkaTopicConfig;
import com.example.chatbot.mapper.ChatEventOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatEventOutboxService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RETRY = "FAILED_RETRY";
    private static final String STATUS_SENT = "SENT";

    private final ChatEventOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, ChatEvent> kafkaTemplate;

    @Value("${app.chat.outbox.batch-size:20}")
    private int batchSize;

    @Value("${app.chat.outbox.send-timeout-seconds:5}")
    private long sendTimeoutSeconds;

    @Scheduled(fixedDelayString = "${app.chat.outbox.dispatch-delay-ms:5000}")
    public void dispatchPendingEvents() {
        List<ChatEventOutbox> events = outboxMapper.selectList(new LambdaQueryWrapper<ChatEventOutbox>()
                .in(ChatEventOutbox::getStatus, List.of(STATUS_PENDING, STATUS_RETRY))
                .and(wrapper -> wrapper.isNull(ChatEventOutbox::getNextRetryTime)
                        .or()
                        .le(ChatEventOutbox::getNextRetryTime, LocalDateTime.now()))
                .orderByAsc(ChatEventOutbox::getId)
                .last("LIMIT " + Math.max(1, batchSize)));
        for (ChatEventOutbox outbox : events) {
            dispatchOne(outbox);
        }
    }

    private void dispatchOne(ChatEventOutbox outbox) {
        try {
            ChatEvent event = objectMapper.readValue(outbox.getPayloadJson(), ChatEvent.class);
            ensureEventId(event);
            kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, event.getSessionId(), event)
                    .get(sendTimeoutSeconds, TimeUnit.SECONDS);
            outbox.setStatus(STATUS_SENT);
            outbox.setSentTime(LocalDateTime.now());
            outbox.setLastError(null);
            outbox.setUpdatedTime(LocalDateTime.now());
            outboxMapper.updateById(outbox);
            log.info("Chat outbox event dispatched, eventId={}, sessionId={}", event.getEventId(), event.getSessionId());
        } catch (Exception e) {
            int retryCount = outbox.getRetryCount() == null ? 0 : outbox.getRetryCount();
            outbox.setStatus(STATUS_RETRY);
            outbox.setRetryCount(retryCount + 1);
            outbox.setLastError(limit(e.getMessage()));
            outbox.setNextRetryTime(LocalDateTime.now().plusSeconds(nextDelaySeconds(retryCount)));
            outbox.setUpdatedTime(LocalDateTime.now());
            outboxMapper.updateById(outbox);
            log.warn("Chat outbox dispatch failed, eventId={}, retryCount={}, error={}",
                    outbox.getEventId(), outbox.getRetryCount(), e.getMessage());
        }
    }

    private long nextDelaySeconds(int retryCount) {
        return Math.min(300, (long) Math.pow(2, Math.min(retryCount, 8)));
    }

    private void ensureEventId(ChatEvent event) {
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            event.setEventId(java.util.UUID.randomUUID().toString().replace("-", ""));
        }
    }

    private String limit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 1000 ? value.substring(0, 1000) : value;
    }
}
