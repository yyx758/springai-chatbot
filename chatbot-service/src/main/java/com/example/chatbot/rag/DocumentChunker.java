package com.example.chatbot.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocumentChunker {

    private final RagProperties ragProperties;

    public List<DocumentChunk> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalized = content.trim().replace("\r\n", "\n");
        int chunkSize = Math.max(200, ragProperties.getChunk().getSize());
        int overlap = Math.max(0, Math.min(ragProperties.getChunk().getOverlap(), chunkSize / 2));
        int maxChunks = Math.max(1, ragProperties.getChunk().getMaxChunksPerDocument());

        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < normalized.length() && chunks.size() < maxChunks) {
            int end = Math.min(normalized.length(), start + chunkSize);
            end = adjustEndToBoundary(normalized, start, end);
            String text = normalized.substring(start, end).trim();
            if (!text.isBlank()) {
                chunks.add(new DocumentChunk(index++, text));
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private int adjustEndToBoundary(String text, int start, int proposedEnd) {
        if (proposedEnd >= text.length()) {
            return text.length();
        }
        int minEnd = Math.min(text.length(), start + Math.max(100, (proposedEnd - start) / 2));
        for (int i = proposedEnd; i > minEnd; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return proposedEnd;
    }
}
