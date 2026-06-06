package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.AgentPendingActionMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentPendingActionServiceTest {

    @Mock
    private AgentPendingActionMapper pendingActionMapper;

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private RagService ragService;

    private AgentPendingActionService service;

    @BeforeEach
    void setUp() {
        service = new AgentPendingActionService(
                pendingActionMapper,
                knowledgeDocumentMapper,
                ragService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "pendingActionExpireMinutes", 10);
    }

    @Test
    @DisplayName("Delete knowledge document creates pending action instead of deleting")
    void requestDeleteCreatesPendingAction() {
        KnowledgeDocument document = KnowledgeDocument.builder()
                .id(9L)
                .userId(7L)
                .title("doc")
                .build();
        when(knowledgeDocumentMapper.selectById(9L)).thenReturn(document);

        AgentPendingAction action = service.requestDeleteKnowledgeDocument(7L, "7_session", 9L, "cleanup");

        assertEquals("PENDING", action.getStatus());
        assertEquals(AgentPendingActionService.ACTION_DELETE_KNOWLEDGE_DOCUMENT, action.getActionType());
        assertTrue(action.getArgumentsJson().contains("\"documentId\":9"));
        verify(ragService, never()).deleteDocument(anyLong(), anyLong());
        verify(pendingActionMapper).insert(any(AgentPendingAction.class));
    }

    @Test
    @DisplayName("Confirm executes pending delete for same user")
    void confirmExecutesPendingDelete() {
        AgentPendingAction action = AgentPendingAction.builder()
                .id(100L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_DELETE_KNOWLEDGE_DOCUMENT)
                .toolName("requestDeleteKnowledgeDocument")
                .argumentsJson("{\"documentId\":9,\"title\":\"doc\",\"reason\":\"cleanup\"}")
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .build();
        when(pendingActionMapper.selectOne(any(Wrapper.class))).thenReturn(action);

        AgentPendingAction confirmed = service.confirm(7L, 100L);

        assertEquals("CONFIRMED", confirmed.getStatus());
        verify(ragService).deleteDocument(7L, 9L);
        ArgumentCaptor<AgentPendingAction> captor = ArgumentCaptor.forClass(AgentPendingAction.class);
        verify(pendingActionMapper).updateById(captor.capture());
        assertEquals("CONFIRMED", captor.getValue().getStatus());
    }

    @Test
    @DisplayName("Expired pending action is rejected and marked failed")
    void expiredActionRejected() {
        AgentPendingAction action = AgentPendingAction.builder()
                .id(100L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_DELETE_KNOWLEDGE_DOCUMENT)
                .argumentsJson("{\"documentId\":9}")
                .status("PENDING")
                .expireTime(LocalDateTime.now().minusMinutes(1))
                .build();
        when(pendingActionMapper.selectOne(any(Wrapper.class))).thenReturn(action);

        assertThrows(IllegalStateException.class, () -> service.confirm(7L, 100L));

        verify(ragService, never()).deleteDocument(anyLong(), anyLong());
        ArgumentCaptor<AgentPendingAction> captor = ArgumentCaptor.forClass(AgentPendingAction.class);
        verify(pendingActionMapper).updateById(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
    }
}
