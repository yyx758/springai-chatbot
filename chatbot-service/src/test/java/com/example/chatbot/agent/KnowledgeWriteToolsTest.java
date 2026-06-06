package com.example.chatbot.agent;

import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.dto.KnowledgeDocumentCreateRequest;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeWriteToolsTest {

    @Mock
    private RagService ragService;

    @Mock
    private AgentPendingActionService pendingActionService;

    @Mock
    private AgentToolAuditService auditService;

    @Mock
    private AgentToolNotifier toolNotifier;

    private final AgentToolContextResolver contextResolver = new AgentToolContextResolver();

    @Test
    @DisplayName("Create knowledge document writes immediately and audits low-risk write")
    void createKnowledgeDocumentWritesImmediately() {
        KnowledgeWriteTools tool = new KnowledgeWriteTools(
                ragService, pendingActionService, contextResolver, auditService, toolNotifier);
        ToolContext context = context();
        KnowledgeDocument saved = KnowledgeDocument.builder()
                .id(3L)
                .userId(7L)
                .title("Policy")
                .enabled(true)
                .build();
        when(auditService.start(any(), eq("createKnowledgeDocument"), eq(AgentToolLevel.LOW_RISK_WRITE), any()))
                .thenReturn(21L);
        when(ragService.createDocument(eq(7L), any(KnowledgeDocumentCreateRequest.class))).thenReturn(saved);

        Map<String, Object> result = tool.createKnowledgeDocument("Policy", "Content", "tag", true, context);

        assertEquals(true, result.get("success"));
        assertEquals(3L, result.get("documentId"));
        ArgumentCaptor<KnowledgeDocumentCreateRequest> captor = ArgumentCaptor.forClass(KnowledgeDocumentCreateRequest.class);
        verify(ragService).createDocument(eq(7L), captor.capture());
        assertEquals("Policy", captor.getValue().getTitle());
        assertEquals("Content", captor.getValue().getContent());
        verify(auditService).success(eq(21L), any());
    }

    @Test
    @DisplayName("Request delete knowledge document only creates pending action")
    void requestDeleteOnlyCreatesPendingAction() {
        KnowledgeWriteTools tool = new KnowledgeWriteTools(
                ragService, pendingActionService, contextResolver, auditService, toolNotifier);
        ToolContext context = context();
        AgentPendingAction action = AgentPendingAction.builder()
                .id(44L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_DELETE_KNOWLEDGE_DOCUMENT)
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(10))
                .build();
        when(auditService.start(any(), eq("requestDeleteKnowledgeDocument"), eq(AgentToolLevel.REQUIRE_CONFIRMATION), any()))
                .thenReturn(22L);
        when(pendingActionService.requestDeleteKnowledgeDocument(7L, "7_session", 9L, "cleanup"))
                .thenReturn(action);

        Map<String, Object> result = tool.requestDeleteKnowledgeDocument(9L, "cleanup", context);

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("requiresConfirmation"));
        assertEquals(44L, result.get("actionId"));
        assertEquals("/api/chat/agent/actions/44/confirm", result.get("confirmPath"));
        verify(pendingActionService).requestDeleteKnowledgeDocument(7L, "7_session", 9L, "cleanup");
        verify(ragService, never()).deleteDocument(anyLong(), anyLong());
        verify(auditService).success(eq(22L), any());
    }

    private ToolContext context() {
        return new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, "7",
                AgentToolContextKeys.SESSION_ID, "7_session"
        ));
    }
}
