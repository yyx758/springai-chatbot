package com.example.chatbot.agent;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.ChatRecord;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentToolSecurityTest {

    @Mock
    private RagService ragService;

    @Mock
    private ChatRecordMapper chatRecordMapper;

    @Mock
    private AgentToolNotifier toolNotifier;

    @Mock
    private AgentToolAuditService auditService;

    private final AgentToolContextResolver contextResolver = new AgentToolContextResolver();

    @Test
    @DisplayName("Knowledge tool reads userId from tool context and caps topK")
    void knowledgeToolUsesContextUserAndCapsTopK() {
        KnowledgeReadTools tool = new KnowledgeReadTools(ragService, contextResolver, toolNotifier, auditService);
        ToolContext context = new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, "7",
                AgentToolContextKeys.SESSION_ID, "7_session"
        ));
        List<RagReference> expected = List.of(RagReference.builder()
                .documentId(1L)
                .title("doc")
                .snippet("hit")
                .score(10)
                .build());
        when(ragService.retrieveReferences(eq(7L), eq("refund policy"), eq(10))).thenReturn(expected);
        when(auditService.start(any(), eq("searchKnowledge"), eq(AgentToolLevel.READ_ONLY), any())).thenReturn(11L);

        List<RagReference> result = tool.searchKnowledge("refund policy", 99, context);

        assertEquals(expected, result);
        verify(ragService).retrieveReferences(7L, "refund policy", 10);
        verify(auditService).success(eq(11L), any());
        verify(toolNotifier).toolStarted(any(), eq("searchKnowledge"));
        verify(toolNotifier).toolCompleted(any(), eq("searchKnowledge"));
    }

    @Test
    @DisplayName("Tool schema does not expose userId or ToolContext to the model")
    void toolSchemaDoesNotExposeContext() {
        KnowledgeReadTools tool = new KnowledgeReadTools(ragService, contextResolver, toolNotifier, auditService);

        ToolCallback[] callbacks = ToolCallbacks.from(tool);

        assertEquals(1, callbacks.length);
        String inputSchema = callbacks[0].getToolDefinition().inputSchema();
        assertTrue(inputSchema.contains("query"));
        assertTrue(inputSchema.contains("topK"));
        assertFalse(inputSchema.contains("userId"));
        assertFalse(inputSchema.contains("sessionId"));
        assertFalse(inputSchema.contains("toolContext"));
        assertFalse(inputSchema.contains("ToolContext"));
    }

    @Test
    @DisplayName("Chat history tool refuses sessions owned by another user")
    void chatHistoryToolRejectsForeignSession() {
        ChatHistoryTools tool = new ChatHistoryTools(chatRecordMapper, contextResolver, toolNotifier, auditService);
        ToolContext context = new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, "7",
                AgentToolContextKeys.SESSION_ID, "7_session"
        ));

        assertThrows(IllegalArgumentException.class,
                () -> tool.getChatHistoryBySession("8_session", 10, context));

        verify(chatRecordMapper, never()).selectList(any());
        verify(auditService).failure(anyLong(), any(IllegalArgumentException.class));
        verify(toolNotifier).toolStarted(any(), eq("getChatHistoryBySession"));
        verify(toolNotifier).toolFailed(any(), eq("getChatHistoryBySession"), any(IllegalArgumentException.class));
    }

    @Test
    @DisplayName("Chat history tool returns only safe fields")
    void chatHistoryToolReturnsSafeFields() {
        ChatHistoryTools tool = new ChatHistoryTools(chatRecordMapper, contextResolver, toolNotifier, auditService);
        ToolContext context = new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, "7",
                AgentToolContextKeys.SESSION_ID, "7_session"
        ));
        ChatRecord record = ChatRecord.builder()
                .id(1L)
                .sessionId("7_session")
                .userMessage("hello")
                .botResponse("world")
                .imageData("data:image/png;base64,secret")
                .createdTime(LocalDateTime.now())
                .build();
        when(chatRecordMapper.selectList(any())).thenReturn(List.of(record));
        when(auditService.start(any(), eq("getCurrentChatHistory"), eq(AgentToolLevel.READ_ONLY), any())).thenReturn(12L);

        List<Map<String, Object>> result = tool.getCurrentChatHistory(5, context);

        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).get("userMessage"));
        assertFalse(result.get(0).containsKey("imageData"));
        ArgumentCaptor<Wrapper<ChatRecord>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(chatRecordMapper).selectList(wrapperCaptor.capture());
        verify(auditService).success(eq(12L), any());
        verify(toolNotifier).toolCompleted(any(), eq("getCurrentChatHistory"));
    }
}
