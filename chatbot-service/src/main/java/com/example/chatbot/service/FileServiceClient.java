package com.example.chatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

    public FileServiceClient() {
        this.restTemplate = new RestTemplate();
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
}
