package com.example.chatbot.kafka;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.service.ChatContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

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
        log.info("Chat event received, partition={}, offset={}, sessionId={}",
                record.partition(), record.offset(), event.getSessionId());

        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new RuntimeException("chat eventId is empty, sessionId=" + event.getSessionId());
        }

        ChatRecord persistedRecord = chatRecordMapper.selectOne(new LambdaQueryWrapper<ChatRecord>()
                .eq(ChatRecord::getEventId, event.getEventId())
                .last("LIMIT 1"));
        if (persistedRecord == null) {
            log.warn("chat_record not found, eventId={}, sessionId={}, retrying",
                    event.getEventId(), event.getSessionId());
            throw new RuntimeException("chat_record not found: " + event.getEventId());
        }

        chatContextService.appendPersistedRecordToCacheStrict(persistedRecord);
        chatContextService.refreshSummaryIfNeededAsync(persistedRecord.getSessionId());
        ack.acknowledge();
        log.info("Chat event processed and ACKed, sessionId={}, recordId={}",
                persistedRecord.getSessionId(), persistedRecord.getId());
    }

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_DLT,
            groupId = "chatbot-dlt-group",
            containerFactory = "dltContainerFactory"
    )
    public void onDLTMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("DLT message received, partition={}, offset={}, key={}, value={}",
                record.partition(), record.offset(), record.key(), record.value());
        ack.acknowledge();
    }
}
