package com.example.chatbot.agent.review;

import lombok.Data;

import java.util.List;

@Data
public class CodeReviewGitDiffRequest {
    private String sessionId;
    private String focus;
    private List<String> relativePaths;
    private Integer maxFiles;
    private Integer maxIssuesPerFile;
}
