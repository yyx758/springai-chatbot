package com.example.chatbot.context;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ContextCompactionResult {

    private List<ContextSegment> segments;
    private int originalChars;
    private int finalChars;
    private int originalEstimatedTokens;
    private int finalEstimatedTokens;
    private int removedSegments;
    private int removedChars;
    private int removedEstimatedTokens;
    private int snippedSegments;
    private int microCompactedToolResults;
    private int persistedLargeResults;
    private boolean autoCompacted;
    private boolean tokenBudgetExceeded;
    private boolean charBudgetExceeded;
    private boolean truncated;
}
