package com.example.chatbot.agent.review;

import lombok.Data;

import java.util.List;

@Data
public class CodeReviewWorkspaceRequest {
    private String sessionId;
    private List<String> relativePaths;
    private String query;
    private String focus;
    private Integer maxFiles;
    private Integer maxIssuesPerFile;
}
