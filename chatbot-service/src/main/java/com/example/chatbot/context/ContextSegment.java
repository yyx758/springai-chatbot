package com.example.chatbot.context;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.LocalDateTime;

@Data
@Builder
public class ContextSegment {

    private String id;
    private ContextSegmentType type;
    private String role;
    private String content;
    private int priority;
    private boolean required;
    private boolean compactable;
    private boolean toolResult;
    private String toolName;
    private String sourceRef;
    private int estimatedTokens;
    private LocalDateTime createdAt;

    public Message toMessage() {
        String safeContent = content == null ? "" : content;
        if ("assistant".equalsIgnoreCase(role)) {
            return new AssistantMessage(safeContent);
        }
        if ("user".equalsIgnoreCase(role)) {
            return new UserMessage(safeContent);
        }
        return new SystemMessage(safeContent);
    }
}
