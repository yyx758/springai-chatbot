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
import org.mockito.ArgumentCaptor;

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
        properties.getElasticsearch().setIndexName("ai_studio_knowledge_v2");
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
                restTemplate,
                new KeywordExtractor());

        List<HybridCandidate> results = service.searchKeywordCandidates(7L, "Redis TTL", 20);

        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getDocumentId());
        assertEquals("1_0", results.get(0).getChunkId());
        assertEquals("Redis 可以设置 TTL", results.get(0).getSnippet());
        assertTrue(results.get(0).getKeywordScore() >= 8.0);
        assertEquals(List.of("Redis", "TTL"), results.get(0).getMatchedTerms());
    }

    @Test
    @DisplayName("Index initialization creates ngram mapping")
    void initializeIndexCreatesNgramMapping() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setEnabled(true);
        properties.getElasticsearch().setBaseUrl("http://localhost:9200");
        properties.getElasticsearch().setIndexName("ai_studio_knowledge_v2");

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));

        ElasticsearchRagService service = new ElasticsearchRagService(
                properties,
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentChunker.class),
                restTemplate,
                new KeywordExtractor());

        service.initializeIndexIfNeeded();

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("http://localhost:9200/ai_studio_knowledge_v2"),
                eq(HttpMethod.PUT), entityCaptor.capture(), eq(String.class));
        String payload = new ObjectMapper().writeValueAsString(entityCaptor.getValue().getBody());
        assertTrue(payload.contains("ai_studio_ngram_tokenizer"));
        assertTrue(payload.contains("ai_studio_ngram"));
        assertTrue(payload.contains("title"));
        assertTrue(payload.contains("exact"));
    }

    @Test
    @DisplayName("Search uses boosted bool query with ngram fields")
    void searchUsesBoostedBoolQueryWithNgramFields() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setEnabled(true);
        properties.getElasticsearch().setBaseUrl("http://localhost:9200");
        properties.getElasticsearch().setIndexName("ai_studio_knowledge_v2");
        properties.getElasticsearch().setTopK(50);

        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(ResponseEntity.ok(new ObjectMapper().readTree("{\"hits\":{\"hits\":[]}}")));

        ElasticsearchRagService service = new ElasticsearchRagService(
                properties,
                mock(KnowledgeDocumentMapper.class),
                mock(DocumentChunker.class),
                restTemplate,
                new KeywordExtractor());

        service.searchKeywordCandidates(7L, "退款政策", 200);

        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(contains("/ai_studio_knowledge_v2/_search"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(JsonNode.class));
        String payload = new ObjectMapper().writeValueAsString(entityCaptor.getValue().getBody());
        assertTrue(payload.contains("title^3"));
        assertTrue(payload.contains("tags^2"));
        assertTrue(payload.contains("title.ngram^2"));
        assertTrue(payload.contains("content.ngram"));
        assertTrue(payload.contains("minimum_should_match"));
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
                restTemplate,
                new KeywordExtractor());

        List<HybridCandidate> results = service.searchKeywordCandidates(7L, "Redis TTL", 20);

        assertTrue(results.isEmpty());
        verifyNoInteractions(restTemplate);
    }
}
