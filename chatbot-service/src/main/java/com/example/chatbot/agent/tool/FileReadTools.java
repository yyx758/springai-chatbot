package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.service.FileServiceClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class FileReadTools {

    private static final int DEFAULT_SIZE = 10;
    private static final int MAX_SIZE = 30;

    private final FileServiceClient fileServiceClient;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolNotifier toolNotifier;
    private final AgentToolAuditService auditService;

    @Tool(description = "Get metadata for a file owned by the current user. This does not download file content.")
    public Map<String, Object> getFileInfo(
            @ToolParam(description = "File key returned by the file service") String fileKey,
            ToolContext toolContext
    ) {
        String toolName = "getFileInfo";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("fileKey", fileKey == null ? "" : fileKey));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            Map<String, Object> fileInfo = fileServiceClient.getFileInfo(fileKey, userId);
            if (fileInfo == null) {
                auditService.success(auditId, Map.of("success", false));
                toolNotifier.toolCompleted(toolContext, toolName);
                return Map.of("success", false, "error", "file not found or access denied");
            }
            auditService.success(auditId, Map.of("success", true));
            toolNotifier.toolCompleted(toolContext, toolName);
            return Map.of("success", true, "data", fileInfo);
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "List files uploaded by the current user. Use this when the user asks about their uploaded files.")
    public Map<String, Object> listUserFiles(
            @ToolParam(description = "Page number starting from 1", required = false) Integer page,
            @ToolParam(description = "Page size", required = false) Integer size,
            ToolContext toolContext
    ) {
        String toolName = "listUserFiles";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("page", page == null ? "" : page, "size", size == null ? "" : size));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            int finalPage = page == null || page <= 0 ? 1 : page;
            int finalSize = size == null || size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
            Map<String, Object> files = fileServiceClient.listFiles(finalPage, finalSize, userId);
            if (files == null) {
                auditService.success(auditId, Map.of("success", false));
                toolNotifier.toolCompleted(toolContext, toolName);
                return Map.of("success", false, "error", "failed to list files");
            }
            auditService.success(auditId, Map.of("success", true));
            toolNotifier.toolCompleted(toolContext, toolName);
            return Map.of("success", true, "data", files);
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }
}
