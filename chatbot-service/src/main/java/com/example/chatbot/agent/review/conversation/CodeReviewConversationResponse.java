package com.example.chatbot.agent.review.conversation;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class CodeReviewConversationResponse {
    private boolean handled;
    private CodeReviewIntentType type;
    private String message;
    private String runId;
    private int issueCount;
    @Builder.Default
    private Map<String, Integer> severityCounts = new HashMap<>();
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();
}
