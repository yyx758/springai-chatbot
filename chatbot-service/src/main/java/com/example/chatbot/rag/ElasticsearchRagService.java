package com.example.chatbot.rag;

import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchRagService {

    private final RagProperties ragProperties;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentChunker documentChunker;
    private final RestTemplate restTemplate;

    public boolean isEnabled() {
        RagProperties.Elasticsearch elasticsearch = ragProperties.getElasticsearch();
        return elasticsearch != null
                && elasticsearch.isEnabled()
                && hasText(elasticsearch.getBaseUrl())
                && hasText(elasticsearch.getIndexName());
    }

    public void initializeIndexIfNeeded() {
        if (!isEnabled() || !ragProperties.getElasticsearch().isInitializeIndex()) {
            return;
        }
        String url = indexUrl();
        Map<String, Object> mapping = Map.of(
                "mappings", Map.of(
                        "properties", Map.of(
                                "chunk_id", Map.of("type", "keyword"),
                                "document_id", Map.of("type", "long"),
                                "user_id", Map.of("type", "long"),
                                "enabled", Map.of("type", "boolean"),
                                "title", Map.of("type", "text", "analyzer", "standard"),
                                "content", Map.of("type", "text", "analyzer", "standard"),
                                "tags", Map.of("type", "text", "analyzer", "standard"),
                                "updated_time", Map.of("type", "date")
                        )
                )
        );
        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(mapping, headers()), String.class);
            log.info("[ElasticsearchRAG] index initialized, index={}", ragProperties.getElasticsearch().getIndexName());
        } catch (RestClientException e) {
            log.warn("[ElasticsearchRAG] index initialization skipped/failed: {}", e.getMessage());
        }
    }

    public void indexDocument(Long userId, Long documentId) {
        if (!isEnabled() || userId == null || documentId == null) {
            return;
        }
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null || !userId.equals(document.getUserId())) {
            deleteDocument(userId, documentId);
            return;
        }
        if (!Boolean.TRUE.equals(document.getEnabled())) {
            deleteDocument(userId, documentId);
            return;
        }

        initializeIndexIfNeeded();
        deleteDocument(userId, documentId);

        List<DocumentChunk> chunks = documentChunker.chunk(document.getContent());
        if (chunks.isEmpty()) {
            return;
        }
        for (DocumentChunk chunk : chunks) {
            String chunkId = document.getId() + "_" + chunk.index();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chunk_id", chunkId);
            payload.put("document_id", document.getId());
            payload.put("user_id", document.getUserId());
            payload.put("enabled", Boolean.TRUE);
            payload.put("title", valueOrEmpty(document.getTitle()));
            payload.put("content", chunk.content());
            payload.put("tags", valueOrEmpty(document.getTags()));
            if (document.getUpdatedTime() != null) {
                payload.put("updated_time", document.getUpdatedTime().toString());
            }
            try {
                restTemplate.exchange(documentUrl(chunkId), HttpMethod.PUT, new HttpEntity<>(payload, headers()), String.class);
            } catch (RestClientException e) {
                log.warn("[ElasticsearchRAG] document chunk index failed, chunkId={}, error={}", chunkId, e.getMessage());
                return;
            }
        }
        log.info("[ElasticsearchRAG] document indexed, userId={}, documentId={}, chunks={}", userId, documentId, chunks.size());
    }

    public void deleteDocument(Long userId, Long documentId) {
        if (!isEnabled() || userId == null || documentId == null) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "query", Map.of(
                        "bool", Map.of(
                                "filter", List.of(
                                        Map.of("term", Map.of("user_id", userId)),
                                        Map.of("term", Map.of("document_id", documentId))
                                )
                        )
                )
        );
        try {
            restTemplate.exchange(indexUrl() + "/_delete_by_query", HttpMethod.POST, new HttpEntity<>(payload, headers()), String.class);
            log.info("[ElasticsearchRAG] document deleted, userId={}, documentId={}", userId, documentId);
        } catch (RestClientException e) {
            log.warn("[ElasticsearchRAG] document delete failed, userId={}, documentId={}, error={}",
                    userId, documentId, e.getMessage());
        }
    }

    public List<HybridCandidate> searchKeywordCandidates(Long userId, String query, int limit) {
        if (!isEnabled() || userId == null || !hasText(query)) {
            return List.of();
        }
        int finalLimit = Math.max(1, Math.min(limit, Math.max(1, ragProperties.getElasticsearch().getTopK())));
        Map<String, Object> payload = Map.of(
                "size", finalLimit,
                "query", Map.of(
                        "bool", Map.of(
                                "filter", List.of(
                                        Map.of("term", Map.of("user_id", userId)),
                                        Map.of("term", Map.of("enabled", true))
                                ),
                                "must", List.of(
                                        Map.of("multi_match", Map.of(
                                                "query", query,
                                                "fields", List.of("title^3", "tags^2", "content"),
                                                "type", "best_fields",
                                                "operator", "or"
                                        ))
                                )
                        )
                ),
                "highlight", Map.of(
                        "pre_tags", List.of(""),
                        "post_tags", List.of(""),
                        "fields", Map.of(
                                "content", Map.of("fragment_size", 160, "number_of_fragments", 1),
                                "title", Map.of("number_of_fragments", 0),
                                "tags", Map.of("number_of_fragments", 0)
                        )
                )
        );

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    indexUrl() + "/_search",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers()),
                    JsonNode.class);
            return toCandidates(response.getBody(), query);
        } catch (RestClientException e) {
            log.warn("[ElasticsearchRAG] keyword search failed, falling back. error={}", e.getMessage());
            return List.of();
        }
    }

    private List<HybridCandidate> toCandidates(JsonNode root, String query) {
        JsonNode hits = root == null ? null : root.path("hits").path("hits");
        if (hits == null || !hits.isArray()) {
            return List.of();
        }
        List<HybridCandidate> candidates = new ArrayList<>();
        List<String> matchedTerms = queryTerms(query);
        int rank = 1;
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            Long documentId = source.path("document_id").isNumber() ? source.path("document_id").asLong() : null;
            if (documentId == null) {
                continue;
            }
            double score = hit.path("_score").asDouble(0.0);
            double keywordScore = Math.max(score * 4.0, 8.0);
            candidates.add(HybridCandidate.builder()
                    .chunkId(text(source.path("chunk_id"), documentId + "_0"))
                    .documentId(documentId)
                    .title(text(source.path("title"), ""))
                    .snippet(snippet(hit, source))
                    .keywordRank(rank++)
                    .keywordScore(keywordScore)
                    .matchedTerms(matchedTerms)
                    .coveredTerms(List.of())
                    .build());
        }
        return candidates;
    }

    private List<String> queryTerms(String query) {
        if (!hasText(query)) {
            return List.of("elasticsearch");
        }
        String normalized = query.trim();
        List<String> terms = new ArrayList<>();
        for (String token : normalized.split("[\\s,，。！？?:：；、]+")) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms.isEmpty() ? List.of("elasticsearch") : terms;
    }

    private String snippet(JsonNode hit, JsonNode source) {
        JsonNode highlight = hit.path("highlight");
        if (highlight.has("content") && highlight.get("content").isArray() && highlight.get("content").size() > 0) {
            return highlight.get("content").get(0).asText();
        }
        String content = text(source.path("content"), "");
        return content.length() > 160 ? content.substring(0, 160) + "..." : content;
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        RagProperties.Elasticsearch elasticsearch = ragProperties.getElasticsearch();
        if (hasText(elasticsearch.getApiKey())) {
            headers.set(HttpHeaders.AUTHORIZATION, "ApiKey " + elasticsearch.getApiKey().trim());
        } else if (hasText(elasticsearch.getUsername()) || hasText(elasticsearch.getPassword())) {
            String token = valueOrEmpty(elasticsearch.getUsername()) + ":" + valueOrEmpty(elasticsearch.getPassword());
            headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
        }
        return headers;
    }

    private String indexUrl() {
        RagProperties.Elasticsearch elasticsearch = ragProperties.getElasticsearch();
        return trimRight(elasticsearch.getBaseUrl(), "/") + "/" + elasticsearch.getIndexName();
    }

    private String documentUrl(String chunkId) {
        return indexUrl() + "/_doc/" + chunkId;
    }

    private String trimRight(String value, String suffix) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith(suffix)) {
            result = result.substring(0, result.length() - suffix.length());
        }
        return result;
    }

    private String text(JsonNode node, String fallback) {
        return node == null || node.isMissingNode() || node.isNull() ? fallback : node.asText(fallback);
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
