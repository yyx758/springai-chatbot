package com.example.chatbot.agent.review.conversation;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class CodeReviewIntent {
    private CodeReviewIntentType type;
    private String sessionId;
    private String targetPath;
    @Builder.Default
    private List<String> targetPaths = new ArrayList<>();
    private String issueRef;
    private String runId;
    @Builder.Default
    private List<String> focusAreas = new ArrayList<>();
    private String statusFilter;
    private String userInstruction;
    private double confidence;

    public boolean isCodeReviewIntent() {
        return type != null && type != CodeReviewIntentType.GENERAL_CHAT && type != CodeReviewIntentType.UNKNOWN;
    }
}
