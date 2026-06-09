package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.dto.KnowledgeDocumentCreateRequest;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KnowledgeWriteTools {

    private final RagService ragService;
    private final AgentPendingActionService pendingActionService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "Create a knowledge base document for the current user. This is a low-risk write operation and records an audit log.")
    public Map<String, Object> createKnowledgeDocument(
            @ToolParam(description = "Document title") String title,
            @ToolParam(description = "Document content") String content,
            @ToolParam(description = "Optional tags", required = false) String tags,
            @ToolParam(description = "Whether the document should be enabled", required = false) Boolean enabled,
            ToolContext toolContext
    ) {
        String toolName = "createKnowledgeDocument";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("title", safe(title), "contentLength", content == null ? 0 : content.length(), "tags", safe(tags)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            KnowledgeDocumentCreateRequest request = new KnowledgeDocumentCreateRequest();
            request.setTitle(title);
            request.setContent(content);
            request.setTags(tags);
            request.setEnabled(enabled == null ? true : enabled);
            KnowledgeDocument document = ragService.createDocument(userId, request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("documentId", document.getId());
            result.put("title", document.getTitle());
            result.put("enabled", document.getEnabled());
            result.put("fileKey", document.getFileKey());
            result.put("openPath", "/api/knowledge/documents/" + document.getId());
            if (document.getFileKey() != null && !document.getFileKey().isBlank()) {
                result.put("downloadUrl", "/api/files/download/" + document.getFileKey());
            }
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.knowledgeDocumentCreated(toolContext, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Request deletion of a knowledge base document. This does not delete immediately; it creates a pending action that the user must confirm.")
    public Map<String, Object> requestDeleteKnowledgeDocument(
            @ToolParam(description = "Knowledge document id to delete") Long documentId,
            @ToolParam(description = "Reason for deletion", required = false) String reason,
            ToolContext toolContext
    ) {
        String toolName = "requestDeleteKnowledgeDocument";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.REQUIRE_CONFIRMATION,
                Map.of("documentId", documentId == null ? "" : documentId, "reason", safe(reason)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            contextResolver.ensureSessionOwnedByUser(sessionId, userId);
            AgentPendingAction action = pendingActionService.requestDeleteKnowledgeDocument(userId, sessionId, documentId, reason);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("requiresConfirmation", true);
            result.put("actionId", action.getId());
            result.put("actionType", action.getActionType());
            result.put("expireTime", action.getExpireTime());
            result.put("confirmPath", "/api/chat/agent/actions/" + action.getId() + "/confirm");
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
