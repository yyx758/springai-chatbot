package com.example.chatbot.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 聊天事件生产者
 * 同步发送 + 重试机制，确保消息可靠投递到 Kafka
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventProducer {

    private final KafkaTemplate<String, ChatEvent> kafkaTemplate;

    private static final int MAX_RETRIES = 3;
    private static final long SEND_TIMEOUT_SECONDS = 5;

    /**
     * 发送聊天完成事件（同步 + 重试）
     *
     * @param event 聊天事件
     */
    public void sendChatEvent(ChatEvent event) {
        String key = event.getSessionId();

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                SendResult<String, ChatEvent> result =
                        kafkaTemplate.send(KafkaTopicConfig.TOPIC_CHAT_EVENTS, key, event)
                                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

                log.info("【Kafka Producer】消息发送成功，Partition: {}, Offset: {}, SessionId: {}",
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getSessionId());
                return;

            } catch (Exception e) {
                log.warn("【Kafka Producer】消息发送失败，第 {}/{} 次重试，SessionId: {}",
                        attempt, MAX_RETRIES, event.getSessionId(), e);

                if (attempt == MAX_RETRIES) {
                    log.error("【Kafka Producer】消息发送最终失败，SessionId: {}，消息将丢失。生产环境应写入本地消息表做补偿",
                            event.getSessionId());
                }
            }
        }
    }
}
