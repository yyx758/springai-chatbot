package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.AgentPendingActionMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.RagService;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileUpdateRequest;
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
import java.util.List;
import java.util.Map;

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

    @Mock
    private AgentWorkspaceService workspaceService;

    private AgentPendingActionService service;

    @BeforeEach
    void setUp() {
        service = new AgentPendingActionService(
                pendingActionMapper,
                knowledgeDocumentMapper,
                ragService,
                workspaceService,
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

    @Test
    @DisplayName("Apply workspace patch creates pending action instead of updating immediately")
    void requestApplyWorkspacePatchCreatesPendingAction() {
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.requireWorkspaceFile(7L, 10L, "src/App.java"))
                .thenReturn(AgentWorkspaceFile.builder().id(3L).workspaceId(10L).relativePath("src/App.java").build());

        AgentPendingAction action = service.requestApplyWorkspacePatch(
                7L,
                "7_session",
                "src/App.java",
                "class App {}",
                1,
                "review fix"
        );

        assertEquals("PENDING", action.getStatus());
        assertEquals(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH, action.getActionType());
        assertTrue(action.getArgumentsJson().contains("\"relativePath\":\"src/App.java\""));
        verify(workspaceService, never()).updateFile(anyLong(), anyLong(), any(WorkspaceFileUpdateRequest.class));
        verify(pendingActionMapper).insert(any(AgentPendingAction.class));
    }

    @Test
    @DisplayName("List pending actions returns safe card without replacement content")
    void listActionsReturnsSafeCards() {
        AgentPendingAction action = AgentPendingAction.builder()
                .id(101L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH)
                .toolName("requestApplyWorkspacePatch")
                .argumentsJson("{\"workspaceId\":10,\"relativePath\":\"src/App.java\",\"replacementContent\":\"class App {}\",\"expectedVersion\":1,\"reason\":\"review fix\"}")
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .createdTime(LocalDateTime.now())
                .build();
        when(pendingActionMapper.selectList(any(Wrapper.class))).thenReturn(List.of(action));

        List<AgentPendingAction> actions = service.listActions(
                7L,
                "7_session",
                AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH,
                "PENDING",
                20
        );
        Map<String, Object> card = service.toActionCard(actions.get(0));
        Map<?, ?> arguments = (Map<?, ?>) card.get("arguments");

        assertEquals(1, actions.size());
        assertEquals("src/App.java", arguments.get("relativePath"));
        assertEquals("review fix", arguments.get("reason"));
        assertFalse(arguments.containsKey("replacementContent"));
    }

    @Test
    @DisplayName("Confirm apply workspace patch updates workspace file")
    void confirmApplyWorkspacePatchUpdatesWorkspaceFile() {
        AgentPendingAction action = AgentPendingAction.builder()
                .id(101L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH)
                .toolName("requestApplyWorkspacePatch")
                .argumentsJson("{\"workspaceId\":10,\"relativePath\":\"src/App.java\",\"replacementContent\":\"class App {}\",\"expectedVersion\":1,\"reason\":\"review fix\"}")
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .build();
        when(pendingActionMapper.selectOne(any(Wrapper.class))).thenReturn(action);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());

        AgentPendingAction confirmed = service.confirm(7L, 101L);

        assertEquals("CONFIRMED", confirmed.getStatus());
        ArgumentCaptor<WorkspaceFileUpdateRequest> requestCaptor = ArgumentCaptor.forClass(WorkspaceFileUpdateRequest.class);
        verify(workspaceService).updateFile(eq(7L), eq(10L), requestCaptor.capture());
        assertEquals("src/App.java", requestCaptor.getValue().getRelativePath());
        assertEquals("class App {}", requestCaptor.getValue().getContent());
        assertEquals(1, requestCaptor.getValue().getExpectedVersion());
        verify(pendingActionMapper).updateById(any(AgentPendingAction.class));
    }

    @Test
    @DisplayName("Cancel pending action marks it cancelled without executing")
    void cancelPendingAction() {
        AgentPendingAction action = AgentPendingAction.builder()
                .id(102L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH)
                .argumentsJson("{\"relativePath\":\"src/App.java\"}")
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(5))
                .build();
        when(pendingActionMapper.selectOne(any(Wrapper.class))).thenReturn(action);

        AgentPendingAction cancelled = service.cancel(7L, 102L);

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("pending action cancelled", cancelled.getResultSummary());
        verify(workspaceService, never()).updateFile(anyLong(), anyLong(), any(WorkspaceFileUpdateRequest.class));
        verify(pendingActionMapper).updateById(action);
    }
}
