package com.example.chatbot.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgVectorClientTest {

    @Test
    @DisplayName("PGVector client is disabled by default")
    void disabledByDefault() {
        RagProperties properties = new RagProperties();
        PgVectorClient client = new PgVectorClient(properties, new ObjectMapper());

        assertFalse(client.isEnabled());
        assertDoesNotThrow(() -> client.deleteDocument(1L, 2L));
    }

    @Test
    @DisplayName("Vector literal is formatted for pgvector")
    void vectorLiteral() {
        RagProperties properties = new RagProperties();
        PgVectorClient client = new PgVectorClient(properties, new ObjectMapper());

        assertEquals("[0.1,-0.2,3.0]", client.vectorLiteral(List.of(0.1, -0.2, 3.0)));
    }
}
