package com.example.chatbot.agent;

import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.FileReadTools;
import com.example.chatbot.agent.tool.GitReviewTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.agent.tool.MemoryTools;
import com.example.chatbot.agent.tool.ReviewWorkspaceTools;
import com.example.chatbot.agent.tool.TimeTools;
import com.example.chatbot.agent.tool.WebTools;
import com.example.chatbot.agent.tool.WorkspaceTools;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.memory.MemorySuggestionService;
import com.example.chatbot.service.ChatContextService;
import com.example.chatbot.service.ChatbotService;
import com.example.chatbot.service.RagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentServiceMemoryPromptTest {

    @Test
    @DisplayName("Agent system prompt routes memory writes through confirmation tool")
    void agentPromptIncludesMemorySuggestionRules() {
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> openAiProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OllamaChatModel> ollamaProvider = mock(ObjectProvider.class);
        AgentService service = new AgentService(
                openAiProvider,
                ollamaProvider,
                mock(KnowledgeDocumentMapper.class),
                mock(ChatbotService.class),
                mock(ChatContextService.class),
                mock(RagService.class),
                mock(KnowledgeReadTools.class),
                mock(KnowledgeWriteTools.class),
                mock(FileReadTools.class),
                mock(GitReviewTools.class),
                mock(ChatHistoryTools.class),
                mock(TimeTools.class),
                mock(WorkspaceTools.class),
                mock(ReviewWorkspaceTools.class),
                mock(WebTools.class),
                mock(MemoryTools.class),
                new MemorySuggestionService()
        );

        String prompt = ReflectionTestUtils.invokeMethod(service, "buildSystemPrompt");

        assertTrue(prompt.contains("Long-term memory behavior"));
        assertTrue(prompt.contains("requestSaveLongTermMemory"));
        assertTrue(prompt.contains("Do not use workspace file tools to save memory"));
    }
}
