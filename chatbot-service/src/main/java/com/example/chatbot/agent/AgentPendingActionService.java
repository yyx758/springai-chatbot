package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.AgentPendingActionMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentPendingActionService {

    public static final String ACTION_DELETE_KNOWLEDGE_DOCUMENT = "DELETE_KNOWLEDGE_DOCUMENT";

    private final AgentPendingActionMapper pendingActionMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RagService ragService;
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
            throw new IllegalArgumentException("unsupported pending action type: " + action.getActionType());
        } catch (Exception e) {
            markFailed(action, e.getMessage());
            throw e;
        }
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
}
