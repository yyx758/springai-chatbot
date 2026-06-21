package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.AgentPendingActionMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.RagService;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileUpdateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentPendingActionService {

    public static final String ACTION_DELETE_KNOWLEDGE_DOCUMENT = "DELETE_KNOWLEDGE_DOCUMENT";
    public static final String ACTION_APPLY_WORKSPACE_PATCH = "APPLY_WORKSPACE_PATCH";

    private final AgentPendingActionMapper pendingActionMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RagService ragService;
    private final AgentWorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    @Value("${app.agent.pending-action-expire-minutes:10}")
    private int pendingActionExpireMinutes;

    public AgentPendingAction requestDeleteKnowledgeDocument(Long userId, String sessionId, Long documentId, String reason) {
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null || !userId.equals(document.getUserId())) {
            throw new IllegalArgumentException("knowledge document not found or access denied");
        }

        Map<String, Object> arguments = Map.of(
                "documentId", documentId,
                "title", document.getTitle(),
                "reason", reason == null ? "" : reason
        );
        AgentPendingAction action = AgentPendingAction.builder()
                .userId(userId)
                .sessionId(sessionId)
                .actionType(ACTION_DELETE_KNOWLEDGE_DOCUMENT)
                .toolName("requestDeleteKnowledgeDocument")
                .argumentsJson(toJson(arguments))
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(pendingActionExpireMinutes))
                .createdTime(LocalDateTime.now())
                .build();
        pendingActionMapper.insert(action);
        return action;
    }

    public AgentPendingAction requestApplyWorkspacePatch(Long userId, String sessionId, String relativePath,
                                                        String replacementContent, Integer expectedVersion,
                                                        String reason) {
        if (replacementContent == null) {
            throw new IllegalArgumentException("replacementContent is required");
        }
        var workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
        workspaceService.requireWorkspaceFile(userId, workspace.getId(), relativePath);

        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("workspaceId", workspace.getId());
        arguments.put("relativePath", relativePath);
        arguments.put("replacementContent", replacementContent);
        arguments.put("expectedVersion", expectedVersion);
        arguments.put("reason", reason == null ? "" : reason);

        AgentPendingAction action = AgentPendingAction.builder()
                .userId(userId)
                .sessionId(sessionId)
                .actionType(ACTION_APPLY_WORKSPACE_PATCH)
                .toolName("requestApplyWorkspacePatch")
                .argumentsJson(toJson(arguments))
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(pendingActionExpireMinutes))
                .createdTime(LocalDateTime.now())
                .build();
        pendingActionMapper.insert(action);
        return action;
    }

    public List<AgentPendingAction> listActions(Long userId, String sessionId, String actionType, String status, int limit) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        if (sessionId != null && !sessionId.isBlank() && !sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
        LambdaQueryWrapper<AgentPendingAction> wrapper = new LambdaQueryWrapper<AgentPendingAction>()
                .eq(AgentPendingAction::getUserId, userId)
                .orderByDesc(AgentPendingAction::getCreatedTime)
                .last("LIMIT " + normalizeLimit(limit));
        if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(AgentPendingAction::getSessionId, sessionId.trim());
        }
        if (actionType != null && !actionType.isBlank()) {
            wrapper.eq(AgentPendingAction::getActionType, actionType.trim());
        }
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            wrapper.eq(AgentPendingAction::getStatus, status.trim().toUpperCase());
        }
        return pendingActionMapper.selectList(wrapper);
    }

    public Map<String, Object> toActionCard(AgentPendingAction action) {
        Map<String, Object> arguments = parseArgumentsOrEmpty(action.getArgumentsJson());
        Map<String, Object> safeArguments = new LinkedHashMap<>();
        copyIfPresent(arguments, safeArguments, "workspaceId");
        copyIfPresent(arguments, safeArguments, "relativePath");
        copyIfPresent(arguments, safeArguments, "expectedVersion");
        copyIfPresent(arguments, safeArguments, "reason");
        copyIfPresent(arguments, safeArguments, "documentId");
        copyIfPresent(arguments, safeArguments, "title");
        return Map.ofEntries(
                Map.entry("id", action.getId() == null ? 0L : action.getId()),
                Map.entry("sessionId", action.getSessionId() == null ? "" : action.getSessionId()),
                Map.entry("actionType", action.getActionType() == null ? "" : action.getActionType()),
                Map.entry("toolName", action.getToolName() == null ? "" : action.getToolName()),
                Map.entry("status", action.getStatus() == null ? "" : action.getStatus()),
                Map.entry("arguments", safeArguments),
                Map.entry("expireTime", action.getExpireTime() == null ? "" : action.getExpireTime()),
                Map.entry("createdTime", action.getCreatedTime() == null ? "" : action.getCreatedTime()),
                Map.entry("confirmedTime", action.getConfirmedTime() == null ? "" : action.getConfirmedTime()),
                Map.entry("resultSummary", action.getResultSummary() == null ? "" : action.getResultSummary()),
                Map.entry("errorMessage", action.getErrorMessage() == null ? "" : action.getErrorMessage())
        );
    }

    public AgentPendingAction confirm(Long userId, Long actionId) {
        AgentPendingAction action = pendingActionMapper.selectOne(new LambdaQueryWrapper<AgentPendingAction>()
                .eq(AgentPendingAction::getId, actionId)
                .eq(AgentPendingAction::getUserId, userId));
        if (action == null) {
            throw new IllegalArgumentException("pending action not found");
        }
        if (!"PENDING".equals(action.getStatus())) {
            throw new IllegalStateException("pending action is not executable");
        }
        if (action.getExpireTime() != null && action.getExpireTime().isBefore(LocalDateTime.now())) {
            markFailed(action, "pending action expired");
            throw new IllegalStateException("pending action expired");
        }

        try {
            if (ACTION_DELETE_KNOWLEDGE_DOCUMENT.equals(action.getActionType())) {
                Map<String, Object> arguments = parseArguments(action.getArgumentsJson());
                Long documentId = Long.valueOf(String.valueOf(arguments.get("documentId")));
                ragService.deleteDocument(userId, documentId);
                action.setStatus("CONFIRMED");
                action.setConfirmedTime(LocalDateTime.now());
                action.setResultSummary("knowledge document deleted: " + documentId);
                pendingActionMapper.updateById(action);
                return action;
            }
            if (ACTION_APPLY_WORKSPACE_PATCH.equals(action.getActionType())) {
                Map<String, Object> arguments = parseArguments(action.getArgumentsJson());
                String sessionId = action.getSessionId();
                String relativePath = String.valueOf(arguments.get("relativePath"));
                String replacementContent = String.valueOf(arguments.get("replacementContent"));
                Integer expectedVersion = parseOptionalInteger(arguments.get("expectedVersion"));
                var workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
                WorkspaceFileUpdateRequest request = new WorkspaceFileUpdateRequest();
                request.setRelativePath(relativePath);
                request.setContent(replacementContent);
                request.setExpectedVersion(expectedVersion);
                workspaceService.updateFile(userId, workspace.getId(), request);
                action.setStatus("CONFIRMED");
                action.setConfirmedTime(LocalDateTime.now());
                action.setResultSummary("workspace file updated: " + relativePath);
                pendingActionMapper.updateById(action);
                return action;
            }
            throw new IllegalArgumentException("unsupported pending action type: " + action.getActionType());
        } catch (Exception e) {
            markFailed(action, e.getMessage());
            throw e;
        }
    }

    public AgentPendingAction cancel(Long userId, Long actionId) {
        AgentPendingAction action = pendingActionMapper.selectOne(new LambdaQueryWrapper<AgentPendingAction>()
                .eq(AgentPendingAction::getId, actionId)
                .eq(AgentPendingAction::getUserId, userId));
        if (action == null) {
            throw new IllegalArgumentException("pending action not found");
        }
        if (!"PENDING".equals(action.getStatus())) {
            throw new IllegalStateException("pending action is not cancellable");
        }
        action.setStatus("CANCELLED");
        action.setConfirmedTime(LocalDateTime.now());
        action.setResultSummary("pending action cancelled");
        pendingActionMapper.updateById(action);
        return action;
    }

    private void markFailed(AgentPendingAction action, String errorMessage) {
        action.setStatus("FAILED");
        action.setConfirmedTime(LocalDateTime.now());
        action.setErrorMessage(errorMessage);
        pendingActionMapper.updateById(action);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> parseArguments(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid pending action arguments", e);
        }
    }

    private Map<String, Object> parseArgumentsOrEmpty(String json) {
        try {
            return parseArguments(json);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private Integer parseOptionalInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }
}
