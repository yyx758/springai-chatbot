package com.example.chatbot.rag;

import com.example.chatbot.kafka.KnowledgeEvent;
import com.example.chatbot.kafka.KnowledgeEventConsumer;
import com.example.chatbot.service.ChatContextService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeEventVectorIndexTest {

    @Mock
    private VectorIndexingService vectorIndexingService;

    @Mock
    private ElasticsearchRagService elasticsearchRagService;

    @Mock
    private ChatContextService chatContextService;

    @Mock
    private Acknowledgment acknowledgment;

    @Test
    @DisplayName("Knowledge created event triggers vector indexing")
    void createdTriggersIndexing() {
        KnowledgeEventConsumer consumer = new KnowledgeEventConsumer(vectorIndexingService, elasticsearchRagService, chatContextService);
        KnowledgeEvent event = KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_CREATED")
                .documentId(9L)
                .userId(7L)
                .eventTime(LocalDateTime.now())
                .build();

        consumer.onKnowledgeEvent(new ConsumerRecord<>("knowledge.events", 0, 0, "7", event), acknowledgment);

        verify(vectorIndexingService).indexDocument(7L, 9L);
        verify(elasticsearchRagService).indexDocument(7L, 9L);
        verify(chatContextService).evictUserContext(7L);
        verify(vectorIndexingService, never()).deleteDocument(anyLong(), anyLong());
        verify(elasticsearchRagService, never()).deleteDocument(anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("Knowledge deleted event triggers vector deletion")
    void deletedTriggersVectorDelete() {
        KnowledgeEventConsumer consumer = new KnowledgeEventConsumer(vectorIndexingService, elasticsearchRagService, chatContextService);
        KnowledgeEvent event = KnowledgeEvent.builder()
                .eventType("KNOWLEDGE_DELETED")
                .documentId(9L)
                .userId(7L)
                .eventTime(LocalDateTime.now())
                .build();

        consumer.onKnowledgeEvent(new ConsumerRecord<>("knowledge.events", 0, 0, "7", event), acknowledgment);

        verify(vectorIndexingService).deleteDocument(7L, 9L);
        verify(elasticsearchRagService).deleteDocument(7L, 9L);
        verify(vectorIndexingService, never()).indexDocument(anyLong(), anyLong());
        verify(elasticsearchRagService, never()).indexDocument(anyLong(), anyLong());
        verify(acknowledgment).acknowledge();
    }
}
