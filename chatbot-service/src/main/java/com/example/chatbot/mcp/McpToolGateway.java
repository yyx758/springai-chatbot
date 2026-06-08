package com.example.chatbot.mcp;

import com.example.chatbot.agent.AgentToolContextKeys;
import com.example.chatbot.agent.tool.ChatHistoryTools;
import com.example.chatbot.agent.tool.FileReadTools;
import com.example.chatbot.agent.tool.KnowledgeReadTools;
import com.example.chatbot.agent.tool.KnowledgeWriteTools;
import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileCreateRequest;
import com.example.chatbot.workspace.WorkspaceFileUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class McpToolGateway {

    private final McpProperties properties;
    private final KnowledgeReadTools knowledgeReadTools;
    private final FileReadTools fileReadTools;
    private final ChatHistoryTools chatHistoryTools;
    private final KnowledgeWriteTools knowledgeWriteTools;
    private final AgentWorkspaceService workspaceService;

    public List<McpToolSpec> listTools() {
        ensureServerEnabled();
        Set<String> allowed = allowedTools();
        return toolCatalog().stream()
                .filter(tool -> allowed.contains(tool.getName()))
                .toList();
    }

    public Map<String, Object> invoke(Long userId, McpToolInvocationRequest request) {
        ensureServerEnabled();
        if (request == null || isBlank(request.getToolName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toolName is required");
        }
        String toolName = request.getToolName().trim();
        McpToolSpec spec = toolIndex().get(toolName);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown MCP tool: " + toolName);
        }
        if (!allowedTools().contains(toolName)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MCP tool is not allowed: " + toolName);
        }

        Map<String, Object> arguments = request.getArguments() == null ? Map.of() : request.getArguments();
        ToolContext context = new ToolContext(Map.of(
                AgentToolContextKeys.USER_ID, String.valueOf(userId),
                AgentToolContextKeys.SESSION_ID, normalizeSessionId(userId, request.getSessionId())
        ));
        Object result = dispatch(toolName, arguments, context);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("toolName", toolName);
        response.put("riskLevel", spec.getRiskLevel());
        response.put("result", result);
        return response;
    }

    private Object dispatch(String toolName, Map<String, Object> args, ToolContext context) {
        return switch (toolName) {
            case "knowledge.search" -> {
                List<RagReference> result = knowledgeReadTools.searchKnowledge(
                        stringArg(args, "query"),
                        intArg(args, "topK"),
                        context
                );
                yield result;
            }
            case "files.info" -> fileReadTools.getFileInfo(stringArg(args, "fileKey"), context);
            case "files.list" -> fileReadTools.listUserFiles(intArg(args, "page"), intArg(args, "size"), context);
            case "chat.history" -> chatHistoryTools.getCurrentChatHistory(intArg(args, "limit"), context);
            case "workspace.files.list" -> {
                Long userId = Long.valueOf(String.valueOf(context.getContext().get(AgentToolContextKeys.USER_ID)));
                String sessionId = String.valueOf(context.getContext().get(AgentToolContextKeys.SESSION_ID));
                AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
                yield workspaceService.listFiles(userId, workspace.getId()).stream()
                        .map(workspaceService::toFileMap)
                        .toList();
            }
            case "workspace.files.read" -> {
                Long userId = Long.valueOf(String.valueOf(context.getContext().get(AgentToolContextKeys.USER_ID)));
                String sessionId = String.valueOf(context.getContext().get(AgentToolContextKeys.SESSION_ID));
                AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
                yield workspaceService.readFileContent(userId, workspace.getId(), stringArg(args, "relativePath"));
            }
            case "workspace.files.create" -> {
                Long userId = Long.valueOf(String.valueOf(context.getContext().get(AgentToolContextKeys.USER_ID)));
                String sessionId = String.valueOf(context.getContext().get(AgentToolContextKeys.SESSION_ID));
                WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
                request.setRelativePath(stringArg(args, "relativePath"));
                request.setContent(stringArg(args, "content"));
                request.setContentType(stringArg(args, "contentType"));
                request.setOverwrite(boolArg(args, "overwrite"));
                yield workspaceService.toFileMap(workspaceService.createFile(userId, sessionId, request));
            }
            case "workspace.files.update" -> {
                Long userId = Long.valueOf(String.valueOf(context.getContext().get(AgentToolContextKeys.USER_ID)));
                String sessionId = String.valueOf(context.getContext().get(AgentToolContextKeys.SESSION_ID));
                AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
                WorkspaceFileUpdateRequest request = new WorkspaceFileUpdateRequest();
                request.setRelativePath(stringArg(args, "relativePath"));
                request.setContent(stringArg(args, "content"));
                request.setExpectedVersion(intArg(args, "expectedVersion"));
                yield workspaceService.toFileMap(workspaceService.updateFile(userId, workspace.getId(), request));
            }
            case "knowledge.create" -> knowledgeWriteTools.createKnowledgeDocument(
                    stringArg(args, "title"),
                    stringArg(args, "content"),
                    stringArg(args, "tags"),
                    boolArg(args, "enabled"),
                    context
            );
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown MCP tool: " + toolName);
        };
    }

    private void ensureServerEnabled() {
        if (!properties.isEnabled() || !properties.getServer().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "MCP server is disabled");
        }
    }

    private Set<String> allowedTools() {
        return properties.getServer().getAllowedTools().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private Map<String, McpToolSpec> toolIndex() {
        return toolCatalog().stream().collect(Collectors.toMap(McpToolSpec::getName, Function.identity()));
    }

    private List<McpToolSpec> toolCatalog() {
        return List.of(
                McpToolSpec.builder()
                        .name("knowledge.search")
                        .description("Search the current user's knowledge base.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of("query", "string", "topK", "integer optional"))
                        .build(),
                McpToolSpec.builder()
                        .name("files.info")
                        .description("Read metadata for a file owned by the current user.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of("fileKey", "string"))
                        .build(),
                McpToolSpec.builder()
                        .name("files.list")
                        .description("List files uploaded by the current user.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of("page", "integer optional", "size", "integer optional"))
                        .build(),
                McpToolSpec.builder()
                        .name("chat.history")
                        .description("Read recent chat history from the current authenticated session.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of("limit", "integer optional"))
                        .build(),
                McpToolSpec.builder()
                        .name("workspace.files.list")
                        .description("List files in the current conversation workspace.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of())
                        .build(),
                McpToolSpec.builder()
                        .name("workspace.files.read")
                        .description("Read a file from the current conversation workspace.")
                        .riskLevel("READ_ONLY")
                        .arguments(Map.of("relativePath", "string"))
                        .build(),
                McpToolSpec.builder()
                        .name("workspace.files.create")
                        .description("Create a file in the current conversation workspace.")
                        .riskLevel("LOW_RISK_WRITE")
                        .arguments(Map.of("relativePath", "string", "content", "string", "contentType", "string optional", "overwrite", "boolean optional"))
                        .build(),
                McpToolSpec.builder()
                        .name("workspace.files.update")
                        .description("Update a file in the current conversation workspace.")
                        .riskLevel("LOW_RISK_WRITE")
                        .arguments(Map.of("relativePath", "string", "content", "string", "expectedVersion", "integer optional"))
                        .build(),
                McpToolSpec.builder()
                        .name("knowledge.create")
                        .description("Create a knowledge document for the current user.")
                        .riskLevel("LOW_RISK_WRITE")
                        .arguments(Map.of("title", "string", "content", "string", "tags", "string optional", "enabled", "boolean optional"))
                        .build()
        );
    }

    private String normalizeSessionId(Long userId, String sessionId) {
        if (isBlank(sessionId)) {
            return userId + "_mcp";
        }
        String trimmed = sessionId.trim();
        if (!trimmed.startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
        return trimmed;
    }

    private String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private Integer intArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be an integer");
        }
    }

    private Boolean boolArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
