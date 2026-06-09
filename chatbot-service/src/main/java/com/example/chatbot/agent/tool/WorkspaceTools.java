package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileCreateRequest;
import com.example.chatbot.workspace.WorkspaceFileSaveToKnowledgeRequest;
import com.example.chatbot.workspace.WorkspaceFileUpdateRequest;
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
public class WorkspaceTools {

    private final AgentWorkspaceService workspaceService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "Create a text or source-code file in the current conversation workspace. Use this when the user asks to generate, save, or write a document, code file, config file, or project file.")
    public Map<String, Object> createWorkspaceFile(
            @ToolParam(description = "Safe relative path such as plan.md, src/main/java/App.java, or config/application.yml") String relativePath,
            @ToolParam(description = "Full file content") String content,
            @ToolParam(description = "Optional content type", required = false) String contentType,
            @ToolParam(description = "Whether to overwrite an existing file", required = false) Boolean overwrite,
            ToolContext toolContext
    ) {
        String toolName = "createWorkspaceFile";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("relativePath", safe(relativePath), "contentLength", content == null ? 0 : content.length()));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
            request.setRelativePath(relativePath);
            request.setContent(content);
            request.setContentType(contentType);
            request.setOverwrite(overwrite);
            AgentWorkspaceFile file = workspaceService.createFile(userId, sessionId, request);
            Map<String, Object> result = workspaceService.toFileMap(file);
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.workspaceFileCreated(toolContext, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Read a text or source-code file from the current conversation workspace before analyzing or editing it.")
    public Map<String, Object> readWorkspaceFile(
            @ToolParam(description = "Relative path of the file") String relativePath,
            ToolContext toolContext
    ) {
        String toolName = "readWorkspaceFile";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("relativePath", safe(relativePath)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, contextResolver.requireSessionId(toolContext));
            Map<String, Object> result = workspaceService.readFileContent(userId, workspace.getId(), relativePath);
            auditService.success(auditId, Map.of("relativePath", safe(relativePath)));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Replace an existing text or source-code file in the current conversation workspace. Use this to apply code fixes after reading the current file.")
    public Map<String, Object> updateWorkspaceFile(
            @ToolParam(description = "Relative path of the file") String relativePath,
            @ToolParam(description = "Full replacement content") String content,
            @ToolParam(description = "Expected file version", required = false) Integer expectedVersion,
            ToolContext toolContext
    ) {
        String toolName = "updateWorkspaceFile";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("relativePath", safe(relativePath), "contentLength", content == null ? 0 : content.length()));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, contextResolver.requireSessionId(toolContext));
            WorkspaceFileUpdateRequest request = new WorkspaceFileUpdateRequest();
            request.setRelativePath(relativePath);
            request.setContent(content);
            request.setExpectedVersion(expectedVersion);
            AgentWorkspaceFile file = workspaceService.updateFile(userId, workspace.getId(), request);
            Map<String, Object> result = workspaceService.toFileMap(file);
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.workspaceFileUpdated(toolContext, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Append content to a text or source-code file in the current conversation workspace. Creates the file if it does not exist.")
    public Map<String, Object> appendWorkspaceFile(
            @ToolParam(description = "Relative path of the file") String relativePath,
            @ToolParam(description = "Content to append") String content,
            ToolContext toolContext
    ) {
        String toolName = "appendWorkspaceFile";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("relativePath", safe(relativePath), "contentLength", content == null ? 0 : content.length()));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            AgentWorkspaceFile file = workspaceService.appendFile(userId, contextResolver.requireSessionId(toolContext), relativePath, content);
            Map<String, Object> result = workspaceService.toFileMap(file);
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.workspaceFileUpdated(toolContext, result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "List files in the current conversation workspace. Use this first when the user asks to inspect or modify an uploaded project.")
    public List<Map<String, Object>> listWorkspaceFiles(ToolContext toolContext) {
        String toolName = "listWorkspaceFiles";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, Map.of());
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, contextResolver.requireSessionId(toolContext));
            List<Map<String, Object>> result = workspaceService.listFiles(userId, workspace.getId()).stream()
                    .map(workspaceService::toFileMap)
                    .toList();
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Save a workspace file into the current user's knowledge base.")
    public Map<String, Object> saveWorkspaceFileToKnowledge(
            @ToolParam(description = "Relative path of the workspace file") String relativePath,
            @ToolParam(description = "Knowledge document title", required = false) String title,
            @ToolParam(description = "Optional tags", required = false) String tags,
            @ToolParam(description = "Whether the document should be enabled", required = false) Boolean enabled,
            ToolContext toolContext
    ) {
        String toolName = "saveWorkspaceFileToKnowledge";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("relativePath", safe(relativePath), "title", safe(title), "tags", safe(tags)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, contextResolver.requireSessionId(toolContext));
            WorkspaceFileSaveToKnowledgeRequest request = new WorkspaceFileSaveToKnowledgeRequest();
            request.setRelativePath(relativePath);
            request.setTitle(title);
            request.setTags(tags);
            request.setEnabled(enabled);
            KnowledgeDocument document = workspaceService.saveToKnowledge(userId, workspace.getId(), request);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("documentId", document.getId());
            result.put("title", document.getTitle());
            result.put("fileKey", document.getFileKey());
            result.put("openPath", "/api/knowledge/documents/" + document.getId());
            result.put("downloadUrl", document.getFileKey() == null ? "" : "/api/files/download/" + document.getFileKey());
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.workspaceFileSavedToKnowledge(toolContext, result);
            toolNotifier.knowledgeDocumentCreated(toolContext, result);
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
