package com.example.chatbot.agent.review;

import com.example.chatbot.agent.runtime.AgentStep;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewWorkspaceResult {
    private boolean success;
    private String runId;
    private String sessionId;
    private Long workspaceId;
    private String scopeType;
    private int reviewedFileCount;
    private String summary;
    private String riskLevel;
    @Builder.Default
    private List<CodeReviewFileSummary> files = new ArrayList<>();
    @Builder.Default
    private List<CodeReviewIssue> issues = new ArrayList<>();
    @Builder.Default
    private List<String> testsToAdd = new ArrayList<>();
    @Builder.Default
    private List<String> patchPlan = new ArrayList<>();
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
    @Builder.Default
    private List<AgentStep> steps = new ArrayList<>();
    private LocalDateTime createdTime;
}
