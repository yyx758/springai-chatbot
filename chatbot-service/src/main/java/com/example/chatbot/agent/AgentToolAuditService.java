package com.example.chatbot.agent;

import com.example.chatbot.entity.AgentToolExecutionLog;
import com.example.chatbot.mapper.AgentToolExecutionLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentToolAuditService {

    private static final int MAX_SUMMARY_LENGTH = 2000;

    private final AgentToolExecutionLogMapper executionLogMapper;
    private final AgentToolContextResolver contextResolver;
    private final ObjectMapper objectMapper;

    public Long start(ToolContext context, String toolName, String toolLevel, Object arguments) {
        try {
            Long userId = contextResolver.requireUserId(context);
            String sessionId = contextResolver.requireSessionId(context);
            AgentToolExecutionLog logRecord = AgentToolExecutionLog.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .toolName(toolName)
                    .toolLevel(toolLevel)
                    .argumentsJson(toJson(arguments))
                    .status("RUNNING")
                    .startedTime(LocalDateTime.now())
                    .build();
            executionLogMapper.insert(logRecord);
            return logRecord.getId();
        } catch (Exception e) {
            log.warn("Agent tool audit start failed, toolName={}, error={}", toolName, e.getMessage());
            return null;
        }
    }

    public void success(Long logId, Object result) {
        if (logId == null) {
            return;
        }
        try {
            AgentToolExecutionLog update = AgentToolExecutionLog.builder()
                    .id(logId)
                    .status("SUCCESS")
                    .resultSummary(truncate(toJson(result)))
                    .finishedTime(LocalDateTime.now())
                    .build();
            executionLogMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Agent tool audit success update failed, logId={}, error={}", logId, e.getMessage());
        }
    }

    public void failure(Long logId, Exception error) {
        if (logId == null) {
            return;
        }
        try {
            AgentToolExecutionLog update = AgentToolExecutionLog.builder()
                    .id(logId)
                    .status("FAILED")
                    .errorMessage(truncate(error.getMessage()))
                    .finishedTime(LocalDateTime.now())
                    .build();
            executionLogMapper.updateById(update);
        } catch (Exception e) {
            log.warn("Agent tool audit failure update failed, logId={}, error={}", logId, e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_SUMMARY_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_LENGTH);
    }
}
