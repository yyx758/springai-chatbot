package com.example.chatbot.agent.review;

import lombok.Data;

@Data
public class CodeReviewPatchSuggestionRequest {
    private String sessionId;
    private String relativePath;
    private Long issueId;
    private String focus;
    private Integer maxIssues;
    private String suggestionPath;
}
