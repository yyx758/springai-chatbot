package com.example.chatbot.rag;

import com.example.chatbot.dto.RagReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VectorRagServiceTest {

    @Test
    @DisplayName("Vector search falls back to raw top candidates when threshold filters everything")
    void fallsBackToRawTopCandidates() {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        PgVectorClient pgVectorClient = mock(PgVectorClient.class);
        RagProperties properties = new RagProperties();
        properties.getVector().setSimilarityThreshold(0.55);

        when(pgVectorClient.isEnabled()).thenReturn(true);
        when(embeddingClient.embed("退款政策")).thenReturn(List.of(0.1, 0.2));
        when(pgVectorClient.vectorLiteral(List.of(0.1, 0.2))).thenReturn("[0.1,0.2]");
        when(pgVectorClient.search(eq(7L), eq("[0.1,0.2]"), eq(20), eq(0.55))).thenReturn(List.of());
        when(pgVectorClient.searchRawTopK(eq(7L), eq("[0.1,0.2]"), eq(3))).thenReturn(List.of(
                RagReference.builder()
                        .chunkId("1_0")
                        .documentId(1L)
                        .title("退款政策")
                        .snippet("七天内可以申请退款")
                        .score(0.42)
                        .build()
        ));

        VectorRagService service = new VectorRagService(embeddingClient, pgVectorClient, properties);

        List<RagReference> results = service.retrieve(7L, "退款政策", 20);

        assertEquals(1, results.size());
        assertEquals("1_0", results.get(0).getChunkId());
        verify(pgVectorClient).searchRawTopK(eq(7L), eq("[0.1,0.2]"), eq(3));
    }
}
