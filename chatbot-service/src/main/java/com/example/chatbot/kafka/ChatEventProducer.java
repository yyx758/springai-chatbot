package com.example.chatbot.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 聊天事件生产者
 * 负责将聊天完成事件发送到 Kafka，替代原来的 @Async 线程池方式
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventProducer {

    private final KafkaTemplate<String, ChatEvent> kafkaTemplate;

    /**
     * 发送聊天完成事件
     *
     * @param event 聊天事件
     */
    public void sendChatEvent(ChatEvent event) {
        String key = event.getSessionId(); // 用 sessionId 做 key，保证同一会话的消息有序

        CompletableFuture<SendResult<String, ChatEvent>> future =
                kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("【Kafka Producer】消息发送成功，Topic: {}, Partition: {}, Offset: {}, SessionId: {}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getSessionId());
            } else {
                log.error("【Kafka Producer】消息发送失败，SessionId: {}, Error: {}",
                        event.getSessionId(), ex.getMessage(), ex);
            }
        });
    }
}
