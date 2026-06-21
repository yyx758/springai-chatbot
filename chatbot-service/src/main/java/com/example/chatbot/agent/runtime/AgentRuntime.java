package com.example.chatbot.agent.runtime;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentRuntime {

    private final AgentRuntimeProperties properties;

    public AgentRuntime(AgentRuntimeProperties properties) {
        this.properties = properties;
    }

    public AgentRun start(String domain, String objective) {
        return AgentRun.builder()
                .runId("run_" + UUID.randomUUID().toString().replace("-", ""))
                .domain(domain)
                .objective(objective)
                .status(AgentRunStatus.RUNNING)
                .maxIterations(Math.max(1, properties.getMaxIterations()))
                .currentIteration(0)
                .startedTime(LocalDateTime.now())
                .build();
    }

    public AgentStep step(AgentRun run, AgentStepType type, String summary) {
        return step(run, type, summary, Map.of());
    }

    public AgentStep step(AgentRun run, AgentStepType type, String summary, Map<String, Object> metadata) {
        ensureCanAddStep(run);
        AgentStep step = AgentStep.builder()
                .stepId("step_" + UUID.randomUUID().toString().replace("-", ""))
                .runId(run.getRunId())
                .index(run.getSteps().size() + 1)
                .type(type)
                .status(AgentStepStatus.SUCCESS)
                .summary(summary)
                .metadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata))
                .startedTime(LocalDateTime.now())
                .finishedTime(LocalDateTime.now())
                .build();
        run.getSteps().add(step);
        run.setCurrentIteration(run.getSteps().size());
        return step;
    }

    public void complete(AgentRun run) {
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedTime(LocalDateTime.now());
    }

    public void fail(AgentRun run, Exception error) {
        run.setStatus(AgentRunStatus.FAILED);
        run.setErrorMessage(error == null ? "" : error.getMessage());
        run.setFinishedTime(LocalDateTime.now());
    }

    private void ensureCanAddStep(AgentRun run) {
        if (run == null) {
            throw new IllegalArgumentException("agent run is required");
        }
        if (run.getSteps().size() >= run.getMaxIterations()) {
            throw new IllegalStateException("agent run exceeded max iterations: " + run.getMaxIterations());
        }
    }
}
