package com.example.chatbot.agent.review.conversation;

import com.example.chatbot.agent.review.CodeReviewAgentService;
import com.example.chatbot.agent.review.CodeReviewGitDiffRequest;
import com.example.chatbot.agent.review.CodeReviewIssue;
import com.example.chatbot.agent.review.CodeReviewIssueSeverity;
import com.example.chatbot.agent.review.CodeReviewPersistenceService;
import com.example.chatbot.agent.review.CodeReviewWorkspaceResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class CodeReviewConversationOrchestrator {

    private final CodeReviewIntentClassifier intentClassifier;
    private final CodeReviewAgentService codeReviewAgentService;
    private final CodeReviewPersistenceService persistenceService;
    private final Map<String, CodeReviewConversationContext> contexts = new ConcurrentHashMap<>();

    public CodeReviewConversationOrchestrator(CodeReviewIntentClassifier intentClassifier,
                                              CodeReviewAgentService codeReviewAgentService,
                                              CodeReviewPersistenceService persistenceService) {
        this.intentClassifier = intentClassifier;
        this.codeReviewAgentService = codeReviewAgentService;
        this.persistenceService = persistenceService;
    }

    public Optional<CodeReviewConversationResponse> handle(Long userId, String sessionId, String message) {
        CodeReviewIntent intent = intentClassifier.classify(sessionId, message);
        if (!intent.isCodeReviewIntent()) {
            return Optional.empty();
        }
        if (intent.getType() == CodeReviewIntentType.REVIEW_GIT_DIFF) {
            return Optional.of(handleGitDiffReview(userId, sessionId, intent));
        }
        return Optional.of(unsupported(intent));
    }

    public CodeReviewConversationContext getContext(Long userId, String sessionId) {
        ensureSessionOwnedByUser(userId, sessionId);
        return contexts.computeIfAbsent(contextKey(userId, sessionId), ignored -> CodeReviewConversationContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public CodeReviewConversationContext setActiveReviewRun(Long userId, String sessionId, String runId) {
        ensureSessionOwnedByUser(userId, sessionId);
        if (runId == null || runId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "runId is required");
        }
        boolean ownedByUserAndSession = persistenceService.listRuns(userId, sessionId, 100).stream()
                .anyMatch(run -> runId.equals(run.getRunId()));
        if (!ownedByUserAndSession) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "code review run not found");
        }
        CodeReviewConversationContext context = getContext(userId, sessionId);
        context.setActiveReviewRunId(runId);
        context.setUpdatedAt(LocalDateTime.now());
        contexts.put(contextKey(userId, sessionId), context);
        return context;
    }

    private CodeReviewConversationContext bindActiveReviewRun(Long userId, String sessionId, String runId) {
        CodeReviewConversationContext context = getContext(userId, sessionId);
        context.setActiveReviewRunId(runId);
        context.setUpdatedAt(LocalDateTime.now());
        contexts.put(contextKey(userId, sessionId), context);
        return context;
    }

    private CodeReviewConversationResponse handleGitDiffReview(Long userId, String sessionId, CodeReviewIntent intent) {
        CodeReviewGitDiffRequest request = new CodeReviewGitDiffRequest();
        request.setSessionId(sessionId);
        request.setFocus(toFocusText(intent));

        CodeReviewWorkspaceResult result = codeReviewAgentService.reviewGitDiff(userId, request);
        bindActiveReviewRun(userId, sessionId, result.getRunId());

        Map<String, Integer> severityCounts = severityCounts(result);
        int issueCount = result.getIssues() == null ? 0 : result.getIssues().size();
        String message = buildGitDiffSummary(result, issueCount, severityCounts);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.getRunId());
        payload.put("sessionId", sessionId);
        payload.put("scopeType", result.getScopeType());
        payload.put("reviewedFileCount", result.getReviewedFileCount());
        payload.put("issueCount", issueCount);
        payload.put("riskLevel", result.getRiskLevel());
        payload.put("severityCounts", severityCounts);
        payload.put("summary", result.getSummary());

        return CodeReviewConversationResponse.builder()
                .handled(true)
                .type(CodeReviewIntentType.REVIEW_GIT_DIFF)
                .message(message)
                .runId(result.getRunId())
                .issueCount(issueCount)
                .severityCounts(severityCounts)
                .payload(payload)
                .build();
    }

    private CodeReviewConversationResponse unsupported(CodeReviewIntent intent) {
        return CodeReviewConversationResponse.builder()
                .handled(true)
                .type(intent.getType())
                .message("已识别到代码审查相关意图：" + intent.getType()
                        + "。当前阶段已接入对话触发 Git diff 审查；单文件、workspace、issue 追问和 patch preview 会在后续阶段接入。")
                .build();
    }

    private String toFocusText(CodeReviewIntent intent) {
        if (intent.getFocusAreas() == null || intent.getFocusAreas().isEmpty()) {
            return intent.getUserInstruction();
        }
        return intent.getFocusAreas().stream().collect(Collectors.joining("、"));
    }

    private Map<String, Integer> severityCounts(CodeReviewWorkspaceResult result) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("BLOCKER", 0);
        counts.put("HIGH", 0);
        counts.put("MEDIUM", 0);
        counts.put("LOW", 0);
        counts.put("INFO", 0);
        if (result.getIssues() == null) {
            return counts;
        }
        for (CodeReviewIssue issue : result.getIssues()) {
            CodeReviewIssueSeverity severity = issue.getSeverity();
            String key = severity == null ? "INFO" : severity.name().toUpperCase(Locale.ROOT);
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private String buildGitDiffSummary(CodeReviewWorkspaceResult result, int issueCount, Map<String, Integer> severityCounts) {
        return "已完成 Git diff 审查，生成 Review Run " + result.getRunId() + "。\n"
                + "审查文件数：" + result.getReviewedFileCount() + "，发现问题：" + issueCount + " 个"
                + "（高危 " + (severityCounts.getOrDefault("BLOCKER", 0) + severityCounts.getOrDefault("HIGH", 0))
                + "，中危 " + severityCounts.getOrDefault("MEDIUM", 0)
                + "，低危 " + severityCounts.getOrDefault("LOW", 0) + "）。\n"
                + "你可以在代码审查面板查看 Issue Cards，也可以继续追问：解释第 1 个问题、只看权限相关问题、或为某个 issue 生成修复草案。";
    }

    private String contextKey(Long userId, String sessionId) {
        return userId + "::" + sessionId;
    }

    private void ensureSessionOwnedByUser(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || !sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
    }
}
