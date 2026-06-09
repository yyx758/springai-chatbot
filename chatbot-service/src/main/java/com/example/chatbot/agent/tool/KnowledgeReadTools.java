package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KnowledgeReadTools {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;

    private final RagService ragService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolNotifier toolNotifier;
    private final AgentToolAuditService auditService;

    @Tool(description = "Search the current user's knowledge base. Use this when the user asks questions that may depend on uploaded documents or private knowledge.")
    public List<RagReference> searchKnowledge(
            @ToolParam(description = "Search query from the user's question") String query,
            @ToolParam(description = "Maximum number of references to return", required = false) Integer topK,
            ToolContext toolContext
    ) {
        String toolName = "searchKnowledge";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("query", query == null ? "" : query, "topK", topK == null ? "" : topK));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            int finalTopK = topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
            List<RagReference> result = ragService.retrieveReferences(userId, query, finalTopK);
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }
}
