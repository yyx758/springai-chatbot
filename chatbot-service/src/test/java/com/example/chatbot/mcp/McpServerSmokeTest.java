package com.example.chatbot.mcp;

import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.FileReadTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.controller.McpController;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.workspace.AgentWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpServerSmokeTest {

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

    private McpController controller;

    @BeforeEach
    void setUp() {
        McpProperties properties = new McpProperties();
        properties.setEnabled(true);
        properties.getServer().setEnabled(true);
        properties.getServer().setAllowedTools(List.of("knowledge.search", "chat.history"));
        McpToolGateway gateway = new McpToolGateway(
                properties, knowledgeReadTools, fileReadTools, chatHistoryTools, knowledgeWriteTools, workspaceService);
        controller = new McpController(gateway);
    }

    @Test
    @DisplayName("MCP tool list exposes only allowed tools")
    void listsAllowedTools() {
        Map<String, Object> response = controller.listTools();

        assertEquals(true, response.get("success"));
        @SuppressWarnings("unchecked")
        List<McpToolSpec> tools = (List<McpToolSpec>) response.get("tools");
        assertEquals(2, tools.size());
        assertTrue(tools.stream().anyMatch(tool -> "knowledge.search".equals(tool.getName())));
        assertTrue(tools.stream().anyMatch(tool -> "chat.history".equals(tool.getName())));
        assertFalse(tools.stream().anyMatch(tool -> "knowledge.create".equals(tool.getName())));
    }

    @Test
    @DisplayName("MCP invoke endpoint returns tool result")
    void invokesToolThroughController() {
        when(chatHistoryTools.getCurrentChatHistory(eq(2), any()))
                .thenReturn(List.of(Map.of("userMessage", "hello", "botResponse", "world")));
        McpToolInvocationRequest request = new McpToolInvocationRequest();
        request.setToolName("chat.history");
        request.setSessionId("7_mcp");
        request.setArguments(Map.of("limit", 2));
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute(AuthInterceptor.AUTH_USER_ID_ATTR, 7L);

        Map<String, Object> response = controller.invoke(request, httpRequest);

        assertEquals(true, response.get("success"));
        assertEquals("chat.history", response.get("toolName"));
        assertEquals("READ_ONLY", response.get("riskLevel"));
        assertNotNull(response.get("result"));
    }
}
