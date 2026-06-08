package com.example.chatbot.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public List<Double> embed(String text) {
        RagProperties.Embedding embedding = ragProperties.getEmbedding();
        if (embedding.getBaseUrl() == null || embedding.getBaseUrl().isBlank()) {
            throw new IllegalStateException("embedding base-url is not configured");
        }
        if ("openai-compatible".equalsIgnoreCase(embedding.getProvider())) {
            return embedOpenAiCompatible(text);
        }
        return embedRemoteOllama(text);
    }

    private List<Double> embedRemoteOllama(String text) {
        String url = trimTrailingSlash(ragProperties.getEmbedding().getBaseUrl())
                + resolveEmbeddingsPath("/api/embeddings");
        Map<String, Object> body = Map.of(
                "model", ragProperties.getEmbedding().getModel(),
                "prompt", text
        );
        JsonNode root = postJson(url, body);
        return toDoubleList(root.get("embedding"));
    }

    private List<Double> embedOpenAiCompatible(String text) {
        String url = trimTrailingSlash(ragProperties.getEmbedding().getBaseUrl())
                + resolveEmbeddingsPath("/v1/embeddings");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ragProperties.getEmbedding().getModel());
        body.put("input", text);
        int dimensions = ragProperties.getVector().getDimensions();
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        String encodingFormat = ragProperties.getEmbedding().getEncodingFormat();
        if (encodingFormat != null && !encodingFormat.isBlank()) {
            body.put("encoding_format", encodingFormat.trim());
        }
        JsonNode root = postJson(url, body);
        return toDoubleList(root.path("data").path(0).path("embedding"));
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String apiKey = ragProperties.getEmbedding().getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.setBearerAuth(apiKey);
        }
        try {
            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new IllegalStateException("embedding request failed: " + e.getMessage(), e);
        }
    }

    private List<Double> toDoubleList(JsonNode arrayNode) {
        if (arrayNode == null || !arrayNode.isArray()) {
            throw new IllegalStateException("embedding response does not contain an embedding array");
        }
        List<Double> values = new ArrayList<>(arrayNode.size());
        for (JsonNode item : arrayNode) {
            values.add(item.asDouble());
        }
        return values;
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String resolveEmbeddingsPath(String defaultPath) {
        String path = ragProperties.getEmbedding().getEmbeddingsPath();
        if (path == null || path.isBlank()) {
            return defaultPath;
        }
        String result = path.trim();
        return result.startsWith("/") ? result : "/" + result;
    }
}
