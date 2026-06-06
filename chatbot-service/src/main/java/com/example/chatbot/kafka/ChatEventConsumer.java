package com.example.chatbot.kafka;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 聊天事件消费者
 * 负责从 Kafka 消费聊天事件并持久化到 MySQL，同时维护 Redis 中最近 N 轮上下文缓存
 * 重试 + 死信队列由 KafkaConsumerConfig 中的 DefaultErrorHandler 统一管理：
 * - 失败自动重试 3 次（间隔 1 秒）
 * - 重试耗尽后自动发往 chat.events.DLT
 * - 业务代码无需 try-catch，专注于业务逻辑
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventConsumer {

    private final ChatRecordMapper chatRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.chatbot.max-history:5}")
    private int maxHistory = 5;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_CHAT_EVENTS,
            groupId = "chatbot-persistence-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        log.info("【Kafka Consumer】收到消息，Partition: {}, Offset: {}, SessionId: {}",
                record.partition(), record.offset(), event.getSessionId());

        // 1. 清洗 botResponse（去除 SSE 协议残留）
        String cleanBotRes = cleanBotResponse(event.getBotResponse());

        // 2. 处理图片数据（优先使用 fileKey，兼容旧的 Base64 方式）
        String dataUri = resolveImageData(event);

        // 3. 写入 MySQL
        ChatRecord chatRecord = ChatRecord.builder()
                .sessionId(event.getSessionId())
                .userMessage(event.getUserMessage())
                .botResponse(cleanBotRes)
                .imageData(dataUri)
                .createdTime(LocalDateTime.now())
                .build();
        chatRecordMapper.insert(chatRecord);
        log.info("【Kafka Consumer】MySQL 持久化成功，RecordId: {}, SessionId: {}",
                chatRecord.getId(), event.getSessionId());

        // 4. 追加更新 Redis 最近上下文缓存；失败时删除缓存兜底，下一次从 MySQL 重建
        appendRedisHistory(chatRecord);

        // 5. 手动确认消息（成功后 ACK，失败由 DefaultErrorHandler 接管重试）
        ack.acknowledge();
        log.info("【Kafka Consumer】消息处理完成并 ACK，SessionId: {}", event.getSessionId());
    }

    /**
     * DLT 消费者 — 处理重试耗尽的死信消息
     * 记录审计日志，生产环境可扩展为：写入数据库 + 发送告警通知
     */
    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_DLT,
            groupId = "chatbot-dlt-group",
            containerFactory = "dltContainerFactory"
    )
    public void onDLTMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.error("【DLT】消息进入死信队列，Partition: {}, Offset: {}, Key: {}, Value: {}",
                record.partition(), record.offset(), record.key(), record.value());

        // TODO: 生产环境扩展 —— 写入审计表 + 发送告警通知
        // dltAuditMapper.insert(...);
        // notificationService.sendAlert("死信消息: " + record.value());

        ack.acknowledge();
    }

    /**
     * 处理图片数据（优先使用 fileKey，兼容旧的 Base64 方式）
     */
    private String resolveImageData(ChatEvent event) {
        if (event.getImageFileKey() != null && !event.getImageFileKey().isBlank()) {
            log.info("【Kafka Consumer】使用 fileKey 模式: {}", event.getImageFileKey());
            return "filekey:" + event.getImageFileKey();
        } else if (event.getImageBytes() != null && event.getImageBytes().length > 0) {
            String base64 = Base64.getEncoder().encodeToString(event.getImageBytes());
            String mime = (event.getImageMimeType() != null && !event.getImageMimeType().isBlank())
                    ? event.getImageMimeType() : "image/jpeg";
            return "data:" + mime + ";base64," + base64;
        } else if (event.getImageData() != null && !event.getImageData().isBlank()) {
            return event.getImageData();
        }
        return null;
    }

    /**
     * 清洗 botResponse，去除 SSE 协议残留
     */
    private String cleanBotResponse(String botResponse) {
        if (botResponse == null) return "";
        if (botResponse.contains("data:{\"content\":")) {
            return botResponse.replaceAll("data:\\{\"content\":\"|\"\\}", "");
        }
        return botResponse;
    }

    /**
     * 聊天历史是追加型数据：MySQL 写入成功后追加 Redis List，并裁剪为最近 N 条。
     * 如果 Redis 更新失败，再删除缓存兜底；下一次构建上下文会从 MySQL 重建缓存。
     */
    private void appendRedisHistory(ChatRecord chatRecord) {
        String key = "chat:history:" + chatRecord.getSessionId();
        int historyLimit = Math.max(1, maxHistory);
        try {
            redisTemplate.opsForList().rightPush(key, chatRecord);
            redisTemplate.opsForList().trim(key, -historyLimit, -1);
            redisTemplate.expire(key, 2, TimeUnit.HOURS);
            log.debug("【Kafka Consumer】Redis 聊天历史已追加，SessionId: {}, MaxHistory: {}",
                    chatRecord.getSessionId(), historyLimit);
        } catch (Exception e) {
            log.warn("【Kafka Consumer】Redis 追加失败，改为删除缓存兜底，SessionId: {}, Error: {}",
                    chatRecord.getSessionId(), e.getMessage());
            evictRedisCache(chatRecord.getSessionId());
        }
    }

    /**
     * 删除 Redis 聊天历史缓存。仅作为缓存更新失败或会话删除时的兜底策略。
     */
    private void evictRedisCache(String sessionId) {
        try {
            String key = "chat:history:" + sessionId;
            redisTemplate.delete(key);
            log.debug("【Kafka Consumer】Redis 缓存已删除，SessionId: {}", sessionId);
        } catch (Exception e) {
            log.warn("【Kafka Consumer】Redis 缓存删除失败，SessionId: {}, Error: {}",
                    sessionId, e.getMessage());
        }
    }
}
