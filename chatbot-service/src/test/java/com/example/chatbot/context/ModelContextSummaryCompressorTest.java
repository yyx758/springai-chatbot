package com.example.chatbot.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelContextSummaryCompressorTest {

    @Test
    @DisplayName("Model summary compressor uses configured LLM when available")
    void usesConfiguredModelWhenAvailable() {
        OpenAiChatModel openAiChatModel = mock(OpenAiChatModel.class);
        when(openAiChatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("LLM semantic summary"))))
        );

        ModelContextSummaryCompressor compressor = new ModelContextSummaryCompressor(
                provider(openAiChatModel),
                provider(null),
                new DeterministicContextSummaryCompressor()
        );

        String summary = compressor.summarize(List.of(segment()), ContextCompressionMode.AUTO);

        assertTrue(summary.contains("LLM semantic summary"));
        verify(openAiChatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("Model summary compressor falls back when LLM call fails")
    void fallsBackWhenModelCallFails() {
        OpenAiChatModel openAiChatModel = mock(OpenAiChatModel.class);
        when(openAiChatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));

        ModelContextSummaryCompressor compressor = new ModelContextSummaryCompressor(
                provider(openAiChatModel),
                provider(null),
                new DeterministicContextSummaryCompressor()
        );

        String summary = compressor.summarize(List.of(segment()), ContextCompressionMode.AUTO);

        assertTrue(summary.contains("[Auto compacted context]"));
        assertTrue(summary.contains("Summary only"));
    }

    private ContextSegment segment() {
        return ContextSegment.builder()
                .type(ContextSegmentType.TOOL_RESULT)
                .role("user")
                .toolName("readWorkspaceFile")
                .sourceRef("src/App.java")
                .estimatedTokens(100)
                .content("large source content")
                .build();
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
