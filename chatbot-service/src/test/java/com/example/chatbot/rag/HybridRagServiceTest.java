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

    @Test
    @DisplayName("RRF: document appearing in both lists gets highest score")
    void rrfDocumentInBothListsRanksFirst() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient);

        // 关键词返回 Redis配置(rank1), Redis缓存(rank2) — 内容包含 "redis"
        KnowledgeDocument docA = KnowledgeDocument.builder().id(1L).userId(7L)
                .title("Redis 配置").content("Spring Boot 配置 Redis 需要设置 host").tags("redis").enabled(true).build();
        KnowledgeDocument docB = KnowledgeDocument.builder().id(2L).userId(7L)
                .title("Redis 缓存策略").content("Redis 缓存可以提升性能").tags("redis").enabled(true).build();
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(docA, docB));

        // 向量返回 Redis缓存(rank1), Transformer(rank2)
        when(vectorRagService.retrieve(eq(7L), eq("Redis怎么配置"), anyInt())).thenReturn(List.of(
                RagReference.builder().documentId(2L).title("Redis 缓存策略").score(0.9).build(),
                RagReference.builder().documentId(3L).title("Transformer原理").score(0.8).build()
        ));

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(3, results.size());
        // docB 出现在两个列表中，RRF 分数最高，排第一
        assertEquals("Redis 缓存策略", results.get(0).getTitle());
        // docA 只在关键词中，排第二
        assertEquals("Redis 配置", results.get(1).getTitle());
        // Transformer 只在向量中，排第三
        assertEquals("Transformer原理", results.get(2).getTitle());
    }

    @Test
    @DisplayName("RRF: score is sum of 1/(k+rank) from each pipeline")
    void rrfScoreCalculation() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient);

        // 关键词返回 Redis配置(rank1) — 内容包含 "redis"
        KnowledgeDocument docA = KnowledgeDocument.builder().id(1L).userId(7L)
                .title("Redis 配置").content("Spring Boot 配置 Redis 需要设置 host").tags("redis").enabled(true).build();
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(docA));

        // 向量返回 Redis配置(rank1), Redis缓存(rank2)
        when(vectorRagService.retrieve(eq(7L), eq("Redis怎么配置"), anyInt())).thenReturn(List.of(
                RagReference.builder().documentId(1L).title("Redis 配置").score(0.9).build(),
                RagReference.builder().documentId(2L).title("Redis 缓存策略").score(0.8).build()
        ));

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(2, results.size());
        // docA: 1/(60+1) + 1/(60+1) = 2/61 ≈ 0.03279
        double expectedA = 2.0 / 61.0;
        assertEquals(expectedA, results.get(0).getScore(), 0.0001);
        // docB: 1/(60+2) = 1/62 ≈ 0.01613
        double expectedB = 1.0 / 62.0;
        assertEquals(expectedB, results.get(1).getScore(), 0.0001);
    }

    @Test
    @DisplayName("RRF: only vector results when keyword returns empty")
    void rrfOnlyVectorResults() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient);

        // 关键词无匹配（文档内容不含查询词）
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

        // 向量返回 A, B
        when(vectorRagService.retrieve(eq(7L), eq("Redis怎么配置"), anyInt())).thenReturn(List.of(
                RagReference.builder().documentId(1L).title("Redis 配置").score(0.9).build(),
                RagReference.builder().documentId(2L).title("Redis 缓存策略").score(0.7).build()
        ));

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(2, results.size());
        assertEquals("Redis 配置", results.get(0).getTitle());
        assertEquals("Redis 缓存策略", results.get(1).getTitle());
    }

    @Test
    @DisplayName("RRF: only keyword results when vector returns empty")
    void rrfOnlyKeywordResults() {
        RagProperties properties = new RagProperties();
        properties.setMode("hybrid");
        RagService ragService = new RagService(knowledgeDocumentMapper, knowledgeEventProducer, vectorRagService,
                vectorIndexingService, properties, fileServiceClient);

        // 关键词返回 Redis配置, Redis缓存 — 内容包含 "redis"
        KnowledgeDocument docA = KnowledgeDocument.builder().id(1L).userId(7L)
                .title("Redis 配置").content("Spring Boot 配置 Redis 需要设置 host").tags("redis").enabled(true).build();
        KnowledgeDocument docB = KnowledgeDocument.builder().id(2L).userId(7L)
                .title("Redis 缓存策略").content("Redis 缓存可以提升性能").tags("redis").enabled(true).build();
        when(knowledgeDocumentMapper.selectList(any(Wrapper.class))).thenReturn(List.of(docA, docB));

        // 向量无结果
        when(vectorRagService.retrieve(eq(7L), eq("Redis怎么配置"), anyInt())).thenReturn(List.of());

        List<RagReference> results = ragService.retrieveReferences(7L, "Redis怎么配置", 3);

        assertEquals(2, results.size());
        assertEquals("Redis 配置", results.get(0).getTitle());
        assertEquals("Redis 缓存策略", results.get(1).getTitle());
    }
}
