package com.example.chatbot.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewPatchPreviewResult {
    private boolean success;
    private String sessionId;
    private Long workspaceId;
    private String relativePath;
    private Long issueId;
    private Integer expectedVersion;
    private boolean applicable;
    private String currentContent;
    private String replacementContent;
    private String diffPreview;
    private List<String> warnings;
    private String message;
}
