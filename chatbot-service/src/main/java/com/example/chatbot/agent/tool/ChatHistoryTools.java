package com.example.chatbot.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChatHistoryTools {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 30;

    private final ChatRecordMapper chatRecordMapper;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolNotifier toolNotifier;
    private final AgentToolAuditService auditService;

    @Tool(description = "Read recent messages from the current conversation. Use this when the user asks about previous conversation context.")
    public List<Map<String, Object>> getCurrentChatHistory(
            @ToolParam(description = "Maximum number of chat records to return", required = false) Integer limit,
            ToolContext toolContext
    ) {
        String toolName = "getCurrentChatHistory";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("limit", limit == null ? "" : limit));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            contextResolver.ensureSessionOwnedByUser(sessionId, userId);
            int finalLimit = normalizeLimit(limit);

            List<ChatRecord> records = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getSessionId, sessionId)
                    .orderByDesc(ChatRecord::getCreatedTime)
                    .last("LIMIT " + finalLimit));

            List<Map<String, Object>> result = records.stream()
                    .sorted(Comparator.comparing(ChatRecord::getCreatedTime))
                    .map(this::toSafeMap)
                    .toList();
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Read recent messages from a session owned by the current user. Refuse sessions that do not belong to the current user.")
    public List<Map<String, Object>> getChatHistoryBySession(
            @ToolParam(description = "Conversation session id") String sessionId,
            @ToolParam(description = "Maximum number of chat records to return", required = false) Integer limit,
            ToolContext toolContext
    ) {
        String toolName = "getChatHistoryBySession";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("sessionId", sessionId == null ? "" : sessionId, "limit", limit == null ? "" : limit));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            contextResolver.ensureSessionOwnedByUser(sessionId, userId);
            int finalLimit = normalizeLimit(limit);

            List<ChatRecord> records = chatRecordMapper.selectList(new LambdaQueryWrapper<ChatRecord>()
                    .eq(ChatRecord::getSessionId, sessionId)
                    .orderByDesc(ChatRecord::getCreatedTime)
                    .last("LIMIT " + finalLimit));

            List<Map<String, Object>> result = records.stream()
                    .sorted(Comparator.comparing(ChatRecord::getCreatedTime))
                    .map(this::toSafeMap)
                    .toList();
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private Map<String, Object> toSafeMap(ChatRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("userMessage", record.getUserMessage());
        result.put("botResponse", record.getBotResponse());
        result.put("createdTime", record.getCreatedTime());
        result.put("sessionId", record.getSessionId());
        return result;
    }
}
