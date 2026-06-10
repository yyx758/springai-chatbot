package com.example.chatbot.rag;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.kafka.KnowledgeEventProducer;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.FileServiceClient;
import com.example.chatbot.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridRagServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private KnowledgeEventProducer knowledgeEventProducer;

    @Mock
    private VectorRagService vectorRagService;

    @Mock
    private VectorIndexingService vectorIndexingService;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private HybridSearchService hybridSearchService;

    @Test
    @DisplayName("Hybrid mode delegates to HybridSearchService")
    void hybridDelegatesToHybridSearchService() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient, hybridSearchService);

        RagReference expected = RagReference.builder()
                .documentId(1L).title("Redis 配置").score(0.05).build();
        when(hybridSearchService.search(eq(7L), eq("Redis怎么配置"), eq(3)))
                .thenReturn(List.of(expected));

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(1, results.size());
        assertEquals("Redis 配置", results.get(0).getTitle());
    }

    @Test
    @DisplayName("Vector mode with fallback uses keyword when vector returns empty")
    void vectorModeEmptyFallsBackToKeyword() {
        RagProperties properties = new RagProperties();
        properties.setMode("vector");
        properties.setFallbackToKeyword(true);
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient, hybridSearchService);
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("退款政策")
                .content("用户可以在七天内申请退款")
                .tags("退款")
                .enabled(true)
                .build();
        when(vectorRagService.retrieve(eq(7L), eq("如何退款"), anyInt())).thenReturn(List.of());
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(document));

        List<RagReference> results = ragService.retrieveReferences(7L, "如何退款", 3);

        assertEquals(1, results.size());
        assertEquals("退款政策", results.get(0).getTitle());
    }

    @Test
    @DisplayName("RRF: hybrid mode returns results from HybridSearchService")
    void rrfDocumentInBothListsRanksFirst() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient, hybridSearchService);

        when(hybridSearchService.search(eq(7L), eq("Redis怎么配置"), eq(3))).thenReturn(List.of(
                RagReference.builder().documentId(2L).title("Redis 缓存策略").score(0.03).build(),
                RagReference.builder().documentId(1L).title("Redis 配置").score(0.02).build()
        ));

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(2, results.size());
        assertEquals("Redis 缓存策略", results.get(0).getTitle());
    }
}
