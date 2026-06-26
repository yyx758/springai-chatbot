package com.example.chatbot.memory;

import com.example.chatbot.entity.AgentLongTermMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPromptBuilderTest {

    private final MemoryPromptBuilder builder = new MemoryPromptBuilder();

    @Test
    @DisplayName("Index prompt includes metadata but not detail content")
    void indexPromptDoesNotLeakDetailContent() {
        String prompt = builder.buildIndexPrompt(List.of(MemoryIndexItem.builder()
                .id(18L)
                .scopeType("PROJECT")
                .memoryType("project")
                .name("Gateway production entry")
                .description("Production traffic uses gateway.")
                .loadHint("Load for deployment changes.")
                .build()));

        assertTrue(prompt.contains("id=18"));
        assertTrue(prompt.contains("Gateway production entry"));
        assertFalse(prompt.contains("Do not expose chatbot-service:8080"));
    }

    @Test
    @DisplayName("Detail prompt includes memory id and content")
    void detailPromptIncludesIdAndContent() {
        String prompt = builder.buildDetailPrompt(List.of(AgentLongTermMemory.builder()
                .id(18L)
                .scopeType("PROJECT")
                .memoryType("project")
                .content("Do not expose chatbot-service:8080.")
                .build()));

        assertTrue(prompt.contains("memory id=18"));
        assertTrue(prompt.contains("Do not expose chatbot-service:8080."));
    }
}
