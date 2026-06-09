package com.example.chatbot.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class AgentToolNotifier {

    public void toolStarted(ToolContext toolContext, String toolName) {
        emit(toolContext, "tool_call_started", Map.of("toolName", toolName));
    }

    public void toolCompleted(ToolContext toolContext, String toolName) {
        emit(toolContext, "tool_call_result", Map.of("toolName", toolName, "status", "completed"));
    }

    public void toolCompleted(ToolContext toolContext, String toolName, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolName", toolName);
        payload.put("status", "completed");
        if (result != null) {
            payload.putAll(result);
        }
        emit(toolContext, "tool_call_result", payload);
    }

    public void toolFailed(ToolContext toolContext, String toolName, Exception error) {
        emit(toolContext, "tool_call_error", Map.of("toolName", toolName, "error", error.getMessage()));
    }

    public void knowledgeDocumentCreated(ToolContext toolContext, Map<String, Object> document) {
        emit(toolContext, "knowledge_document_created", document);
    }

    public void workspaceFileCreated(ToolContext toolContext, Map<String, Object> file) {
        emit(toolContext, "workspace_file_created", file);
    }

    public void workspaceFileUpdated(ToolContext toolContext, Map<String, Object> file) {
        emit(toolContext, "workspace_file_updated", file);
    }

    public void workspaceFileSavedToKnowledge(ToolContext toolContext, Map<String, Object> payload) {
        emit(toolContext, "workspace_file_saved_to_knowledge", payload);
    }

    public void webSearchStarted(ToolContext toolContext, Map<String, Object> payload) {
        emit(toolContext, "web_search_started", payload);
    }

    public void webSearchCompleted(ToolContext toolContext, Map<String, Object> payload) {
        emit(toolContext, "web_search_completed", payload);
    }

    public void webFetchCompleted(ToolContext toolContext, Map<String, Object> payload) {
        emit(toolContext, "web_fetch_completed", payload);
    }

    private void emit(ToolContext toolContext, String eventName, Map<String, Object> payload) {
        SseEmitter emitter = getEmitter(toolContext);
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
        } catch (Exception e) {
            log.warn("Agent tool event send failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private SseEmitter getEmitter(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object emitter = toolContext.getContext().get(AgentToolContextKeys.EMITTER);
        return emitter instanceof SseEmitter ? (SseEmitter) emitter : null;
    }
}
