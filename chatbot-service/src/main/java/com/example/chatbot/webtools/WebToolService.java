package com.example.chatbot.webtools;

import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileCreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WebToolService {

    private final WebToolsProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentWorkspaceService workspaceService;
    private final RestTemplate restTemplate;

    public Map<String, Object> search(String query, Integer limit) {
        ensureEnabled();
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        int finalLimit = Math.min(Math.max(limit == null ? properties.getMaxResults() : limit, 1), properties.getMaxResults());
        String url = trimTrailingSlash(properties.getFirecrawl().getBaseUrl()) + "/v2/search";
        Map<String, Object> body = Map.of("query", query.trim(), "limit", finalLimit);
        JsonNode root = postJson(url, body);
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode item : data) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", item.path("title").asText(""));
                row.put("url", item.path("url").asText(""));
                row.put("description", item.path("description").asText(item.path("snippet").asText("")));
                results.add(row);
            }
        }
        return Map.of("success", true, "query", query.trim(), "results", results);
    }

    public Map<String, Object> fetch(String url) {
        ensureEnabled();
        validateExternalUrl(url);
        String endpoint = trimTrailingSlash(properties.getFirecrawl().getBaseUrl()) + "/v2/scrape";
        Map<String, Object> body = Map.of("url", url.trim(), "formats", List.of("markdown"));
        JsonNode root = postJson(endpoint, body);
        JsonNode data = root.path("data");
        String markdown = data.path("markdown").asText("");
        if (markdown.length() > properties.getMaxContentChars()) {
            markdown = markdown.substring(0, properties.getMaxContentChars());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("url", url.trim());
        result.put("title", data.path("metadata").path("title").asText(""));
        result.put("content", markdown);
        result.put("contentLength", markdown.length());
        return result;
    }

    public Map<String, Object> createWorkspaceFileFromWebPage(Long userId, String sessionId, String url, String relativePath) {
        Map<String, Object> fetched = fetch(url);
        String content = "# " + String.valueOf(fetched.getOrDefault("title", "Web Page")).trim() + "\n\n"
                + "Source: " + url.trim() + "\n\n"
                + String.valueOf(fetched.getOrDefault("content", ""));
        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath(relativePath);
        request.setContent(content);
        request.setContentType("text/markdown");
        request.setOverwrite(true);
        AgentWorkspaceFile file = workspaceService.createFile(userId, sessionId, request);
        return workspaceService.toFileMap(file);
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "web tools are disabled");
        }
        if (!"firecrawl".equalsIgnoreCase(properties.getProvider())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported web tools provider");
        }
        if (properties.getFirecrawl().getApiKey() == null || properties.getFirecrawl().getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Firecrawl API key is not configured");
        }
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(properties.getFirecrawl().getApiKey());
        try {
            String response = restTemplate.postForObject(url, new HttpEntity<>(body, headers), String.class);
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "web tool request failed: " + e.getMessage(), e);
        }
    }

    public void validateExternalUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only http and https URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url host is required");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (properties.getBlockedHosts().stream().anyMatch(blocked -> lowerHost.equals(blocked.toLowerCase(Locale.ROOT)))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "blocked host");
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "private network URLs are not allowed");
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception ignored) {
            // DNS failures are handled by the provider request.
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
