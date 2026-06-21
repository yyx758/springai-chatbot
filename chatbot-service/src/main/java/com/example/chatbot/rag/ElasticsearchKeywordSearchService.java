package com.example.chatbot.rag;

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
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchKeywordSearchService {

    public static final String SOURCE = "elasticsearch";

    private final RagProperties ragProperties;
    private final RestTemplate restTemplate;

    public boolean isEnabled() {
        RagProperties.Elasticsearch elasticsearch = ragProperties.getElasticsearch();
        return elasticsearch != null
                && elasticsearch.isEnabled()
                && hasText(elasticsearch.getBaseUrl())
                && hasText(elasticsearch.getIndexName());
    }

    public List<SearchResult> search(Long userId, String query, int topK) {
        if (!isEnabled() || userId == null || !hasText(query)) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(topK, Math.max(1, ragProperties.getElasticsearch().getTopK())));
        Map<String, Object> payload = searchPayload(userId, query, limit);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    indexUrl() + "/_search",
                    HttpMethod.POST,
                    new HttpEntity<>(payload, headers()),
                    JsonNode.class);
            List<SearchResult> results = toResults(response.getBody());
            logResults(query, results);
            return results;
        } catch (RestClientException e) {
            log.warn("[ElasticsearchKeyword] search failed, falling back. error={}", e.getMessage());
            return List.of();
        }
    }

    Map<String, Object> searchPayload(Long userId, String query, int limit) {
        List<Object> should = new ArrayList<>();
        should.add(Map.of("multi_match", Map.of(
                "query", query,
                "fields", List.of("title^5", "keywords^4", "tags^3", "summary^2"),
                "type", "best_fields",
                "operator", "or",
                "minimum_should_match", ragProperties.getElasticsearch().getMinimumShouldMatch()
        )));
        should.add(Map.of("multi_match", Map.of(
                "query", query,
                "fields", List.of("title.ngram^4", "keywords.ngram^3", "tags.ngram^2", "summary.ngram^1.5", "content.ngram"),
                "type", "best_fields",
                "operator", "or",
                "minimum_should_match", "75%"
        )));
        should.add(Map.of("match_phrase", Map.of("title", Map.of("query", query, "boost", 8.0))));
        should.add(Map.of("match_phrase", Map.of("keywords", Map.of("query", query, "boost", 6.0))));
        should.add(Map.of("match_phrase", Map.of("tags", Map.of("query", query, "boost", 5.0))));
        should.add(Map.of("match_phrase", Map.of("summary", Map.of("query", query, "boost", 3.0))));
        should.add(Map.of("match_phrase", Map.of("content", Map.of("query", query, "boost", 2.0))));
        should.add(Map.of("term", Map.of("title.keyword", Map.of("value", query, "boost", 10.0))));
        should.add(Map.of("term", Map.of("keywords.keyword", Map.of("value", query, "boost", 6.0))));
        should.add(Map.of("term", Map.of("tags.keyword", Map.of("value", query, "boost", 6.0))));

        return Map.of(
                "size", limit,
                "query", Map.of(
                        "bool", Map.of(
                                "filter", List.of(
                                        Map.of("term", Map.of("userId", userId)),
                                        Map.of("term", Map.of("enabled", true))
                                ),
                                "should", should,
                                "minimum_should_match", 1
                        )
                ),
                "highlight", Map.of(
                        "pre_tags", List.of(""),
                        "post_tags", List.of(""),
                        "fields", Map.of(
                                "content", Map.of("fragment_size", 180, "number_of_fragments", 1),
                                "summary", Map.of("fragment_size", 180, "number_of_fragments", 1),
                                "title", Map.of("number_of_fragments", 0),
                                "keywords", Map.of("number_of_fragments", 0),
                                "tags", Map.of("number_of_fragments", 0)
                        )
                )
        );
    }

    private List<SearchResult> toResults(JsonNode root) {
        JsonNode hits = root == null ? null : root.path("hits").path("hits");
        if (hits == null || !hits.isArray()) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        int rank = 1;
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            Long docId = longValue(source.path("docId"), source.path("document_id"));
            String chunkId = text(source.path("chunkId"), text(source.path("chunk_id"), docId == null ? "" : docId + "_0"));
            if (!hasText(chunkId) || docId == null) {
                continue;
            }
            results.add(SearchResult.builder()
                    .chunkId(chunkId)
                    .docId(docId)
                    .title(text(source.path("title"), ""))
                    .content(snippet(hit, source))
                    .score(hit.path("_score").asDouble(0.0))
                    .rank(rank++)
                    .source(SOURCE)
                    .build());
        }
        return results;
    }

    private void logResults(String query, List<SearchResult> results) {
        for (SearchResult result : results) {
            log.info("[ElasticsearchKeyword] query='{}', rank={}, chunkId={}, docId={}, title='{}', score={}",
                    truncate(query, 40), result.getRank(), result.getChunkId(), result.getDocId(),
                    truncate(result.getTitle(), 30), result.getScore());
        }
    }

    private String snippet(JsonNode hit, JsonNode source) {
        JsonNode highlight = hit.path("highlight");
        for (String field : List.of("content", "summary", "title", "keywords", "tags")) {
            if (highlight.has(field) && highlight.get(field).isArray() && highlight.get(field).size() > 0) {
                return highlight.get(field).get(0).asText();
            }
        }
        String content = text(source.path("content"), "");
        return content.length() > 180 ? content.substring(0, 180) + "..." : content;
    }

    private Long longValue(JsonNode primary, JsonNode fallback) {
        if (primary != null && primary.isNumber()) {
            return primary.asLong();
        }
        if (fallback != null && fallback.isNumber()) {
            return fallback.asLong();
        }
        return null;
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

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
