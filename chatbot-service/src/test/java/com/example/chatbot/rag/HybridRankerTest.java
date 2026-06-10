package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridRankerTest {

    private final HybridRanker ranker = new HybridRanker();

    @Test
    @DisplayName("文档同时出现在向量和关键词中，RRF 分数最高")
    void documentInBothListsRanksFirst() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("Redis缓存").vectorRank(1).vectorScore(0.9).build(),
                HybridCandidate.builder().chunkId("3_0").documentId(3L).title("Transformer").vectorRank(2).vectorScore(0.8).build()
        );
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("Redis配置").keywordRank(1).keywordScore(50.0).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("Redis缓存").keywordRank(2).keywordScore(30.0).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, keywordResults, intent, 3);

        assertFalse(selected.isEmpty());
        // chunkId=2_0 出现在两个列表中，RRF 分数最高
        assertEquals("2_0", selected.get(0).getChunkId());
        assertTrue(selected.get(0).isSelected());
    }

    @Test
    @DisplayName("加权 RRF：向量权重高时向量结果排名更靠前")
    void weightedRrfFavorsVector() {
        // 两个候选都有向量分数（确保能通过过滤）
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").vectorRank(1).vectorScore(0.7).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").vectorRank(2).vectorScore(0.6).build()
        );
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").keywordRank(1).keywordScore(50.0).matchedTerms(List.of("Redis")).build(),
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").keywordRank(2).keywordScore(30.0).matchedTerms(List.of("Redis")).build()
        );
        // 向量权重高：A 向量 rank=1 贡献更大
        QueryIntent vectorHeavy = new QueryIntent(QueryType.SEMANTIC_EXPLAIN, 1.3, 0.7);
        List<HybridCandidate> result1 = ranker.rank(vectorResults, keywordResults, vectorHeavy, 3);

        // 关键词权重高：B 关键词 rank=1 贡献更大
        QueryIntent keywordHeavy = new QueryIntent(QueryType.EXACT_KEYWORD, 0.7, 1.3);
        List<HybridCandidate> result2 = ranker.rank(vectorResults, keywordResults, keywordHeavy, 3);

        // 向量权重高时 A 排第一（A 向量 rank=1 权重大）
        assertEquals("1_0", result1.get(0).getChunkId());
        // 关键词权重高时 B 排第一（B 关键词 rank=1 权重大）
        assertEquals("2_0", result2.get(0).getChunkId());
    }

    @Test
    @DisplayName("只有向量结果时正常工作")
    void onlyVectorResults() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").vectorRank(1).vectorScore(0.7).build(),
                HybridCandidate.builder().chunkId("2_0").documentId(2L).title("B").vectorRank(2).vectorScore(0.6).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(2, selected.size());
    }

    @Test
    @DisplayName("最终过滤：vectorScore >= 0.55 直接保留")
    void filterStrongRelevance() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").vectorRank(1).vectorScore(0.6).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(1, selected.size());
        assertTrue(selected.get(0).isSelected());
    }

    @Test
    @DisplayName("最终过滤：弱相关但命中高价值关键词也保留")
    void filterWeakButHighValueKeyword() {
        List<HybridCandidate> keywordResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("NullPointerException修复")
                        .keywordRank(1).keywordScore(50.0)
                        .matchedTerms(List.of("NullPointerException"))
                        .build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(List.of(), keywordResults, intent, 3);

        assertEquals(1, selected.size());
        assertTrue(selected.get(0).isSelected());
    }

    @Test
    @DisplayName("全部过滤时 fallback 保留最高分 1 条")
    void fallbackWhenAllFiltered() {
        List<HybridCandidate> vectorResults = List.of(
                HybridCandidate.builder().chunkId("1_0").documentId(1L).title("A").vectorRank(1).vectorScore(0.1).build()
        );
        QueryIntent intent = new QueryIntent(QueryType.DEFAULT, 1.1, 0.9);

        List<HybridCandidate> selected = ranker.rank(vectorResults, List.of(), intent, 3);

        assertEquals(1, selected.size());
        assertTrue(selected.get(0).isSelected());
    }
}
