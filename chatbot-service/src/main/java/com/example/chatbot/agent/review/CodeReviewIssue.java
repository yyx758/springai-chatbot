package com.example.chatbot.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewIssue {
    private String id;
    private CodeReviewIssueSeverity severity;
    private CodeReviewIssueCategory category;
    private String title;
    private String description;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private String evidence;
    private String impact;
    private String recommendation;
    private Boolean patchable;
    private String suggestedPatch;
}
