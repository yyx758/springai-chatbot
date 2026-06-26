package com.example.chatbot.agent.review.conversation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class CodeReviewConversationContext {
    private String sessionId;
    private Long userId;
    private String activeReviewRunId;
    private Long activeIssueId;
    private String activePatchPreviewRef;
    private LocalDateTime updatedAt;
}
