package com.example.chatbot.kafka;

import com.example.chatbot.rag.VectorIndexingService;
import com.example.chatbot.rag.ElasticsearchRagService;
import com.example.chatbot.service.ChatContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

    private final VectorIndexingService vectorIndexingService;
    private final ElasticsearchRagService elasticsearchRagService;
    private final ChatContextService chatContextService;

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
            refreshVectorIndex(event);

            log.info("【Kafka Consumer-知识库】缓存刷新完成，UserId: {}", event.getUserId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("【Kafka Consumer-知识库】事件处理失败，UserId: {}, Error: {}",
                    event.getUserId(), e.getMessage(), e);
            throw new IllegalStateException("knowledge event failed", e);
        }
    }

    /**
     * 清除用户的所有聊天历史缓存
     * 注意：生产环境应更精细地只清除包含该知识文档的会话缓存
     */
    private void invalidateUserChatCache(Long userId) {
        try {
            chatContextService.evictUserContext(userId);
            log.info("【Kafka Consumer-知识库】已清除用户上下文缓存，UserId: {}", userId);
        } catch (Exception e) {
            log.warn("【Kafka Consumer-知识库】缓存清除失败，UserId: {}, Error: {}", userId, e.getMessage());
        }
    }

    private void refreshVectorIndex(KnowledgeEvent event) {
        if (event == null || event.getUserId() == null || event.getDocumentId() == null) {
            return;
        }
        if ("KNOWLEDGE_DELETED".equals(event.getEventType())) {
            vectorIndexingService.deleteDocument(event.getUserId(), event.getDocumentId());
            elasticsearchRagService.deleteDocument(event.getUserId(), event.getDocumentId());
            return;
        }
        if ("KNOWLEDGE_CREATED".equals(event.getEventType()) || "KNOWLEDGE_UPDATED".equals(event.getEventType())) {
            vectorIndexingService.indexDocument(event.getUserId(), event.getDocumentId());
            elasticsearchRagService.indexDocument(event.getUserId(), event.getDocumentId());
        }
    }
}
