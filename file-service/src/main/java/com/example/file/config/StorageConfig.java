package com.example.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "file.storage")
public class StorageConfig {

    private String type = "LOCAL";

    private Local local = new Local();

    private Minio minio = new Minio();

    @Data
    public static class Local {
        private String basePath = "./uploads";
        private String urlPrefix = "/api/files";
    }

    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucket = "chatbot-files";
    }
}
