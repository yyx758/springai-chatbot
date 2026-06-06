package com.example.chatbot.agent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AgentToolContextResolver {

    public Long requireUserId(ToolContext context) {
        Object value = requireContextValue(context, AgentToolContextKeys.USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }

    public String requireSessionId(ToolContext context) {
        return String.valueOf(requireContextValue(context, AgentToolContextKeys.SESSION_ID));
    }

    public void ensureSessionOwnedByUser(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (!sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
    }

    private Object requireContextValue(ToolContext context, String key) {
        if (context == null) {
            throw new IllegalArgumentException("tool context is required");
        }
        Map<String, Object> values = context.getContext();
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }
}
