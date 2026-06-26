package com.example.chatbot.memory;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MemorySuggestionService {

    public Optional<MemorySuggestion> suggestFromUserMessage(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }
        String text = userMessage.trim();
        if (looksLikeDurablePreference(text)) {
            return Optional.of(MemorySuggestion.builder()
                    .memoryType("feedback")
                    .scopeType("USER")
                    .name("User collaboration preference")
                    .description(limit(text, 180))
                    .content(text)
                    .loadHint("Load when deciding response style or task execution approach.")
                    .build());
        }
        if (looksLikeProjectConstraint(text)) {
            return Optional.of(MemorySuggestion.builder()
                    .memoryType("project")
                    .scopeType("PROJECT")
                    .name("Project constraint")
                    .description(limit(text, 180))
                    .content(text)
                    .loadHint("Load when tasks touch project architecture, deployment, security, or review rules.")
                    .build());
        }
        return Optional.empty();
    }

    public String buildAgentInstruction() {
        return """
                Long-term memory behavior:
                - If the user states a stable preference, repeated feedback, project constraint, or reusable reference, briefly suggest that it can be saved as long-term memory.
                - If the user explicitly asks you to remember or save it, call requestSaveLongTermMemory to create a pending confirmation action.
                - Do not claim memory was saved unless the user confirms the pending action and the memory API succeeds.
                - The suggestion should be lightweight, for example: "这条信息适合保存为长期记忆，之后我可以默认遵守。需要我写入 memory 吗？"
                - Never save secrets, passwords, tokens, .env content, or one-off temporary task details as memory.
                """;
    }

    private boolean looksLikeDurablePreference(String text) {
        return text.contains("以后")
                || text.contains("长期")
                || text.contains("偏好")
                || text.contains("我喜欢")
                || text.contains("不要再")
                || text.toLowerCase().contains("remember");
    }

    private boolean looksLikeProjectConstraint(String text) {
        return text.contains("必须")
                || text.contains("禁止")
                || text.contains("不要")
                || text.contains("只能")
                || text.contains("安全边界")
                || text.contains("生产环境");
    }

    private String limit(String value, int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    @Data
    @Builder
    public static class MemorySuggestion {
        private String scopeType;
        private String memoryType;
        private String name;
        private String description;
        private String content;
        private String loadHint;
    }
}
