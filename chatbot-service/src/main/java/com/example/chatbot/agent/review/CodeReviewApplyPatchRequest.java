package com.example.chatbot.agent.review;

import lombok.Data;

@Data
public class CodeReviewApplyPatchRequest {
    private String sessionId;
    private String relativePath;
    private String replacementContent;
    private Integer expectedVersion;
    private String reason;
}
