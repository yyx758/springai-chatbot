package com.example.chatbot.rag;

import com.example.chatbot.dto.RagReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorRagService {

    private final EmbeddingClient embeddingClient;
    private final PgVectorClient pgVectorClient;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return pgVectorClient.isEnabled();
    }

    public List<RagReference> retrieve(Long userId, String query, int topK) {
        if (!isEnabled()) {
            log.warn("[VectorRAG] not enabled, isEnabled=false");
            return List.of();
        }
        double threshold = ragProperties.getVector().getSimilarityThreshold();
        int finalTopK = topK <= 0 ? ragProperties.getVector().getTopK() : topK;
        log.info("[VectorRAG] userId={}, query='{}', topK={}, threshold={}", userId, query, finalTopK, threshold);
        List<Double> embedding = embeddingClient.embed(query);
        log.info("[VectorRAG] embedding dims={}", embedding.size());
        String vector = pgVectorClient.vectorLiteral(embedding);
        List<RagReference> results = pgVectorClient.search(userId, vector, finalTopK, threshold);
        log.info("[VectorRAG] results={}", results.size());
        return results;
    }
}
