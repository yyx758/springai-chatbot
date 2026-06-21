package com.example.chatbot.agent.review;

import lombok.Data;

@Data
public class CodeReviewPatchPreviewRequest {
    private String sessionId;
    private String relativePath;
    private Long issueId;
    private String suggestedPatch;
}
