package com.example.chatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件服务客户端
 * 通过 REST 调用 file-service 获取文件
 */
@Slf4j
@Component
public class FileServiceClient {

    private final RestTemplate restTemplate;

    @Value("${file.service.url:http://localhost:8081}")
    private String fileServiceUrl;

    public FileServiceClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 获取文件二进制数据
     */
    public byte[] getFileBytes(String fileKey) {
        try {
            String url = fileServiceUrl + "/api/files/download/" + fileKey;
            byte[] data = restTemplate.getForObject(url, byte[].class);
            log.info("【FileServiceClient】获取文件成功: key={}, size={}KB", fileKey, data != null ? data.length / 1024 : 0);
            return data;
        } catch (Exception e) {
            log.error("【FileServiceClient】获取文件失败: key={}", fileKey, e);
            return null;
        }
    }

    public byte[] getFileBytes(String fileKey, Long userId) {
        try {
            String url = fileServiceUrl + "/api/files/download/" + fileKey;
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(userId)),
                    byte[].class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("FileServiceClient get file bytes failed: key={}", fileKey, e);
            return null;
        }
    }

    /**
     * 获取文件信息
     */
    public Map<String, Object> getFileInfo(String fileKey) {
        try {
            String url = fileServiceUrl + "/api/files/info/" + fileKey;
            Map<String, Object> result = restTemplate.getForObject(url, Map.class);
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return (Map<String, Object>) result.get("data");
            }
            return null;
        } catch (Exception e) {
            log.error("【FileServiceClient】获取文件信息失败: key={}", fileKey, e);
            return null;
        }
    }

    public Map<String, Object> getFileInfo(String fileKey, Long userId) {
        try {
            String url = fileServiceUrl + "/api/files/info/" + fileKey;
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(userId)),
                    Map.class
            );
            Map<String, Object> result = response.getBody();
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return (Map<String, Object>) result.get("data");
            }
            return null;
        } catch (Exception e) {
            log.error("銆怓ileServiceClient銆戣幏鍙栨枃浠朵俊鎭け璐? key={}", fileKey, e);
            return null;
        }
    }

    public Map<String, Object> listFiles(int page, int size, Long userId) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(fileServiceUrl + "/api/files")
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .build()
                    .toUri();
            ResponseEntity<Map> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    new HttpEntity<>(authHeaders(userId)),
                    Map.class
            );
            Map<String, Object> result = response.getBody();
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return (Map<String, Object>) result.get("data");
            }
            return null;
        } catch (Exception e) {
            log.error("銆怓ileServiceClient銆戞枃浠跺垪琛ㄨ幏鍙栧け璐? userId={}", userId, e);
            return null;
        }
    }

    public Map<String, Object> createGeneratedKnowledgeMarkdown(Long userId, Long documentId, String title, String content) {
        try {
            String url = fileServiceUrl + "/api/files/generated/knowledge";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", title == null || title.isBlank() ? "generated-knowledge" : title.trim());
            body.put("content", content == null ? "" : content);
            body.put("bizId", documentId == null ? "" : String.valueOf(documentId));

            HttpHeaders headers = authHeaders(userId);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            Map<String, Object> result = response.getBody();
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return (Map<String, Object>) result.get("data");
            }
            log.warn("銆怓ileServiceClient銆慉gent鐢熸垚鏂囨。鍐欏叆file-service澶辫触: {}", result);
            return null;
        } catch (Exception e) {
            log.warn("銆怓ileServiceClient銆慉gent鐢熸垚鏂囨。鍐欏叆file-service寮傚父: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> createGeneratedWorkspaceFile(Long userId, Long workspaceId, String relativePath,
                                                            String content, String contentType) {
        try {
            String url = fileServiceUrl + "/api/files/generated/workspace";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("relativePath", relativePath);
            body.put("content", content == null ? "" : content);
            body.put("contentType", contentType == null ? "" : contentType);
            body.put("bizId", workspaceId == null ? "" : String.valueOf(workspaceId));

            HttpHeaders headers = authHeaders(userId);
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
            Map<String, Object> result = response.getBody();
            if (result != null && Boolean.TRUE.equals(result.get("success"))) {
                return (Map<String, Object>) result.get("data");
            }
            log.warn("FileServiceClient generated workspace file failed: {}", result);
            return null;
        } catch (Exception e) {
            log.warn("FileServiceClient generated workspace file request failed: {}", e.getMessage());
            return null;
        }
    }

    private HttpHeaders authHeaders(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-Auth-UserId", String.valueOf(userId));
        }
        return headers;
    }
}
