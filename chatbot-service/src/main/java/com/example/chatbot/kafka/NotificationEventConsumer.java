package com.example.chatbot.kafka;

import com.example.chatbot.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 通知事件消费者
 * 消费 notification.events 事件，异步发送邮件
 * 解耦 AuthService 和 EmailService，邮件发送失败不影响主流程
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final EmailService emailService;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_NOTIFICATION_EVENTS,
            groupId = "chatbot-notification-consumer",
            containerFactory = "notificationKafkaListenerContainerFactory"
    )
    public void onNotificationEvent(ConsumerRecord<String, NotificationEvent> record, Acknowledgment ack) {
        NotificationEvent event = record.value();
        log.info("【Kafka Consumer-通知】收到事件，Type: {}, To: {}",
                event.getEventType(), event.getToEmail());

        try {
            switch (event.getEventType()) {
                case "SEND_VERIFICATION_CODE" -> {
                    emailService.sendVerificationCode(event.getToEmail());
                    log.info("【Kafka Consumer-通知】注册验证码发送成功，To: {}", event.getToEmail());
                }
                case "SEND_RESET_CODE" -> {
                    emailService.sendResetCode(event.getToEmail());
                    log.info("【Kafka Consumer-通知】重置密码验证码发送成功，To: {}", event.getToEmail());
                }
                default -> log.warn("【Kafka Consumer-通知】未知事件类型: {}", event.getEventType());
            }
            ack.acknowledge();
        } catch (IllegalStateException e) {
            // 业务异常（如发送过于频繁），不再重试，直接 ACK
            log.warn("【Kafka Consumer-通知】业务异常，跳过消息: {}", e.getMessage());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("【Kafka Consumer-通知】事件处理失败，Type: {}, To: {}, Error: {}",
                    event.getEventType(), event.getToEmail(), e.getMessage(), e);
            throw new IllegalStateException("notification event failed", e);
        }
    }
}
