package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.agent.review.GitReviewService;
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
public class GitReviewTools {

    private final GitReviewService gitReviewService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "Get read-only git status for the current repository. Use this before reviewing local git changes.")
    public Map<String, Object> getGitStatus(ToolContext toolContext) {
        String toolName = "getGitStatus";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, Map.of());
        try {
            contextResolver.requireUserId(toolContext);
            String status = gitReviewService.getStatus();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            auditService.success(auditId, Map.of("chars", status.length()));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("chars", status.length()));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "List changed files from read-only git diff and staged diff.")
    public List<String> getChangedFiles(ToolContext toolContext) {
        String toolName = "getChangedFiles";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY, Map.of());
        try {
            contextResolver.requireUserId(toolContext);
            List<String> result = gitReviewService.getChangedFiles();
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("resultCount", result.size()));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Get read-only unified git diff for one changed file. Use this for PR-style code review.")
    public Map<String, Object> getFileDiff(
            @ToolParam(description = "Repository relative path of the changed file") String relativePath,
            ToolContext toolContext
    ) {
        String toolName = "getFileDiff";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("relativePath", relativePath == null ? "" : relativePath));
        try {
            contextResolver.requireUserId(toolContext);
            String diff = gitReviewService.getFileDiff(relativePath);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("relativePath", relativePath);
            result.put("diff", diff);
            result.put("chars", diff.length());
            auditService.success(auditId, Map.of("relativePath", relativePath == null ? "" : relativePath, "chars", diff.length()));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("relativePath", relativePath == null ? "" : relativePath, "chars", diff.length()));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }
}
