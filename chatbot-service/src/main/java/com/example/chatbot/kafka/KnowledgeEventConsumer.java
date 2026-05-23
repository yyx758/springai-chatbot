package com.example.chatbot.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 知识库事件消费者
 * 消费 knowledge.events 事件，刷新 ChatService 的 RAG 缓存
 * 当知识文档变更时，清除相关用户的 RAG 缓存，确保下次查询使用最新数据
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KnowledgeEventConsumer {

    private final RedisTemplate<String, Object> redisTemplate;

    @KafkaListener(
            topics = KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS,
            groupId = "chatbot-knowledge-consumer",
            containerFactory = "knowledgeKafkaListenerContainerFactory"
    )
    public void onKnowledgeEvent(ConsumerRecord<String, KnowledgeEvent> record, Acknowledgment ack) {
        KnowledgeEvent event = record.value();
        log.info("【Kafka Consumer-知识库】收到事件，Type: {}, DocId: {}, UserId: {}",
                event.getEventType(), event.getDocumentId(), event.getUserId());

        try {
            // 知识文档变更后，清除该用户的所有聊天历史缓存
            // 这样下次聊天时会重新从 MySQL 加载，包含最新的 RAG 引用
            invalidateUserChatCache(event.getUserId());

            log.info("【Kafka Consumer-知识库】缓存刷新完成，UserId: {}", event.getUserId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("【Kafka Consumer-知识库】事件处理失败，UserId: {}, Error: {}",
                    event.getUserId(), e.getMessage(), e);
            // ACK 消息避免无限重试
            ack.acknowledge();
        }
    }

    /**
     * 清除用户的所有聊天历史缓存
     * 注意：生产环境应更精细地只清除包含该知识文档的会话缓存
     */
    private void invalidateUserChatCache(Long userId) {
        try {
            String pattern = "chat:history:" + userId + "_*";
            java.util.Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("【Kafka Consumer-知识库】已清除 {} 个缓存键，UserId: {}", keys.size(), userId);
            }
        } catch (Exception e) {
            log.warn("【Kafka Consumer-知识库】缓存清除失败，UserId: {}, Error: {}", userId, e.getMessage());
        }
    }
}
