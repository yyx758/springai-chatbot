package com.example.chatbot.agent.tool;

import com.example.chatbot.agent.AgentToolAuditService;
import com.example.chatbot.agent.AgentToolContextResolver;
import com.example.chatbot.agent.AgentToolLevel;
import com.example.chatbot.agent.AgentToolNotifier;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.workspace.AgentWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ReviewWorkspaceTools {

    private static final int MAX_SEARCHED_FILES = 80;
    private static final int MAX_LINE_CHARS = 240;

    private final AgentWorkspaceService workspaceService;
    private final AgentToolContextResolver contextResolver;
    private final AgentToolAuditService auditService;
    private final AgentToolNotifier toolNotifier;

    @Tool(description = "Read a workspace source file with stable 1-based line numbers for code review. Use before making line-specific review comments.")
    public Map<String, Object> readWorkspaceFileWithLineNumbers(
            @ToolParam(description = "Safe workspace relative path") String relativePath,
            ToolContext toolContext
    ) {
        String toolName = "readWorkspaceFileWithLineNumbers";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("relativePath", safe(relativePath)));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
            Map<String, Object> file = workspaceService.readFileContent(userId, workspace.getId(), relativePath);
            String content = String.valueOf(file.getOrDefault("content", ""));
            Map<String, Object> result = new LinkedHashMap<>(file);
            result.put("lineCount", lineCount(content));
            result.put("numberedContent", withLineNumbers(content));
            auditService.success(auditId, Map.of("relativePath", safe(relativePath), "lineCount", lineCount(content)));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("relativePath", safe(relativePath), "lineCount", lineCount(content)));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    @Tool(description = "Search source code in the current conversation workspace by keyword. Returns matching files and line snippets for code review context collection.")
    public List<Map<String, Object>> searchWorkspaceCode(
            @ToolParam(description = "Keyword or phrase to search for") String query,
            @ToolParam(description = "Optional comma-separated extensions, for example .java,.yml", required = false) String extensions,
            @ToolParam(description = "Maximum number of matches to return", required = false) Integer limit,
            ToolContext toolContext
    ) {
        String toolName = "searchWorkspaceCode";
        toolNotifier.toolStarted(toolContext, toolName);
        Long auditId = auditService.start(toolContext, toolName, AgentToolLevel.READ_ONLY,
                Map.of("query", safe(query), "extensions", safe(extensions), "limit", limit == null ? "" : limit));
        try {
            Long userId = contextResolver.requireUserId(toolContext);
            String sessionId = contextResolver.requireSessionId(toolContext);
            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, sessionId);
            List<Map<String, Object>> result = search(userId, workspace.getId(), query, extensions, normalizeLimit(limit));
            auditService.success(auditId, Map.of("resultCount", result.size()));
            toolNotifier.toolCompleted(toolContext, toolName, Map.of("resultCount", result.size()));
            return result;
        } catch (Exception e) {
            auditService.failure(auditId, e);
            toolNotifier.toolFailed(toolContext, toolName, e);
            throw e;
        }
    }

    private List<Map<String, Object>> search(Long userId, Long workspaceId, String query, String extensions, int limit) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        Set<String> extensionFilter = parseExtensions(extensions);
        List<AgentWorkspaceFile> files = workspaceService.listFiles(userId, workspaceId);
        List<Map<String, Object>> matches = new ArrayList<>();
        int searched = 0;
        for (AgentWorkspaceFile file : files) {
            if (matches.size() >= limit || searched >= MAX_SEARCHED_FILES) {
                break;
            }
            String path = file.getRelativePath();
            if (!extensionFilter.isEmpty() && !extensionFilter.contains(extension(path))) {
                continue;
            }
            if (!isReviewableSource(path)) {
                continue;
            }
            searched++;
            try {
                Map<String, Object> contentMap = workspaceService.readFileContent(userId, workspaceId, path);
                String content = String.valueOf(contentMap.getOrDefault("content", ""));
                addContentMatches(matches, limit, path, file.getFileName(), content, normalizedQuery);
            } catch (Exception ignored) {
                // Ignore unreadable files in search results. Direct reads still surface errors.
            }
        }
        return matches;
    }

    private void addContentMatches(List<Map<String, Object>> matches, int limit, String path, String fileName,
                                   String content, String normalizedQuery) {
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length && matches.size() < limit; i++) {
            String line = lines[i];
            if (line.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                Map<String, Object> match = new LinkedHashMap<>();
                match.put("relativePath", path);
                match.put("fileName", fileName);
                match.put("line", i + 1);
                match.put("preview", limitLine(line));
                matches.add(match);
            }
        }
    }

    private Set<String> parseExtensions(String extensions) {
        Set<String> result = new LinkedHashSet<>();
        if (extensions == null || extensions.isBlank()) {
            return result;
        }
        for (String item : extensions.split(",")) {
            String ext = item.trim().toLowerCase(Locale.ROOT);
            if (ext.isBlank()) {
                continue;
            }
            result.add(ext.startsWith(".") ? ext : "." + ext);
        }
        return result;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 20;
        }
        return Math.min(limit, 50);
    }

    private String withLineNumbers(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            builder.append(String.format(Locale.ROOT, "%4d | %s", i + 1, lines[i])).append('\n');
        }
        return builder.toString();
    }

    private int lineCount(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return content.split("\\R", -1).length;
    }

    private String limitLine(String line) {
        if (line == null) {
            return "";
        }
        String trimmed = line.trim();
        if (trimmed.length() <= MAX_LINE_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_LINE_CHARS) + "...";
    }

    private boolean isReviewableSource(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".py")
                || lower.endsWith(".go")
                || lower.endsWith(".rs")
                || lower.endsWith(".js")
                || lower.endsWith(".ts")
                || lower.endsWith(".jsx")
                || lower.endsWith(".tsx")
                || lower.endsWith(".vue")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".md")
                || lower.endsWith(".json");
    }

    private String extension(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String name = slash < 0 ? path : path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
