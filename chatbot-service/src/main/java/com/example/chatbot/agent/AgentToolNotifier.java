package com.example.chatbot.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class AgentToolNotifier {

    private final ThreadLocal<SseEmitter> currentEmitter = new ThreadLocal<>();

    public void bind(SseEmitter emitter) {
        currentEmitter.set(emitter);
    }

    public void clear() {
        currentEmitter.remove();
    }

    public void toolStarted(String toolName) {
        emit("tool_call_started", Map.of("toolName", toolName));
    }

    public void toolCompleted(String toolName) {
        emit("tool_call_result", Map.of("toolName", toolName, "status", "completed"));
    }

    public void toolCompleted(String toolName, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("status", "completed");
        if (result != null) {
            payload.putAll(result);
        }
        emit("tool_call_result", payload);
    }

    public void toolFailed(String toolName, Exception error) {
        emit("tool_call_error", Map.of("toolName", toolName, "error", error.getMessage()));
    }

    public void knowledgeDocumentCreated(Map<String, Object> document) {
        emit("knowledge_document_created", document);
    }

    private void emit(String eventName, Map<String, Object> payload) {
        SseEmitter emitter = currentEmitter.get();
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            log.warn("Agent tool event send failed: {}", e.getMessage());
        }
    }
}
