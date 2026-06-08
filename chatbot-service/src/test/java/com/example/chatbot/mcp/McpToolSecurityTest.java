package com.example.chatbot.mcp;

import com.example.chatbot.agent.AgentToolContextKeys;
import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.FileReadTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.dto.RagReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpToolSecurityTest {

    @Mock
    private KnowledgeReadTools knowledgeReadTools;

    @Mock
    private FileReadTools fileReadTools;

    @Mock
    private ChatHistoryTools chatHistoryTools;

    @Mock
    private KnowledgeWriteTools knowledgeWriteTools;

    @Mock
    private AgentWorkspaceService workspaceService;

    private McpProperties properties;
    private McpToolGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        properties.setEnabled(true);
        properties.getServer().setEnabled(true);
        properties.getServer().setAllowedTools(List.of("knowledge.search", "chat.history"));
        gateway = new McpToolGateway(properties, knowledgeReadTools, fileReadTools, chatHistoryTools, knowledgeWriteTools, workspaceService);
    }

    @Test
    @DisplayName("MCP passes authenticated user context to whitelisted tools")
    void invokesWhitelistedToolWithAuthenticatedContext() {
        McpToolInvocationRequest request = new McpToolInvocationRequest();
        request.setToolName("knowledge.search");
        request.setSessionId("7_mcp");
        request.setArguments(Map.of("query", "refund policy", "topK", 4));
        RagReference reference = RagReference.builder()
                .documentId(1L)
                .title("Policy")
                .snippet("hit")
                .score(9)
                .build();
        when(knowledgeReadTools.searchKnowledge(eq("refund policy"), eq(4), any()))
                .thenReturn(List.of(reference));

        Map<String, Object> result = gateway.invoke(7L, request);

        assertEquals(true, result.get("success"));
        assertEquals("knowledge.search", result.get("toolName"));
        ArgumentCaptor<ToolContext> contextCaptor = ArgumentCaptor.forClass(ToolContext.class);
        verify(knowledgeReadTools).searchKnowledge(eq("refund policy"), eq(4), contextCaptor.capture());
        assertEquals("7", contextCaptor.getValue().getContext().get(AgentToolContextKeys.USER_ID));
        assertEquals("7_mcp", contextCaptor.getValue().getContext().get(AgentToolContextKeys.SESSION_ID));
    }

    @Test
    @DisplayName("MCP refuses tools outside the allowlist")
    void rejectsToolOutsideAllowlist() {
        McpToolInvocationRequest request = new McpToolInvocationRequest();
        request.setToolName("knowledge.create");
        request.setSessionId("7_mcp");
        request.setArguments(Map.of("title", "Draft", "content", "Content"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> gateway.invoke(7L, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(knowledgeWriteTools);
    }

    @Test
    @DisplayName("MCP refuses sessions that do not belong to the authenticated user")
    void rejectsForeignSession() {
        McpToolInvocationRequest request = new McpToolInvocationRequest();
        request.setToolName("chat.history");
        request.setSessionId("8_session");
        request.setArguments(Map.of("limit", 5));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> gateway.invoke(7L, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(chatHistoryTools);
    }

    @Test
    @DisplayName("MCP server is not available while disabled")
    void disabledServerReturnsNotFound() {
        properties.setEnabled(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, gateway::listTools);

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }
}
