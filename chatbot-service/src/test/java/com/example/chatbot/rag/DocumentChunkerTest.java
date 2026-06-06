package com.example.chatbot.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentChunkerTest {

    @Test
    @DisplayName("Chunker splits long text with configured max chunks")
    void chunkLongText() {
        RagProperties properties = new RagProperties();
        properties.getChunk().setSize(20);
        properties.getChunk().setOverlap(5);
        properties.getChunk().setMaxChunksPerDocument(3);
        DocumentChunker chunker = new DocumentChunker(properties);

        List<DocumentChunk> chunks = chunker.chunk("第一段内容很长很长。第二段内容也很长很长。第三段内容继续很长很长。第四段内容。");

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() <= 3);
        assertEquals(0, chunks.get(0).index());
        assertFalse(chunks.get(0).content().isBlank());
    }

    @Test
    @DisplayName("Chunker returns empty list for blank content")
    void blankContent() {
        RagProperties properties = new RagProperties();
        DocumentChunker chunker = new DocumentChunker(properties);

        assertTrue(chunker.chunk("   ").isEmpty());
    }
}
