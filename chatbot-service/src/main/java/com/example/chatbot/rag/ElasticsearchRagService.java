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
import org.springframework.web.client.HttpClientErrorException;
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
    private final KeywordExtractor keywordExtractor;

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
        if (indexExists(url)) {
            return;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", Map.of("type", "keyword"));
        properties.put("chunkId", Map.of("type", "keyword"));
        properties.put("docId", Map.of("type", "long"));
        properties.put("userId", Map.of("type", "long"));
        properties.put("chunk_id", Map.of("type", "keyword"));
        properties.put("document_id", Map.of("type", "long"));
        properties.put("user_id", Map.of("type", "long"));
        properties.put("enabled", Map.of("type", "boolean"));
        properties.put("title", textFieldMapping(true));
        properties.put("content", textFieldMapping(false));
        properties.put("summary", textFieldMapping(false));
        properties.put("keywords", textFieldMapping(true));
        properties.put("tags", textFieldMapping(true));
        properties.put("fileName", textFieldMapping(true));
        properties.put("createdAt", Map.of("type", "date"));
        properties.put("updatedAt", Map.of("type", "date"));
        properties.put("updated_time", Map.of("type", "date"));

        Map<String, Object> mapping = Map.of(
                "settings", Map.of(
                        "index.max_ngram_diff", 2,
                        "analysis", Map.of(
                                "tokenizer", Map.of(
                                        "ai_studio_ngram_tokenizer", Map.of(
                                                "type", "ngram",
                                                "min_gram", 2,
                                                "max_gram", 4,
                                                "token_chars", List.of("letter", "digit")
                                        )
                                ),
                                "analyzer", Map.of(
                                        "ai_studio_ngram", Map.of(
                                                "type", "custom",
                                                "tokenizer", "ai_studio_ngram_tokenizer",
                                                "filter", List.of("lowercase")
                                        )
                                )
                        )
                ),
                "mappings", Map.of(
                        "properties", properties
                )
        );
        try {
            restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(mapping, headers()), String.class);
            log.info("[ElasticsearchRAG] index initialized, index={}", ragProperties.getElasticsearch().getIndexName());
        } catch (RestClientException e) {
            log.warn("[ElasticsearchRAG] index initialization skipped/failed: {}", e.getMessage());
        }
    }

    private boolean indexExists(String url) {
        try {
            restTemplate.exchange(url, HttpMethod.HEAD, new HttpEntity<>(headers()), String.class);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            log.warn("[ElasticsearchRAG] index existence check failed, continue initialization: {}", e.getMessage());
            return false;
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
            payload.put("id", chunkId);
            payload.put("chunkId", chunkId);
            payload.put("docId", document.getId());
            payload.put("userId", document.getUserId());
            payload.put("chunk_id", chunkId);
            payload.put("document_id", document.getId());
            payload.put("user_id", document.getUserId());
            payload.put("enabled", Boolean.TRUE);
            payload.put("title", valueOrEmpty(document.getTitle()));
            payload.put("content", chunk.content());
            payload.put("summary", "");
            payload.put("keywords", List.of());
            payload.put("tags", valueOrEmpty(document.getTags()));
            payload.put("fileName", valueOrEmpty(document.getFileKey()));
            if (document.getCreatedTime() != null) {
                payload.put("createdAt", document.getCreatedTime().toString());
            }
            if (document.getUpdatedTime() != null) {
                payload.put("updatedAt", document.getUpdatedTime().toString());
            }
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
                                "should", List.of(
                                        Map.of("term", Map.of("title.exact", Map.of("value", query, "boost", 8.0))),
                                        Map.of("term", Map.of("tags.exact", Map.of("value", query, "boost", 5.0))),
                                        Map.of("match_phrase", Map.of("title", Map.of("query", query, "boost", 6.0))),
                                        Map.of("match_phrase", Map.of("tags", Map.of("query", query, "boost", 4.0))),
                                        Map.of("match_phrase", Map.of("content", Map.of("query", query, "boost", 3.0))),
                                        Map.of("multi_match", Map.of(
                                                "query", query,
                                                "fields", List.of("title^3", "tags^2", "content"),
                                                "type", "best_fields",
                                                "operator", "or"
                                        )),
                                        Map.of("multi_match", Map.of(
                                                "query", query,
                                                "fields", List.of("title.ngram^2", "tags.ngram^1.5", "content.ngram"),
                                                "type", "best_fields",
                                                "operator", "or"
                                        ))
                                ),
                                "minimum_should_match", 1
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
        KeywordExtractor.CoverageResult coverage = keywordExtractor.removeTermsCoveredByLongerTerms(queryTerms(query));
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
                    .matchedTerms(coverage.matched())
                    .coveredTerms(coverage.covered())
                    .build());
        }
        return candidates;
    }

    private List<String> queryTerms(String query) {
        if (!hasText(query)) {
            return List.of("elasticsearch");
        }
        List<String> terms = new ArrayList<>();
        terms.addAll(keywordExtractor.extractTechnicalTerms(query));
        terms.addAll(keywordExtractor.extractQueryPhrases(query));
        terms.addAll(keywordExtractor.extractBigrams(query));
        String normalized = query.trim();
        for (String token : normalized.split("[\\s,，。！？?:：；、]+")) {
            if (token.length() >= 2 && !keywordExtractor.isStopWord(token)) {
                terms.add(token);
            }
        }
        if (terms.isEmpty() && normalized.length() >= 2) {
            terms.add(normalized);
        }
        return terms.stream().distinct().toList();
    }

    private Map<String, Object> textFieldMapping(boolean exact) {
        Map<String, Object> fields = exact
                ? Map.of(
                        "exact", Map.of("type", "keyword", "ignore_above", 256),
                        "ngram", Map.of("type", "text", "analyzer", "ai_studio_ngram", "search_analyzer", "standard")
                )
                : Map.of("ngram", Map.of("type", "text", "analyzer", "ai_studio_ngram", "search_analyzer", "standard"));
        return Map.of(
                "type", "text",
                "analyzer", "standard",
                "fields", fields
        );
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
