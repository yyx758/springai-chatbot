package com.example.chatbot.rag;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagRecallEvaluationTest {

    @Test
    @DisplayName("Representative Chinese and technical queries recall expected keyword documents")
    void representativeQueriesRecallExpectedKeywordDocuments() {
        List<Scenario> scenarios = List.of(
                new Scenario("如何退款", "退款", "退款政策",
                        "用户可以在七天内申请退款，超过期限需要联系人工客服。"),
                new Scenario("帮我查一下文件上传失败怎么办", "文件上传失败", "文件上传限制",
                        "图片上传失败通常是因为文件超过 10MB 或格式不是 jpg/png/gif/webp。"),
                new Scenario("JWT刷新令牌怎么轮转", "JWT刷新令牌轮转", "JWT 双 Token 机制",
                        "Refresh Token 存储在 Redis 中，刷新时使用 getAndDelete 做原子轮转。"),
                new Scenario("Kafka消息为什么会丢失", "Kafka消息会丢失", "Kafka 可靠性设计",
                        "Kafka 消费者使用 manual_immediate ACK，失败后重试并进入死信队列。"),
                new Scenario("Docker部署ES内存怎么配置", "Docker部署ES内存配置", "Elasticsearch 部署说明",
                        "4GB 服务器上 Elasticsearch heap 使用 -Xms384m -Xmx384m。")
        );

        for (Scenario scenario : scenarios) {
            KnowledgeDocumentMapper mapper = mock(KnowledgeDocumentMapper.class);
            VectorRagService vectorRagService = mock(VectorRagService.class);
            ElasticsearchKeywordSearchService keywordSearchService = mock(ElasticsearchKeywordSearchService.class);
            when(vectorRagService.isEnabled()).thenReturn(false);
            when(keywordSearchService.search(anyLong(), anyString(), anyInt())).thenReturn(List.of());
            when(mapper.searchFulltextCandidates(eq(7L), eq(scenario.rewrittenQuery()), anyInt()))
                    .thenReturn(List.of(document(1L, scenario.title(), scenario.content())));

            HybridRagProperties hybridProperties = new HybridRagProperties();
            RagProperties ragProperties = new RagProperties();
            ragProperties.getQueryEnhancer().setEnabled(false);
            HybridSearchService service = new HybridSearchService(
                    new QueryIntentAnalyzer(),
                    vectorRagService,
                    mapper,
                    new KeywordExtractor(),
                    new QueryRewriteService(),
                    new QueryEnhancer(ragProperties),
                    keywordSearchService,
                    new RrfFusionService(hybridProperties));

            List<RagReference> results = service.search(7L, scenario.query(), 3);

            assertFalse(results.isEmpty(), "query should recall: " + scenario.query());
            assertEquals(scenario.title(), results.get(0).getTitle(), "query=" + scenario.query());
            verify(mapper).searchFulltextCandidates(eq(7L), eq(scenario.rewrittenQuery()), eq(200));
            verify(mapper, never()).selectList(any(Wrapper.class));
        }
    }

    @Test
    @DisplayName("Query rewrite keeps domain terms and removes question filler")
    void queryRewriteKeepsDomainTermsAndRemovesQuestionFiller() {
        QueryRewriteService service = new QueryRewriteService();

        assertEquals("文件上传失败", service.rewriteForRetrieval("帮我查一下文件上传失败怎么办"));
        assertEquals("JWT刷新令牌轮转", service.rewriteForRetrieval("JWT刷新令牌怎么轮转"));
        assertEquals("Kafka消息会丢失", service.rewriteForRetrieval("Kafka消息为什么会丢失"));
        assertEquals("Docker部署ES内存配置", service.rewriteForRetrieval("Docker部署ES内存怎么配置"));
    }

    @Test
    @DisplayName("Keyword extractor recognizes standalone technical terms")
    void keywordExtractorRecognizesStandaloneTechnicalTerms() {
        KeywordExtractor extractor = new KeywordExtractor();

        assertTrue(extractor.extractTechnicalTerms("Kafka消息为什么会丢失").contains("Kafka"));
        assertTrue(extractor.extractTechnicalTerms("JWT刷新令牌怎么轮转").contains("JWT"));
        assertTrue(extractor.extractTechnicalTerms("Redis TTL怎么配置").contains("Redis"));
        assertTrue(extractor.extractTechnicalTerms("Docker部署ES内存").contains("Docker"));
    }

    private KnowledgeDocument document(Long id, String title, String content) {
        return KnowledgeDocument.builder()
                .id(id)
                .userId(7L)
                .title(title)
                .content(content)
                .enabled(true)
                .build();
    }

    private record Scenario(String query, String rewrittenQuery, String title, String content) {
    }
}
