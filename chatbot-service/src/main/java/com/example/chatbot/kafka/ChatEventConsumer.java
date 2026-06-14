package com.example.chatbot.kafka;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.service.ChatContextService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 聊天事件消费者。
 * 负责从 Kafka 消费聊天事件并持久化到 MySQL，同时维护分层上下文缓存。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventConsumer {

    private final ChatRecordMapper chatRecordMapper;
    private final ChatContextService chatContextService;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_CHAT_EVENTS,
            groupId = "chatbot-persistence-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        log.info("【Kafka Consumer】收到消息，Partition: {}, Offset: {}, SessionId: {}",
                record.partition(), record.offset(), event.getSessionId());

        String cleanBotRes = cleanBotResponse(event.getBotResponse());
        String dataUri = resolveImageData(event);
        if (event.getEventId() != null && !event.getEventId().isBlank()) {
            ChatRecord existed = chatRecordMapper.selectOne(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getEventId, event.getEventId())
                    .last("LIMIT 1"));
            if (existed != null) {
                log.info("【Kafka Consumer】重复事件已跳过，EventId: {}, RecordId: {}",
                        event.getEventId(), existed.getId());
                ack.acknowledge();
                return;
            }
        }

        ChatRecord chatRecord = ChatRecord.builder()
                .sessionId(event.getSessionId())
                .eventId(event.getEventId())
                .userId(resolveUserId(event))
                .userMessage(event.getUserMessage())
                .botResponse(cleanBotRes)
                .imageData(dataUri)
                .createdTime(LocalDateTime.now())
                .build();
        chatRecordMapper.insert(chatRecord);
        log.info("【Kafka Consumer】MySQL 持久化成功，RecordId: {}, SessionId: {}",
                chatRecord.getId(), event.getSessionId());

        try {
            chatContextService.appendPersistedRecordToCache(chatRecord);
        } catch (Exception e) {
            log.warn("【Kafka Consumer】上下文缓存更新失败，SessionId: {}, Error: {}",
                    event.getSessionId(), e.getMessage());
        }

        ack.acknowledge();
        log.info("【Kafka Consumer】消息处理完成并 ACK，SessionId: {}", event.getSessionId());
    }

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

    private String resolveImageData(ChatEvent event) {
        if (event.getImageFileKey() != null && !event.getImageFileKey().isBlank()) {
            log.info("【Kafka Consumer】使用 fileKey 模式: {}", event.getImageFileKey());
            return "filekey:" + event.getImageFileKey();
        }
        if (event.getImageBytes() != null && event.getImageBytes().length > 0) {
            log.warn("【Kafka Consumer】忽略 imageBytes 入库，请优先使用 fileKey 多模态链路，SessionId: {}",
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
}
