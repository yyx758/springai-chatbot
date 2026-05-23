package com.example.chatbot.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 通知事件生产者
 * 发布邮件发送请求，供 NotificationEventConsumer 消费
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationEventProducer {

    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    public void sendNotificationEvent(NotificationEvent event) {
        String key = event.getToEmail();

        CompletableFuture<SendResult<String, NotificationEvent>> future =
                kafkaTemplate.send(KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("【Kafka Producer】通知事件发送成功，Type: {}, To: {}",
                        event.getEventType(), event.getToEmail());
            } else {
                log.error("【Kafka Producer】通知事件发送失败，To: {}, Error: {}",
                        event.getToEmail(), ex.getMessage(), ex);
            }
        });
    }
}
