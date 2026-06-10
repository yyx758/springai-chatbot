package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridRankerTest {

    private final HybridRagProperties properties = new HybridRagProperties();
    private final HybridRanker ranker = new HybridRanker(properties);

    @Test
    @DisplayName("文档同时出现在向量和关键词中，RRF 分数最高")
    void documentInBothListsRanksFirst() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("Redis缓存").snippet("内容").vectorRank(1).vectorScore(0.9).build(),
                HybridCandidate.builder().chunkId("3_0").documentId(3L).title("Transformer").snippet("内容").vectorRank(2).vectorScore(0.8).build()
        );
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("Redis配置").snippet("内容").keywordRank(1).keywordScore(50.0).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("Redis缓存").snippet("内容").keywordRank(2).keywordScore(30.0).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, keywordResults, intent, 3);

        assertFalse(selected.isEmpty());
        assertEquals("2_0", selected.get(0).getChunkId());
        assertTrue(selected.get(0).isSelected());
    }

    @Test
    @DisplayName("加权 RRF：向量权重高时向量结果排名更靠前")
    void weightedRrfFavorsVector() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").vectorRank(1).vectorScore(0.7).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").snippet("内容").vectorRank(2).vectorScore(0.6).build()
        );
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").snippet("内容").keywordRank(1).keywordScore(50.0).matchedTerms(List.of("Redis")).build(),
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").keywordRank(2).keywordScore(30.0).matchedTerms(List.of("Redis")).build()
        );
        QueryIntent vectorHeavy = new QueryIntent(QueryType.SEMANTIC_EXPLAIN, 1.3, 0.7);
        List<HybridCandidate> result1 = ranker.rank(vectorResults, keywordResults, vectorHeavy, 3);

        QueryIntent keywordHeavy = new QueryIntent(QueryType.EXACT_KEYWORD, 0.7, 1.3);
        List<HybridCandidate> result2 = ranker.rank(vectorResults, keywordResults, keywordHeavy, 3);

        assertEquals("1_0", result1.get(0).getChunkId());
        assertEquals("2_0", result2.get(0).getChunkId());
    }

    @Test
    @DisplayName("只有向量结果时正常工作")
    void onlyVectorResults() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").vectorRank(1).vectorScore(0.7).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").snippet("内容").vectorRank(2).vectorScore(0.6).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(2, selected.size());
    }

    @Test
    @DisplayName("规则2：vectorScore >= 0.55 直接保留")
    void filterStrongRelevance() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").vectorRank(1).vectorScore(0.6).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(1, selected.size());
        assertEquals(SelectReason.SELECTED_STRONG_VECTOR, selected.get(0).getSelectedReason());
    }

    @Test
    @DisplayName("规则4：关键词强命中也保留")
    void filterWeakButHighValueKeyword() {
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("NullPointerException修复").snippet("内容")
                        .keywordRank(1).keywordScore(50.0)
                        .matchedTerms(List.of("NullPointerException"))
                        .build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(List.of(), keywordResults, intent, 3);

        assertEquals(1, selected.size());
        assertEquals(SelectReason.SELECTED_STRONG_KEYWORD, selected.get(0).getSelectedReason());
    }

    @Test
    @DisplayName("规则8：全部过滤时 fallback 保留最高分 1 条")
    void fallbackWhenAllFiltered() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").vectorRank(1).vectorScore(0.5).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(1, selected.size());
        assertEquals(SelectReason.SELECTED_FALLBACK_BEST_VECTOR, selected.get(0).getSelectedReason());
    }

    @Test
    @DisplayName("规则6：同文档 chunk 限制，第二个被过滤")
    void sameDocumentChunkLimit() {
        properties.setMaxChunksPerDocument(1);
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").snippet("内容").vectorRank(1).vectorScore(0.7).build(),
                HybridCandidate.builder().chunkId("1_1").documentId(1L).title("A2").snippet("内容").vectorRank(2).vectorScore(0.65).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        // 只有 1 个被选中（同文档限制）
        assertEquals(1, selected.size());
        assertEquals("1_0", selected.get(0).getChunkId());
        assertEquals(SelectReason.SELECTED_STRONG_VECTOR, selected.get(0).getSelectedReason());
    }
}
