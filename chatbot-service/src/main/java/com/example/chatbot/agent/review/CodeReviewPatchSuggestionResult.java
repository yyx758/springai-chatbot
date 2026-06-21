package com.example.chatbot.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewPatchSuggestionResult {
    private boolean success;
    private String runId;
    private String sessionId;
    private Long workspaceId;
    private String targetPath;
    private Long issueId;
    private String suggestionPath;
    private Map<String, Object> suggestionFile;
    private CodeReviewResult review;
    private String message;
    private LocalDateTime createdTime;
}
