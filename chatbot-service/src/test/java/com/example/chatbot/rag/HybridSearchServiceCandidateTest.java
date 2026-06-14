package com.example.chatbot.rag;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridSearchServiceCandidateTest {

    @Test
    @DisplayName("Keyword search uses fulltext candidates before Java scoring")
    void keywordSearchUsesFulltextCandidates() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchRagService elasticsearchRagService = mock(ElasticsearchRagService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(elasticsearchRagService.searchKeywordCandidates(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("Redis 缓存策略")
                .content("Redis 可以设置 TTL 和 LRU 策略")
                .enabled(true)
                .build();
        when(mapper.searchFulltextCandidates(eq(7L), eq("Redis TTL"), anyInt()))
                .thenReturn(List.of(document));

        HybridSearchService service = newService(vectorRagService, mapper, elasticsearchRagService);

        service.search(7L, "Redis TTL", 3);

        verify(mapper).searchFulltextCandidates(eq(7L), eq("Redis TTL"), eq(200));
        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("Keyword search falls back to limited scan when fulltext fails")
    void keywordSearchFallbackWhenFulltextFails() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchRagService elasticsearchRagService = mock(ElasticsearchRagService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(elasticsearchRagService.searchKeywordCandidates(anyLong(), anyString(), anyInt()))
                .thenReturn(List.of());
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("退款政策")
                .content("用户可以在七天内申请退款")
                .enabled(true)
                .build();
        when(mapper.searchFulltextCandidates(anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("fulltext unavailable"));
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(document));

        HybridSearchService service = newService(vectorRagService, mapper, elasticsearchRagService);

        service.search(7L, "如何退款", 3);

        verify(mapper).searchFulltextCandidates(eq(7L), eq("如何退款"), eq(200));
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("Keyword search prefers Elasticsearch candidates when available")
    void keywordSearchPrefersElasticsearchCandidates() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchRagService elasticsearchRagService = mock(ElasticsearchRagService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(elasticsearchRagService.searchKeywordCandidates(eq(7L), eq("Redis TTL"), anyInt()))
                .thenReturn(List.of(HybridCandidate.builder()
                        .chunkId("1_0")
                        .documentId(1L)
                        .title("Redis 缓存策略")
                        .snippet("Redis 可以设置 TTL")
                        .keywordRank(1)
                        .keywordScore(8.0)
                        .matchedTerms(List.of("Redis", "TTL"))
                        .build()));

        HybridSearchService service = newService(vectorRagService, mapper, elasticsearchRagService);

        service.search(7L, "Redis TTL", 3);

        verify(elasticsearchRagService).searchKeywordCandidates(eq(7L), eq("Redis TTL"), eq(200));
        verify(mapper, never()).searchFulltextCandidates(anyLong(), anyString(), anyInt());
        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    private HybridSearchService newService(VectorRagService vectorRagService,
                                           KnowledgeDocumentMapper mapper,
                                           ElasticsearchRagService elasticsearchRagService) {
        HybridRagProperties properties = new HybridRagProperties();
        return new HybridSearchService(
                new QueryIntentAnalyzer(),
                vectorRagService,
                new HybridRanker(properties),
                mapper,
                new KeywordExtractor(),
                elasticsearchRagService);
    }
}
