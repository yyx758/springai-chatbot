package com.example.chatbot.agent;

import com.example.chatbot.agent.tool.MemoryTools;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.memory.LongTermMemoryRequest;
import com.example.chatbot.memory.LongTermMemoryService;
import com.example.chatbot.memory.MemoryIndexItem;
import com.example.chatbot.memory.MemoryIndexService;
import com.example.chatbot.memory.MemoryPromptBuilder;
import com.example.chatbot.memory.MemorySelectionPreview;
import com.example.chatbot.memory.MemorySelectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryToolsTest {

    @Mock
    private LongTermMemoryService memoryService;

    @Mock
    private MemoryIndexService memoryIndexService;

    @Mock
    private MemorySelectionService memorySelectionService;

    @Mock
    private MemoryPromptBuilder promptBuilder;

    @Mock
    private AgentPendingActionService pendingActionService;

    @Mock
    private AgentToolAuditService auditService;

    @Mock
    private AgentToolNotifier toolNotifier;

    private final AgentToolContextResolver contextResolver = new AgentToolContextResolver();

    @Test
    @DisplayName("Request save memory creates pending action instead of writing immediately")
    void requestSaveMemoryCreatesPendingAction() {
        MemoryTools tools = tools();
        ToolContext context = context();
        AgentPendingAction action = AgentPendingAction.builder()
                .id(77L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_SAVE_LONG_TERM_MEMORY)
                .status("PENDING")
                .expireTime(LocalDateTime.now().plusMinutes(10))
                .build();
        when(auditService.start(any(), eq("requestSaveLongTermMemory"), eq(AgentToolLevel.REQUIRE_CONFIRMATION), any()))
                .thenReturn(31L);
        when(pendingActionService.requestSaveLongTermMemory(eq(7L), eq("7_session"), any(LongTermMemoryRequest.class), eq("remember")))
                .thenReturn(action);

        Map<String, Object> result = tools.requestSaveLongTermMemory(
                "PROJECT",
                "springaI-chatbot",
                "project",
                "Gateway rule",
                "Production traffic uses gateway.",
                "Production user entry must go through Gateway :9000.",
                "Load for deployment tasks.",
                "remember",
                context
        );

        assertEquals(true, result.get("success"));
        assertEquals(true, result.get("requiresConfirmation"));
        assertEquals(77L, result.get("actionId"));
        assertEquals("/api/chat/agent/actions/77/confirm", result.get("confirmPath"));
        ArgumentCaptor<LongTermMemoryRequest> captor = ArgumentCaptor.forClass(LongTermMemoryRequest.class);
        verify(pendingActionService).requestSaveLongTermMemory(eq(7L), eq("7_session"), captor.capture(), eq("remember"));
        assertEquals("AGENT_SUGGESTED", captor.getValue().getSourceType());
        assertEquals("ACTIVE", captor.getValue().getStatus());
        verify(memoryService, never()).create(anyLong(), any(LongTermMemoryRequest.class));
        verify(auditService).success(eq(31L), any());
    }

    @Test
    @DisplayName("List memory index is read-only")
    void listMemoryIndexIsReadOnly() {
        MemoryTools tools = tools();
        ToolContext context = context();
        MemoryIndexItem item = MemoryIndexItem.builder()
                .id(1L)
                .name("Project rule")
                .memoryType("project")
                .build();
        when(auditService.start(any(), eq("listLongTermMemoryIndex"), eq(AgentToolLevel.READ_ONLY), any()))
                .thenReturn(32L);
        when(memoryIndexService.loadIndex(7L, "springaI-chatbot")).thenReturn(List.of(item));
        when(promptBuilder.buildIndexPrompt(List.of(item))).thenReturn("index prompt");

        Map<String, Object> result = tools.listLongTermMemoryIndex("springaI-chatbot", context);

        assertEquals(true, result.get("success"));
        assertEquals("index prompt", result.get("prompt"));
        assertTrue(((List<?>) result.get("items")).size() == 1);
        verify(memoryService, never()).create(anyLong(), any(LongTermMemoryRequest.class));
    }

    @Test
    @DisplayName("Preview memory details is read-only")
    void previewMemoryDetailsIsReadOnly() {
        MemoryTools tools = tools();
        ToolContext context = context();
        MemorySelectionPreview preview = MemorySelectionPreview.builder()
                .selectedIds(List.of(1L, 2L))
                .prompt("detail prompt")
                .build();
        when(auditService.start(any(), eq("previewLongTermMemoryDetails"), eq(AgentToolLevel.READ_ONLY), any()))
                .thenReturn(33L);
        when(memorySelectionService.selectDetailPreview(7L, "springaI-chatbot", "deploy"))
                .thenReturn(preview);

        Map<String, Object> result = tools.previewLongTermMemoryDetails("deploy", "springaI-chatbot", context);

        assertEquals(true, result.get("success"));
        assertEquals(preview, result.get("selection"));
        verify(memoryService, never()).create(anyLong(), any(LongTermMemoryRequest.class));
    }

    private MemoryTools tools() {
        return new MemoryTools(
                memoryService,
                memoryIndexService,
                memorySelectionService,
                promptBuilder,
                pendingActionService,
                contextResolver,
                auditService,
                toolNotifier
        );
    }

    private ToolContext context() {
        return new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, "7",
                AgentToolContextKeys.SESSION_ID, "7_session"
        ));
    }
}
