package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RrfFusionServiceTest {

    @Test
    @DisplayName("RRF fuses vector and Elasticsearch ranks by chunk id")
    void rrfFusesByChunkId() {
        HybridRagProperties properties = new HybridRagProperties();
        properties.setRrfK(60);
        RrfFusionService service = new RrfFusionService(properties);

        List<SearchResult> vector = List.of(
                SearchResult.builder()
                        .chunkId("1_0")
                        .docId(1L)
                        .title("Redisson 看门狗机制")
                        .content("watchdog 自动续期")
                        .rank(1)
                        .score(0.82)
                        .source("vector")
                        .build());
        List<SearchResult> keyword = List.of(
                SearchResult.builder()
                        .chunkId("1_0")
                        .docId(1L)
                        .title("Redisson 看门狗机制")
                        .content("watchdog 自动续期")
                        .rank(1)
                        .score(12.5)
                        .source("elasticsearch")
                        .build(),
                SearchResult.builder()
                        .chunkId("2_0")
                        .docId(2L)
                        .title("Redis 缓存")
                        .content("缓存策略")
                        .rank(2)
                        .score(8.0)
                        .source("elasticsearch")
                        .build());

        List<HybridSearchResult> results = service.fuse(vector, keyword, 3);

        assertEquals(2, results.size());
        assertEquals("1_0", results.get(0).getChunkId());
        assertEquals(1, results.get(0).getVectorRank());
        assertEquals(1, results.get(0).getKeywordRank());
        assertEquals(0.82, results.get(0).getVectorScore());
        assertEquals(12.5, results.get(0).getKeywordScore());
        assertEquals(List.of("vector", "elasticsearch"), results.get(0).getSources());
        assertTrue(results.get(0).getRrfScore() > results.get(1).getRrfScore());
    }
}
