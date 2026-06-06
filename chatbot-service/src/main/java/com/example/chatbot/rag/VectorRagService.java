package com.example.chatbot.rag;

import com.example.chatbot.dto.RagReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class VectorRagService {

    private final EmbeddingClient embeddingClient;
    private final PgVectorClient pgVectorClient;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return pgVectorClient.isEnabled();
    }

    public List<RagReference> retrieve(Long userId, String query, int topK) {
        if (!isEnabled()) {
            return List.of();
        }
        List<Double> embedding = embeddingClient.embed(query);
        String vector = pgVectorClient.vectorLiteral(embedding);
        int finalTopK = topK <= 0 ? ragProperties.getVector().getTopK() : topK;
        return pgVectorClient.search(
                userId,
                vector,
                finalTopK,
                ragProperties.getVector().getSimilarityThreshold()
        );
    }
}
