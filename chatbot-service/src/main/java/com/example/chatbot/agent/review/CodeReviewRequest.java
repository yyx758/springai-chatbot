package com.example.chatbot.agent.review;

import lombok.Data;

@Data
public class CodeReviewRequest {
    private String sessionId;
    private String relativePath;
    private String focus;
    private Integer maxIssues;
}
