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

    @Test
    @DisplayName("Hybrid mode falls back to keyword results when vector retrieval fails")
    void hybridFallsBackToKeyword() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient);
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("Redis 配置")
                .content("Spring Boot 配置 Redis 需要设置 host 和 port")
                .tags("redis")
                .enabled(true)
                .build();
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(document));
        when(vectorRagService.retrieve(eq(7L), eq("Redis怎么配置"), anyInt()))
                .thenThrow(new RuntimeException("vector unavailable"));

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
                vectorIndexingService, properties, fileServiceClient);
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
}
