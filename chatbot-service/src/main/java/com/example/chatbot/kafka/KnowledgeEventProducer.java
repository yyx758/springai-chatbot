package com.example.chatbot.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 知识库事件生产者
 * 当知识文档变更时发布事件，供 ChatService 消费以刷新 RAG 索引
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KnowledgeEventProducer {

    private final KafkaTemplate<String, KnowledgeEvent> kafkaTemplate;

    public void sendKnowledgeEvent(KnowledgeEvent event) {
        String key = event.getUserId() + "_" + event.getEventType();

        CompletableFuture<SendResult<String, KnowledgeEvent>> future =
                kafkaTemplate.send(KafkaTopicConfig.TOPIC_KNOWLEDGE_EVENTS, key, event);

        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("【Kafka Producer】知识库事件发送成功，Type: {}, DocId: {}, UserId: {}",
                        event.getEventType(), event.getDocumentId(), event.getUserId());
            } else {
                log.error("【Kafka Producer】知识库事件发送失败，DocId: {}, Error: {}",
                        event.getDocumentId(), ex.getMessage(), ex);
            }
        });
    }
}
