package com.example.chatbot.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KnowledgeReadTools {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;

    private final RagService ragService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolNotifier toolNotifier;
    private final AgentToolAuditService auditService;

    @Tool(description = "List ALL knowledge documents for the current user. Use this when the user asks to see all documents, manage documents, delete documents, or perform any batch operation on the knowledge base. Do NOT use searchKnowledge for listing — use this tool instead.")
    public List<Map<String, Object>> listAllKnowledgeDocuments(ToolContext toolContext) {
        String toolName = "listAllKnowledgeDocuments";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, Map.of());
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            List<KnowledgeDocument> documents = knowledgeDocumentMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeDocument>()
                            .eq(KnowledgeDocument::getUserId, userId)
                            .orderByDesc(KnowledgeDocument::getId));
            List<Map<String, Object>> result = documents.stream().map(doc -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", doc.getId());
                map.put("title", doc.getTitle());
                map.put("tags", doc.getTags() == null ? "" : doc.getTags());
                map.put("enabled", doc.getEnabled());
                map.put("createdTime", doc.getCreatedTime() != null ? doc.getCreatedTime().toString() : "");
                return map;
            }).toList();
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Search the current user's knowledge base by semantic similarity. Use this when the user asks a QUESTION about their knowledge content (e.g. 'Redis怎么用', '什么是Transformer'). Do NOT use this for listing all documents — use listAllKnowledgeDocuments instead.")
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
