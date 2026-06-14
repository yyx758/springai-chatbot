package com.example.chatbot.rag;

import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ElasticsearchRagServiceTest {

    @Test
    @DisplayName("Search converts Elasticsearch hits to keyword candidates")
    void searchConvertsHits() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setEnabled(true);
        properties.getElasticsearch().setBaseUrl("http://localhost:9200");
        properties.getElasticsearch().setIndexName("ai_studio_knowledge");
        properties.getElasticsearch().setTopK(10);

        RestTemplate restTemplate = mock(RestTemplate.class);
        JsonNode body = new ObjectMapper().readTree("""
                {
                  "hits": {
                    "hits": [
                      {
                        "_score": 2.5,
                        "_source": {
                          "chunk_id": "1_0",
                          "document_id": 1,
                          "title": "Redis 缓存策略",
                          "content": "Redis 可以设置 TTL 和 LRU 策略"
                        },
                        "highlight": {
                          "content": ["Redis 可以设置 TTL"]
                        }
                      }
                    ]
                  }
                }
                """);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(body));

        ElasticsearchRagService service = new ElasticsearchRagService(
                properties,
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentChunker.class),
                restTemplate);

        List<HybridCandidate> results = service.searchKeywordCandidates(7L, "Redis TTL", 20);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getDocumentId());
        assertEquals("1_0", results.get(0).getChunkId());
        assertEquals("Redis 可以设置 TTL", results.get(0).getSnippet());
        assertTrue(results.get(0).getKeywordScore() >= 8.0);
        assertEquals(List.of("Redis", "TTL"), results.get(0).getMatchedTerms());
    }

    @Test
    @DisplayName("Disabled Elasticsearch does not call remote service")
    void disabledDoesNotCallRemote() {
        RagProperties properties = new RagProperties();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ElasticsearchRagService service = new ElasticsearchRagService(
                properties,
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentChunker.class),
                restTemplate);

        List<HybridCandidate> results = service.searchKeywordCandidates(7L, "Redis TTL", 20);

        assertTrue(results.isEmpty());
        verifyNoInteractions(restTemplate);
    }
}
