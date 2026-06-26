package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.memory.LongTermMemoryRequest;
import com.example.chatbot.memory.LongTermMemoryService;
import com.example.chatbot.memory.MemoryIndexService;
import com.example.chatbot.memory.MemoryPromptBuilder;
import com.example.chatbot.memory.MemorySelectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MemoryTools {

    private final LongTermMemoryService memoryService;
    private final MemoryIndexService memoryIndexService;
    private final MemorySelectionService memorySelectionService;
    private final MemoryPromptBuilder promptBuilder;
    private final AgentPendingActionService pendingActionService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "List the current user's long-term memory index. Use before relying on saved preferences or project constraints.")
    public Map<String, Object> listLongTermMemoryIndex(
            @ToolParam(description = "Optional project key. Use springaI-chatbot for this project when applicable.", required = false) String projectKey,
            ToolContext toolContext
    ) {
        String toolName = "listLongTermMemoryIndex";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("projectKey", safe(projectKey)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            var items = memoryIndexService.loadIndex(userId, projectKey);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("items", items);
            result.put("prompt", promptBuilder.buildIndexPrompt(items));
            auditService.success(auditId, Map.of("resultCount", items.size()));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("resultCount", items.size()));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Preview which long-term memory details would be loaded for the current user input. This is read-only.")
    public Map<String, Object> previewLongTermMemoryDetails(
            @ToolParam(description = "Current user request or task text") String userInput,
            @ToolParam(description = "Optional project key. Use springaI-chatbot for this project when applicable.", required = false) String projectKey,
            ToolContext toolContext
    ) {
        String toolName = "previewLongTermMemoryDetails";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("projectKey", safe(projectKey), "inputLength", userInput == null ? 0 : userInput.length()));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            var selection = memorySelectionService.selectDetailPreview(userId, projectKey, userInput);
            int selectedCount = selection.getSelectedIds() == null ? 0 : selection.getSelectedIds().size();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("selection", selection);
            auditService.success(auditId, Map.of("selectedCount", selectedCount));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("selectedCount", selectedCount));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Request saving a stable user preference, project constraint, or reusable reference into long-term memory. This does not write immediately; it creates a pending action that the user must confirm. Use only when the user explicitly asks you to remember or save something.")
    public Map<String, Object> requestSaveLongTermMemory(
            @ToolParam(description = "Memory scope: USER, PROJECT, or SESSION") String scopeType,
            @ToolParam(description = "Scope key. For PROJECT scope use springaI-chatbot unless the user specified another project.", required = false) String scopeKey,
            @ToolParam(description = "Memory type: user, feedback, project, or reference") String memoryType,
            @ToolParam(description = "Short memory name") String name,
            @ToolParam(description = "One sentence summary") String description,
            @ToolParam(description = "Full memory content to save. Do not include secrets, passwords, tokens, .env content, or temporary one-off details.") String content,
            @ToolParam(description = "When this memory should be loaded in future prompts", required = false) String loadHint,
            @ToolParam(description = "Why this should be saved", required = false) String reason,
            ToolContext toolContext
    ) {
        String toolName = "requestSaveLongTermMemory";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.REQUIRE_CONFIRMATION,
                Map.of("scopeType", safe(scopeType), "scopeKey", safe(scopeKey), "memoryType", safe(memoryType),
                        "name", safe(name), "contentLength", content == null ? 0 : content.length()));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            contextResolver.ensureSessionOwnedByUser(sessionId, userId);

            LongTermMemoryRequest request = new LongTermMemoryRequest();
            request.setScopeType(scopeType);
            request.setScopeKey(scopeKey);
            request.setMemoryType(memoryType);
            request.setName(name);
            request.setDescription(description);
            request.setContent(content);
            request.setLoadHint(loadHint);
            request.setSourceType("AGENT_SUGGESTED");
            request.setStatus("ACTIVE");

            AgentPendingAction action = pendingActionService.requestSaveLongTermMemory(userId, sessionId, request, reason);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("requiresConfirmation", true);
            result.put("actionId", action.getId());
            result.put("actionType", action.getActionType());
            result.put("expireTime", action.getExpireTime());
            result.put("confirmPath", "/api/chat/agent/actions/" + action.getId() + "/confirm");
            result.put("name", name);
            result.put("memoryType", memoryType);
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Read one long-term memory detail by id for the current user. Use only after listLongTermMemoryIndex or when the user references a specific memory id.")
    public Map<String, Object> readLongTermMemory(
            @ToolParam(description = "Long-term memory id") Long memoryId,
            ToolContext toolContext
    ) {
        String toolName = "readLongTermMemory";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("memoryId", memoryId == null ? "" : memoryId));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            var memory = memoryService.get(userId, memoryId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("memory", memory);
            auditService.success(auditId, Map.of("memoryId", memoryId));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("memoryId", memoryId));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
