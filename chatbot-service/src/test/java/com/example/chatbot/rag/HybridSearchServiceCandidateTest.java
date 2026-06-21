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
        ElasticsearchKeywordSearchService keywordSearchService = mock(ElasticsearchKeywordSearchService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(keywordSearchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("Redis 缓存策略")
                .content("Redis 可以设置 TTL 和 LRU 策略")
                .enabled(true)
                .build();
        when(mapper.searchFulltextCandidates(eq(7L), eq("Redis TTL"), anyInt()))
                .thenReturn(List.of(document));

        HybridSearchService service = newService(vectorRagService, mapper, keywordSearchService);

        service.search(7L, "Redis TTL", 3);

        verify(mapper).searchFulltextCandidates(eq(7L), eq("Redis TTL"), eq(200));
        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("Keyword search falls back to limited scan when fulltext fails")
    void keywordSearchFallbackWhenFulltextFails() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchKeywordSearchService keywordSearchService = mock(ElasticsearchKeywordSearchService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(keywordSearchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
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

        HybridSearchService service = newService(vectorRagService, mapper, keywordSearchService);

        service.search(7L, "如何退款", 3);

        verify(mapper).searchFulltextCandidates(eq(7L), eq("退款"), eq(200));
        verify(mapper).selectList(any(Wrapper.class));
    }

    @Test
    @DisplayName("Chinese two-character keyword can be a strong keyword signal")
    void chineseTwoCharacterKeywordCanBeStrongSignal() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchKeywordSearchService keywordSearchService = mock(ElasticsearchKeywordSearchService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(keywordSearchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(1L)
                .userId(7L)
                .title("退款政策")
                .content("用户可以在七天内申请退款")
                .enabled(true)
                .build();
        when(mapper.searchFulltextCandidates(eq(7L), eq("退款"), anyInt()))
                .thenReturn(List.of(document));

        HybridSearchService service = newService(vectorRagService, mapper, keywordSearchService);

        List<?> results = service.search(7L, "如何退款", 3);

        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("Keyword search prefers Elasticsearch candidates when available")
    void keywordSearchPrefersElasticsearchCandidates() {
        KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
        VectorRagService vectorRagService = mock(VectorRagService.class);
        ElasticsearchKeywordSearchService keywordSearchService = mock(ElasticsearchKeywordSearchService.class);
        when(vectorRagService.isEnabled()).thenReturn(false);
        when(keywordSearchService.search(eq(7L), eq("Redis TTL"), anyInt()))
                .thenReturn(List.of(SearchResult.builder()
                        .chunkId("1_0")
                        .docId(1L)
                        .title("Redis 缓存策略")
                        .content("Redis 可以设置 TTL")
                        .rank(1)
                        .score(8.0)
                        .source("elasticsearch")
                        .build()));

        HybridSearchService service = newService(vectorRagService, mapper, keywordSearchService);

        service.search(7L, "Redis TTL", 3);

        verify(keywordSearchService).search(eq(7L), eq("Redis TTL"), eq(200));
        verify(mapper, never()).searchFulltextCandidates(anyLong(), anyString(), anyInt());
        verify(mapper, never()).selectList(any(Wrapper.class));
    }

    private HybridSearchService newService(VectorRagService vectorRagService,
                                           KnowledgeDocumentMapper mapper,
                                           ElasticsearchKeywordSearchService keywordSearchService) {
        HybridRagProperties properties = new HybridRagProperties();
        RagProperties ragProperties = new RagProperties();
        ragProperties.getQueryEnhancer().setEnabled(false);
        return new HybridSearchService(
                new QueryIntentAnalyzer(),
                vectorRagService,
                mapper,
                new KeywordExtractor(),
                new QueryRewriteService(),
                new QueryEnhancer(ragProperties),
                keywordSearchService,
                new RrfFusionService(properties));
    }

    @Test
    @DisplayName("Query rewrite removes filler words before retrieval")
    void queryRewriteRemovesFillerWords() {
        QueryRewriteService service = new QueryRewriteService();

        assertEquals("退款政策", service.rewriteForRetrieval("请问如何退款政策？"));
        assertEquals("文件上传失败", service.rewriteForRetrieval("帮我查一下文件上传失败怎么办"));
        assertEquals("Redis TTL", service.rewriteForRetrieval("Redis TTL"));
    }
}
