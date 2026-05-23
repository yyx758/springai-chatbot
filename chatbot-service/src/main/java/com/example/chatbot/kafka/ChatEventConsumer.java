package com.example.chatbot.kafka;

import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 聊天事件消费者
 * 负责从 Kafka 消费聊天事件并持久化到 MySQL + Redis
 * 替代原来 ChatbotService 中 @Async 的方式，优势：
 * 1. 消息不丢失（Kafka 持久化 + 手动 ACK）
 * 2. 支持重试（消费失败可重新消费）
 * 3. 支持多实例水平扩展
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatEventConsumer {

    private final ChatRecordMapper chatRecordMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final int MAX_HISTORY = 5;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_CHAT_EVENTS,
            groupId = "chatbot-persistence-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onChatEvent(ConsumerRecord<String, ChatEvent> record, Acknowledgment ack) {
        ChatEvent event = record.value();
        log.info("【Kafka Consumer】收到消息，Topic: {}, Partition: {}, Offset: {}, SessionId: {}",
                record.topic(), record.partition(), record.offset(), event.getSessionId());

        try {
            // 1. 清洗 botResponse（去除 SSE 协议残留）
            String cleanBotRes = cleanBotResponse(event.getBotResponse());

            // 2. 处理图片数据（优先使用 fileKey，兼容旧的 Base64 方式）
            String dataUri = null;
            if (event.getImageFileKey() != null && !event.getImageFileKey().isBlank()) {
                // 新方式：存储 fileKey（格式：filekey:xxx）
                dataUri = "filekey:" + event.getImageFileKey();
                log.info("【Kafka Consumer】使用 fileKey 模式: {}", event.getImageFileKey());
            } else if (event.getImageBytes() != null && event.getImageBytes().length > 0) {
                // 旧方式：Base64 编码
                String base64 = Base64.getEncoder().encodeToString(event.getImageBytes());
                String mime = (event.getImageMimeType() != null && !event.getImageMimeType().isBlank())
                        ? event.getImageMimeType() : "image/jpeg";
                dataUri = "data:" + mime + ";base64," + base64;
            } else if (event.getImageData() != null && !event.getImageData().isBlank()) {
                dataUri = event.getImageData();
            }

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

            // 4. 更新 Redis 缓存
            updateRedisCache(event.getSessionId(), chatRecord);

            // 5. 手动确认消息
            ack.acknowledge();
            log.info("【Kafka Consumer】消息处理完成并 ACK，SessionId: {}", event.getSessionId());

        } catch (Exception e) {
            log.error("【Kafka Consumer】消息处理失败，SessionId: {}, Error: {}",
                    event.getSessionId(), e.getMessage(), e);
            // ACK 消息避免无限重试（生产环境应使用死信队列 DLQ）
            ack.acknowledge();
        }
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
     * 更新 Redis 聊天历史缓存
     */
    private void updateRedisCache(String sessionId, ChatRecord record) {
        try {
            String key = "chat:history:" + sessionId;
            redisTemplate.opsForList().rightPush(key, record);
            redisTemplate.opsForList().trim(key, -MAX_HISTORY, -1);
            redisTemplate.expire(key, 2, TimeUnit.HOURS);
            log.debug("【Kafka Consumer】Redis 缓存更新成功，SessionId: {}", sessionId);
        } catch (Exception e) {
            // Redis 失败不影响主流程，只记日志
            log.warn("【Kafka Consumer】Redis 缓存更新失败，SessionId: {}, Error: {}",
                    sessionId, e.getMessage());
        }
    }
}
