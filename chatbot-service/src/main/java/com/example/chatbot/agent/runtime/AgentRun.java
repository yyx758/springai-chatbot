package com.example.chatbot.agent.runtime;

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
public class AgentRun {
    private String runId;
    private String objective;
    private String domain;
    private AgentRunStatus status;
    private int maxIterations;
    private int currentIteration;
    @Builder.Default
    private List<AgentStep> steps = new ArrayList<>();
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
}
