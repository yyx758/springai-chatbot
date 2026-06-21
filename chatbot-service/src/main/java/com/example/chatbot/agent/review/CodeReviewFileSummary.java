package com.example.chatbot.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewFileSummary {
    private String relativePath;
    private String fileName;
    private String riskLevel;
    private int issueCount;
    private boolean truncated;
    private String summary;
}
