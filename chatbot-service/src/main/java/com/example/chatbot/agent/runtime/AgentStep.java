package com.example.chatbot.agent.runtime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStep {
    private String stepId;
    private String runId;
    private int index;
    private AgentStepType type;
    private AgentStepStatus status;
    private String summary;
    @Builder.Default
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
}
