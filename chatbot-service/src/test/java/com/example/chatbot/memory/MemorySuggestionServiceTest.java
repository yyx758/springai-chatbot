package com.example.chatbot.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySuggestionServiceTest {

    private final MemorySuggestionService service = new MemorySuggestionService();

    @Test
    @DisplayName("Stable user preference produces memory suggestion")
    void stablePreferenceProducesSuggestion() {
        var suggestion = service.suggestFromUserMessage("以后回答要直接给实现、验证和总结。");

        assertTrue(suggestion.isPresent());
        assertEquals("feedback", suggestion.get().getMemoryType());
        assertEquals("USER", suggestion.get().getScopeType());
    }

    @Test
    @DisplayName("Agent instruction tells model to suggest but not auto-save memory")
    void agentInstructionIncludesMemorySuggestionBoundary() {
        String instruction = service.buildAgentInstruction();

        assertTrue(instruction.contains("suggest"));
        assertTrue(instruction.contains("Do not write memory by yourself"));
        assertTrue(instruction.contains("长期记忆"));
    }
}
