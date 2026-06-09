package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.webtools.WebToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class WebTools {

    private final WebToolService webToolService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "Search the public web. Use only when the user asks for current or external web information.")
    public Map<String, Object> searchWeb(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Maximum number of results", required = false) Integer limit,
            ToolContext toolContext
    ) {
        String toolName = "searchWeb";
        toolNotifier.toolStarted(toolContext, toolName);
        toolNotifier.webSearchStarted(Map.of("query", query == null ? "" : query));
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("query", query == null ? "" : query, "limit", limit == null ? "" : limit));
        try {
            Map<String, Object> result = webToolService.search(query, limit);
            auditService.success(auditId, Map.of("success", true));
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.webSearchCompleted(result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Fetch readable markdown content from a public web page.")
    public Map<String, Object> fetchWebPage(
            @ToolParam(description = "Public http or https URL") String url,
            ToolContext toolContext
    ) {
        String toolName = "fetchWebPage";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("url", url == null ? "" : url));
        try {
            Map<String, Object> result = webToolService.fetch(url);
            auditService.success(auditId, Map.of("success", true, "contentLength", result.get("contentLength")));
            toolNotifier.toolCompleted(toolContext, toolName);
            toolNotifier.webFetchCompleted(result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Fetch a public web page and save it as a markdown file in the current conversation workspace.")
    public Map<String, Object> createWorkspaceFileFromWebPage(
            @ToolParam(description = "Public http or https URL") String url,
            @ToolParam(description = "Relative markdown file path, such as research/page.md") String relativePath,
            ToolContext toolContext
    ) {
        String toolName = "createWorkspaceFileFromWebPage";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.LOW_RISK_WRITE,
                Map.of("url", url == null ? "" : url, "relativePath", relativePath == null ? "" : relativePath));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            Map<String, Object> result = webToolService.createWorkspaceFileFromWebPage(userId, sessionId, url, relativePath);
            auditService.success(auditId, result);
            toolNotifier.toolCompleted(toolContext, toolName, result);
            toolNotifier.workspaceFileCreated(result);
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }
}
