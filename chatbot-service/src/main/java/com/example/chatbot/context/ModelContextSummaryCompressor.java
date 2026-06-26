package com.example.chatbot.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Primary
@Slf4j
public class ModelContextSummaryCompressor implements ContextSummaryCompressor {

    private static final int MAX_SUMMARY_SOURCE_CHARS = 28000;

    private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;
    private final ObjectProvider<OllamaChatModel> ollamaChatModelProvider;
    private final DeterministicContextSummaryCompressor fallbackCompressor;

    public ModelContextSummaryCompressor(
            ObjectProvider<OpenAiChatModel> openAiChatModelProvider,
            ObjectProvider<OllamaChatModel> ollamaChatModelProvider,
            DeterministicContextSummaryCompressor fallbackCompressor
    ) {
        this.openAiChatModelProvider = openAiChatModelProvider;
        this.ollamaChatModelProvider = ollamaChatModelProvider;
        this.fallbackCompressor = fallbackCompressor;
    }

    @Override
    public String summarize(List<ContextSegment> segments, ContextCompressionMode mode) {
        ChatModel model = resolveModel();
        if (model == null) {
            return fallbackCompressor.summarize(segments, mode);
        }

        String source = buildSource(segments);
        try {
            String result = model.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt(mode)),
                    new UserMessage(source)
            ))).getResult().getOutput().getText();
            if (result == null || result.isBlank()) {
                return fallbackCompressor.summarize(segments, mode);
            }
            return result.trim();
        } catch (Exception e) {
            log.warn("LLM context summary failed, falling back to deterministic summary. mode={}, error={}",
                    mode, e.getMessage());
            return fallbackCompressor.summarize(segments, mode);
        }
    }

    private ChatModel resolveModel() {
        try {
            OpenAiChatModel openAi = openAiChatModelProvider.getIfAvailable();
            if (openAi != null) {
                return openAi;
            }
        } catch (Exception e) {
            log.debug("OpenAI/DeepSeek summary model unavailable: {}", e.getMessage());
        }
        try {
            return ollamaChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.debug("Ollama summary model unavailable: {}", e.getMessage());
            return null;
        }
    }

    private String systemPrompt(ContextCompressionMode mode) {
        return """
                You are compressing conversation context for an engineering agent.
                CRITICAL: Respond with text only. Do not call tools.
                Do not invent facts.
                Preserve IDs, file paths, user constraints, safety boundaries, pending actions, unresolved work, and important decisions.
                Prefer concise Chinese unless source content clearly requires English identifiers.
                Keep exact identifiers and paths unchanged.
                Output sections:
                1. 当前目标
                2. 已确认约束
                3. 关键上下文
                4. 文件/工具/ID引用
                5. 未完成事项
                Compression mode: %s
                """.formatted(mode.name());
    }

    private String buildSource(List<ContextSegment> segments) {
        String source = (segments == null ? List.<ContextSegment>of() : segments).stream()
                .filter(Objects::nonNull)
                .map(this::formatSegment)
                .collect(Collectors.joining("\n\n"));
        if (source.length() <= MAX_SUMMARY_SOURCE_CHARS) {
            return source;
        }
        return source.substring(0, MAX_SUMMARY_SOURCE_CHARS) + "\n\n[summary source truncated]";
    }

    private String formatSegment(ContextSegment segment) {
        return """
                --- segment ---
                type: %s
                role: %s
                id: %s
                sourceRef: %s
                toolName: %s
                estimatedTokens: %d
                content:
                %s
                """.formatted(
                segment.getType() == null ? "UNKNOWN" : segment.getType().name(),
                safe(segment.getRole()),
                safe(segment.getId()),
                safe(segment.getSourceRef()),
                safe(segment.getToolName()),
                Math.max(0, segment.getEstimatedTokens()),
                safe(segment.getContent())
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
