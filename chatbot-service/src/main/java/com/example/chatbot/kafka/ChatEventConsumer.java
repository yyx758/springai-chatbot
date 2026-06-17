package com.example.chatbot.kafka;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 聊天事件消费者。
 * 负责从 Kafka 消费聊天事件，验证记录存在性，处理异步副作用。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventConsumer {

    private final ChatRecordMapper chatRecordMapper;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_CHAT_EVENTS,
            groupId = "chatbot-persistence-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        log.info("【Kafka Consumer】收到消息，Partition: {}, Offset: {}, SessionId: {}",
                record.partition(), record.offset(), event.getSessionId());

        // 验证 chat_record 是否已存在（由 ChatRecordPersistenceService 在事务内写入）
        if (event.getEventId() != null && !event.getEventId().isBlank()) {
            ChatRecord existed = chatRecordMapper.selectOne(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getEventId, event.getEventId())
                    .last("LIMIT 1"));
            if (existed == null) {
                log.warn("【Kafka Consumer】chat_record 不存在，EventId: {}, SessionId: {}，将重试",
                        event.getEventId(), event.getSessionId());
                throw new RuntimeException("chat_record 不存在: " + event.getEventId());
            }
        }

        // TODO: 处理异步副作用（摘要刷新、统计、审计等）
        // 第一版最小实现：仅验证记录存在性

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
}
