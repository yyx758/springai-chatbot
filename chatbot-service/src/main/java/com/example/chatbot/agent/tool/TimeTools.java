package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TimeTools {

    private final AgentToolNotifier toolNotifier;
    private final AgentToolAuditService auditService;

    @Tool(description = "Get the current server time in ISO-8601 format.")
    public Map<String, Object> getCurrentTime(ToolContext toolContext) {
        String toolName = "getCurrentTime";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, Map.of());
        try {
            ZonedDateTime now = ZonedDateTime.now();
            Map<String, Object> result = Map.of(
                    "isoTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    "zone", now.getZone().toString()
            );
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }
}
