package com.example.chatbot.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElasticsearchKeywordSearchServiceTest {

    @Test
    @DisplayName("Keyword search payload uses user filter and boosted multi match")
    void payloadUsesUserFilterAndBoostedMultiMatch() {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setEnabled(true);
        properties.getElasticsearch().setMinimumShouldMatch("60%");
        ElasticsearchKeywordSearchService service = new ElasticsearchKeywordSearchService(
                properties,
                mock(RestTemplate.class));

        Map<String, Object> payload = service.searchPayload(7L, "混合检索 RRF", 10);
        String json = toJson(payload);

        assertTrue(json.contains("\"userId\":7"));
        assertTrue(json.contains("\"enabled\":true"));
        assertTrue(json.contains("title^5"));
        assertTrue(json.contains("keywords^4"));
        assertTrue(json.contains("tags^3"));
        assertTrue(json.contains("summary^2"));
        assertTrue(json.contains("content"));
        assertTrue(json.contains("60%"));
    }

    @Test
    @DisplayName("Keyword search converts Elasticsearch hits to search results")
    void convertsHitsToSearchResults() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setEnabled(true);
        properties.getElasticsearch().setBaseUrl("http://localhost:9200");
        properties.getElasticsearch().setIndexName("ai_studio_knowledge_v3");
        RestTemplate restTemplate = mock(RestTemplate.class);
        JsonNode body = new ObjectMapper().readTree("""
                {
                  "hits": {
                    "hits": [
                      {
                        "_score": 9.5,
                        "_source": {
                          "chunkId": "12_0",
                          "docId": 12,
                          "title": "混合检索 RRF",
                          "content": "RRF 使用 rank 融合 ES 和向量结果"
                        },
                        "highlight": {
                          "content": ["RRF 使用 rank 融合"]
                        }
                      }
                    ]
                  }
                }
                """);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));
        ElasticsearchKeywordSearchService service = new ElasticsearchKeywordSearchService(properties, restTemplate);

        List<SearchResult> results = service.search(7L, "混合检索", 10);

        assertEquals(1, results.size());
        assertEquals("12_0", results.get(0).getChunkId());
        assertEquals(12L, results.get(0).getDocId());
        assertEquals(9.5, results.get(0).getScore());
        assertEquals(1, results.get(0).getRank());
        assertEquals("elasticsearch", results.get(0).getSource());
        assertEquals("RRF 使用 rank 融合", results.get(0).getContent());
    }

    private String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
