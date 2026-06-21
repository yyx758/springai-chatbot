package com.example.chatbot.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    private String mode = "hybrid";
    private boolean fallbackToKeyword = true;
    private Chunk chunk = new Chunk();
    private Vector vector = new Vector();
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Embedding embedding = new Embedding();
    private QueryEnhancer queryEnhancer = new QueryEnhancer();

    @Data
    public static class Chunk {
        private int size = 800;
        private int overlap = 100;
        private int maxChunksPerDocument = 200;
    }

    @Data
    public static class Vector {
        private boolean enabled = true;
        private String jdbcUrl = "";
        private String username = "";
        private String password = "";
        private String tableName = "ai_studio_knowledge_vectors";
        private int dimensions = 1024;
        private int topK = 5;
        private double similarityThreshold = 0.3;
        private boolean initializeSchema = true;
    }

    @Data
    public static class Elasticsearch {
        private boolean enabled = false;
        private String baseUrl = "http://localhost:9200";
        private String indexName = "ai_studio_knowledge_v3";
        private String username = "";
        private String password = "";
        private String apiKey = "";
        private int topK = 50;
        private boolean initializeIndex = true;
        private String analyzerMode = "standard_ngram";
        private String minimumShouldMatch = "60%";
    }

    @Data
    public static class Embedding {
        private String provider = "openai-compatible";
        private String model = "text-embedding-v4";
        private String baseUrl = "";
        private String embeddingsPath = "";
        private String apiKey = "";
        private String encodingFormat = "float";
        private int timeoutMs = 20000;
    }

    @Data
    public static class QueryEnhancer {
        private boolean enabled = true;
        private int maxExpandedTerms = 6;
        private String synonymFile = "classpath:rag/query-synonyms.yml";
    }
}
