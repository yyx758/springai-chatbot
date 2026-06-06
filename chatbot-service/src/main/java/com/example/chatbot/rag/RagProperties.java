package com.example.chatbot.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private String mode = "keyword";
    private boolean fallbackToKeyword = true;
    private Chunk chunk = new Chunk();
    private Vector vector = new Vector();
    private Embedding embedding = new Embedding();

    @Data
    public static class Chunk {
        private int size = 800;
        private int overlap = 100;
        private int maxChunksPerDocument = 200;
    }

    @Data
    public static class Vector {
        private boolean enabled = false;
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private String tableName = "ai_studio_knowledge_vectors";
        private int dimensions = 1024;
        private int topK = 5;
        private double similarityThreshold = 0.55;
        private boolean initializeSchema = true;
    }

    @Data
    public static class Embedding {
        private String provider = "remote-ollama";
        private String model = "bge-m3";
        private String baseUrl = "";
        private String apiKey = "";
        private int timeoutMs = 20000;
    }
}
