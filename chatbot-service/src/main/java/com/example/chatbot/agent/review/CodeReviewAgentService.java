package com.example.chatbot.agent.review;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.agent.runtime.AgentRun;
import com.example.chatbot.agent.runtime.AgentRuntime;
import com.example.chatbot.agent.runtime.AgentStepType;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.CodeReviewIssueRecord;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileCreateRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeReviewAgentService {

    private static final Pattern HUNK_HEADER = Pattern.compile("@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    private final AgentWorkspaceService workspaceService;
    private final CodeReviewProperties properties;
    private final AgentRuntime agentRuntime;
    private final GitReviewService gitReviewService;
    private final AgentPendingActionService pendingActionService;
    private final CodeReviewPersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;
    private final ObjectProvider<OllamaChatModel> ollamaChatModelProvider;

    @Value("#{environment['spring.ai.openai.api-key'] ?: ''}")
    private String openAiApiKey;

    public CodeReviewResult reviewFile(Long userId, CodeReviewRequest request) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "code review agent is disabled");
        }
        validateRequest(userId, request);

        AgentRun run = agentRuntime.start("CODE_REVIEW", "Review workspace file: " + request.getRelativePath());
        try {
            agentRuntime.step(run, AgentStepType.CLASSIFY_SCOPE, "审查范围已识别为单文件。",
                    Map.of("relativePath", request.getRelativePath()));

            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, request.getSessionId());
            Map<String, Object> file = workspaceService.readFileContent(userId, workspace.getId(), request.getRelativePath());
            String content = String.valueOf(file.getOrDefault("content", ""));
            String relativePath = String.valueOf(file.getOrDefault("relativePath", request.getRelativePath()));
            String fileName = String.valueOf(file.getOrDefault("fileName", fileName(relativePath)));
            int maxIssues = normalizeMaxIssues(request.getMaxIssues());
            agentRuntime.step(run, AgentStepType.COLLECT_CONTEXT, "已读取待审查文件内容。",
                    Map.of("workspaceId", workspace.getId(), "relativePath", relativePath, "chars", content.length()));

            ReviewInput input = buildReviewInput(request, workspace, relativePath, fileName, content, maxIssues);
            CodeReviewResult result = tryModelReview(input);
            if (result == null) {
                result = localReview(input, "AI model unavailable or returned invalid structured review; used local conservative review rules.");
            }
            agentRuntime.step(run, AgentStepType.ANALYZE, "已完成单文件问题分析。",
                    Map.of("modelUsed", result.getModelUsed() == null ? "" : result.getModelUsed(),
                            "issueCount", result.getIssues() == null ? 0 : result.getIssues().size()));

            result.setSuccess(true);
            result.setRunId(run.getRunId());
            result.setSessionId(request.getSessionId());
            result.setWorkspaceId(workspace.getId());
            result.setRelativePath(relativePath);
            result.setFileName(fileName);
            result.setReviewedChars(input.reviewContent().length());
            result.setTruncated(input.truncated());
            result.setCreatedTime(LocalDateTime.now());
            normalizeResult(result, input);
            agentRuntime.step(run, AgentStepType.VERIFY, "已校验审查结果结构。",
                    Map.of("riskLevel", result.getRiskLevel(), "issueCount", result.getIssues().size()));
            agentRuntime.step(run, AgentStepType.REPORT, "已生成单文件审查报告。",
                    Map.of("runId", run.getRunId()));
            agentRuntime.complete(run);
            result.setSteps(new ArrayList<>(run.getSteps()));
            persistFileResultSafely(userId, result);
            return result;
        } catch (Exception e) {
            agentRuntime.fail(run, e);
            throw e;
        }
    }

    public CodeReviewWorkspaceResult reviewWorkspace(Long userId, CodeReviewWorkspaceRequest request) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "code review agent is disabled");
        }
        validateWorkspaceRequest(userId, request);

        AgentRun run = agentRuntime.start("CODE_REVIEW", "Review workspace files");
        try {
            String scopeType = scopeType(request);
            agentRuntime.step(run, AgentStepType.CLASSIFY_SCOPE, "审查范围已识别为工作区多文件。",
                    Map.of("scopeType", scopeType));

            AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, request.getSessionId());
            List<AgentWorkspaceFile> files = workspaceService.listFiles(userId, workspace.getId());
            List<String> selectedPaths = selectReviewPaths(userId, workspace, files, request);
            agentRuntime.step(run, AgentStepType.COLLECT_CONTEXT, "已选择待审查文件。",
                    Map.of("workspaceId", workspace.getId(), "candidateFiles", files.size(), "selectedFiles", selectedPaths.size()));

            if (selectedPaths.isEmpty()) {
                CodeReviewWorkspaceResult emptyResult = CodeReviewWorkspaceResult.builder()
                        .success(true)
                        .runId(run.getRunId())
                        .sessionId(request.getSessionId())
                        .workspaceId(workspace.getId())
                        .scopeType(scopeType)
                        .reviewedFileCount(0)
                        .summary("未找到符合条件的工作区文件。")
                        .riskLevel("LOW")
                        .warnings(new ArrayList<>(List.of("No workspace files matched the review scope.")))
                        .createdTime(LocalDateTime.now())
                        .build();
                agentRuntime.step(run, AgentStepType.REPORT, "未找到可审查文件，已生成空报告。");
            agentRuntime.complete(run);
            emptyResult.setSteps(new ArrayList<>(run.getSteps()));
            persistWorkspaceResultSafely(userId, emptyResult);
            return emptyResult;
            }

            List<CodeReviewResult> fileResults = new ArrayList<>();
            for (String path : selectedPaths) {
                CodeReviewRequest fileRequest = new CodeReviewRequest();
                fileRequest.setSessionId(request.getSessionId());
                fileRequest.setRelativePath(path);
                fileRequest.setFocus(request.getFocus());
                fileRequest.setMaxIssues(request.getMaxIssuesPerFile());
                fileResults.add(reviewFile(userId, fileRequest));
            }
            agentRuntime.step(run, AgentStepType.ANALYZE, "已完成多文件逐文件分析。",
                    Map.of("reviewedFileCount", fileResults.size()));

            List<CodeReviewIssue> issues = fileResults.stream()
                    .flatMap(result -> result.getIssues().stream())
                    .sorted(Comparator.comparing(this::severityRank))
                    .toList();
            List<CodeReviewFileSummary> fileSummaries = fileResults.stream()
                    .map(result -> CodeReviewFileSummary.builder()
                            .relativePath(result.getRelativePath())
                            .fileName(result.getFileName())
                            .riskLevel(result.getRiskLevel())
                            .issueCount(result.getIssues().size())
                            .truncated(result.isTruncated())
                            .summary(result.getSummary())
                            .build())
                    .toList();
            List<String> warnings = fileResults.stream()
                    .flatMap(result -> result.getWarnings().stream())
                    .distinct()
                    .collect(Collectors.toCollection(ArrayList::new));
            if (selectedPaths.size() < files.size()) {
                warnings.add("本次按范围只审查 " + selectedPaths.size() + " 个文件，未覆盖全部工作区文件。");
            }
            agentRuntime.step(run, AgentStepType.VERIFY, "已聚合并校验多文件审查结果。",
                    Map.of("issueCount", issues.size(), "riskLevel", calculateRiskLevel(issues)));

            CodeReviewWorkspaceResult result = CodeReviewWorkspaceResult.builder()
                    .success(true)
                    .runId(run.getRunId())
                    .sessionId(request.getSessionId())
                    .workspaceId(workspace.getId())
                    .scopeType(scopeType)
                    .reviewedFileCount(fileResults.size())
                    .summary("工作区多文件审查完成，审查 " + fileResults.size() + " 个文件，发现 " + issues.size() + " 个问题。")
                    .riskLevel(calculateRiskLevel(issues))
                    .files(new ArrayList<>(fileSummaries))
                    .issues(new ArrayList<>(issues))
                    .testsToAdd(aggregateStrings(fileResults.stream().flatMap(item -> item.getTestsToAdd().stream()).toList()))
                    .patchPlan(aggregateStrings(fileResults.stream().flatMap(item -> item.getPatchPlan().stream()).toList()))
                    .warnings(warnings)
                    .createdTime(LocalDateTime.now())
                    .build();
            agentRuntime.step(run, AgentStepType.REPORT, "已生成工作区多文件审查报告。",
                    Map.of("runId", run.getRunId()));
            agentRuntime.complete(run);
            result.setSteps(new ArrayList<>(run.getSteps()));
            persistWorkspaceResultSafely(userId, result);
            return result;
        } catch (Exception e) {
            agentRuntime.fail(run, e);
            throw e;
        }
    }

    public CodeReviewWorkspaceResult reviewGitDiff(Long userId, CodeReviewGitDiffRequest request) {
        validateGitDiffRequest(userId, request);
        AgentRun run = agentRuntime.start("CODE_REVIEW", "Review git diff");
        try {
            agentRuntime.step(run, AgentStepType.CLASSIFY_SCOPE, "审查范围已识别为 Git diff。",
                    Map.of("scopeType", "GIT_DIFF"));

            List<String> changedFiles = selectGitChangedFiles(request).stream()
                    .filter(this::isReviewableSource)
                    .limit(normalizeMaxFiles(request.getMaxFiles()))
                    .toList();
            agentRuntime.step(run, AgentStepType.COLLECT_CONTEXT, "已读取 Git 变更文件列表。",
                    Map.of("changedFiles", changedFiles.size()));

            if (changedFiles.isEmpty()) {
                CodeReviewWorkspaceResult result = CodeReviewWorkspaceResult.builder()
                        .success(true)
                        .runId(run.getRunId())
                        .sessionId(request.getSessionId())
                        .scopeType("GIT_DIFF")
                        .reviewedFileCount(0)
                        .summary("当前 Git diff 没有可审查的源文件变更。")
                        .riskLevel("LOW")
                        .warnings(new ArrayList<>(List.of("No reviewable git diff files found.")))
                        .createdTime(LocalDateTime.now())
                        .build();
                agentRuntime.step(run, AgentStepType.REPORT, "未发现可审查 Git diff，已生成空报告。");
                agentRuntime.complete(run);
                result.setSteps(new ArrayList<>(run.getSteps()));
                persistWorkspaceResultSafely(userId, result);
                return result;
            }

            int maxIssuesPerFile = normalizeMaxIssues(request.getMaxIssuesPerFile());
            List<CodeReviewIssue> issues = new ArrayList<>();
            List<CodeReviewFileSummary> fileSummaries = new ArrayList<>();
            for (String file : changedFiles) {
                String diff = gitReviewService.getFileDiff(file);
                List<CodeReviewIssue> fileIssues = analyzeDiff(file, diff, maxIssuesPerFile);
                issues.addAll(fileIssues);
                fileSummaries.add(CodeReviewFileSummary.builder()
                        .relativePath(file)
                        .fileName(fileName(file))
                        .riskLevel(calculateRiskLevel(fileIssues))
                        .issueCount(fileIssues.size())
                        .truncated(diff != null && diff.contains("[diff truncated]"))
                        .summary(fileIssues.isEmpty() ? "未发现本地规则可确认的问题。" : "发现 " + fileIssues.size() + " 个 diff 问题。")
                        .build());
            }
            issues = issues.stream()
                    .sorted(Comparator.comparing(this::severityRank))
                    .toList();
            agentRuntime.step(run, AgentStepType.ANALYZE, "已完成 Git diff 本地规则分析。",
                    Map.of("reviewedFileCount", changedFiles.size(), "issueCount", issues.size()));

            String riskLevel = calculateRiskLevel(issues);
            agentRuntime.step(run, AgentStepType.VERIFY, "已校验 Git diff 审查结果。",
                    Map.of("riskLevel", riskLevel, "issueCount", issues.size()));

            CodeReviewWorkspaceResult result = CodeReviewWorkspaceResult.builder()
                    .success(true)
                    .runId(run.getRunId())
                    .sessionId(request.getSessionId())
                    .scopeType("GIT_DIFF")
                    .reviewedFileCount(changedFiles.size())
                    .summary("Git diff 审查完成，审查 " + changedFiles.size() + " 个变更文件，发现 " + issues.size() + " 个问题。")
                    .riskLevel(riskLevel)
                    .files(new ArrayList<>(fileSummaries))
                    .issues(new ArrayList<>(issues))
                    .testsToAdd(defaultGitDiffTests(issues))
                    .patchPlan(defaultPatchPlan(issues))
                    .warnings(new ArrayList<>(List.of("当前阶段只审查 Git diff 中的新增行，并使用本地保守规则；复杂语义问题将在后续模型化 diff 审查中增强。")))
                    .createdTime(LocalDateTime.now())
                    .build();
            agentRuntime.step(run, AgentStepType.REPORT, "已生成 Git diff 审查报告。",
                    Map.of("runId", run.getRunId()));
            agentRuntime.complete(run);
            result.setSteps(new ArrayList<>(run.getSteps()));
            persistWorkspaceResultSafely(userId, result);
            return result;
        } catch (Exception e) {
            agentRuntime.fail(run, e);
            throw e;
        }
    }

    public Map<String, Object> listGitChangedFiles(Long userId, String sessionId, Integer maxFiles) {
        validateSession(userId, sessionId);
        List<String> files = gitReviewService.getChangedFiles().stream()
                .filter(this::isReviewableSource)
                .limit(normalizeMaxFiles(maxFiles))
                .toList();
        List<Map<String, Object>> fileDetails = files.stream()
                .map(this::toGitChangedFileSummary)
                .toList();
        return Map.of(
                "success", true,
                "sessionId", sessionId,
                "files", files,
                "fileDetails", fileDetails,
                "count", files.size()
        );
    }

    public Map<String, Object> getGitFileDiffPreview(Long userId, String sessionId, String path) {
        validateSession(userId, sessionId);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String normalizedPath = path.trim().replace('\\', '/');
        if (!isReviewableSource(normalizedPath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is not reviewable source");
        }
        String diff = gitReviewService.getFileDiff(normalizedPath);
        Map<String, Object> summary = toGitChangedFileSummary(normalizedPath);
        return Map.of(
                "success", true,
                "sessionId", sessionId,
                "path", normalizedPath,
                "diffPreview", diff,
                "summary", summary
        );
    }

    public CodeReviewPatchSuggestionResult createPatchSuggestion(Long userId, CodeReviewPatchSuggestionRequest request) {
        validatePatchSuggestionRequest(userId, request);
        CodeReviewRequest reviewRequest = new CodeReviewRequest();
        reviewRequest.setSessionId(request.getSessionId());
        reviewRequest.setRelativePath(request.getRelativePath());
        reviewRequest.setFocus(request.getFocus());
        reviewRequest.setMaxIssues(request.getMaxIssues());
        CodeReviewResult review = reviewFile(userId, reviewRequest);

        AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, request.getSessionId());
        String suggestionPath = normalizeSuggestionPath(request.getSuggestionPath(), request.getRelativePath(), review.getRunId(), request.getIssueId());
        WorkspaceFileCreateRequest createRequest = new WorkspaceFileCreateRequest();
        createRequest.setRelativePath(suggestionPath);
        createRequest.setContent(buildPatchSuggestionMarkdown(review, request.getIssueId()));
        createRequest.setContentType("text/markdown");
        createRequest.setOverwrite(true);
        AgentWorkspaceFile file = workspaceService.createFile(userId, request.getSessionId(), createRequest);

        return CodeReviewPatchSuggestionResult.builder()
                .success(true)
                .runId(review.getRunId())
                .sessionId(request.getSessionId())
                .workspaceId(workspace.getId())
                .targetPath(request.getRelativePath())
                .issueId(request.getIssueId())
                .suggestionPath(suggestionPath)
                .suggestionFile(workspaceService.toFileMap(file))
                .review(review)
                .message("Patch suggestion file created in workspace. No source file was modified.")
                .createdTime(LocalDateTime.now())
                .build();
    }

    public CodeReviewPatchPreviewResult createPatchPreview(Long userId, CodeReviewPatchPreviewRequest request) {
        validatePatchPreviewRequest(userId, request);
        CodeReviewIssueRecord issue = null;
        if (request.getIssueId() != null) {
            issue = persistenceService.getIssue(userId, request.getIssueId());
            if (!request.getSessionId().equals(issue.getSessionId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "issue does not belong to current session");
            }
        }

        String relativePath = request.getRelativePath();
        if ((relativePath == null || relativePath.isBlank()) && issue != null) {
            relativePath = issue.getFilePath();
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }

        String suggestedPatch = request.getSuggestedPatch();
        if ((suggestedPatch == null || suggestedPatch.isBlank()) && issue != null) {
            suggestedPatch = issue.getSuggestedPatch();
        }

        AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(userId, request.getSessionId());
        Map<String, Object> file = workspaceService.readFileContent(userId, workspace.getId(), relativePath);
        String currentContent = String.valueOf(file.getOrDefault("content", ""));
        Integer expectedVersion = parseOptionalInteger(file.get("version"));

        PatchDraft draft = buildReplacementDraft(currentContent, suggestedPatch);
        if (!draft.applicable() && issue != null) {
            draft = tryModelFixDraft(issue, relativePath, currentContent, draft.warnings());
        }
        return CodeReviewPatchPreviewResult.builder()
                .success(true)
                .sessionId(request.getSessionId())
                .workspaceId(workspace.getId())
                .relativePath(relativePath)
                .issueId(request.getIssueId())
                .expectedVersion(expectedVersion)
                .applicable(draft.applicable())
                .currentContent(currentContent)
                .replacementContent(draft.replacementContent())
                .diffPreview(buildDiffPreview(relativePath, currentContent, draft.replacementContent()))
                .warnings(draft.warnings())
                .message(draft.applicable()
                        ? "Patch preview generated. Source file is not modified until apply-request is confirmed."
                        : "Unable to generate an applicable replacement draft from the current suggestion.")
                .build();
    }

    public CodeReviewApplyPatchResult requestApplyWorkspacePatch(Long userId, CodeReviewApplyPatchRequest request) {
        validateApplyPatchRequest(userId, request);
        var action = pendingActionService.requestApplyWorkspacePatch(
                userId,
                request.getSessionId(),
                request.getRelativePath(),
                request.getReplacementContent(),
                request.getExpectedVersion(),
                request.getReason()
        );
        return CodeReviewApplyPatchResult.builder()
                .success(true)
                .actionId(action.getId())
                .status(action.getStatus())
                .actionType(action.getActionType())
                .message("Patch application requires confirmation. Confirm via /api/chat/agent/actions/" + action.getId() + "/confirm.")
                .expireTime(action.getExpireTime())
                .build();
    }

    private void validateRequest(Long userId, CodeReviewRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
        if (request.getRelativePath() == null || request.getRelativePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }
    }

    private void validateWorkspaceRequest(Long userId, CodeReviewWorkspaceRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
    }

    private void validateGitDiffRequest(Long userId, CodeReviewGitDiffRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
    }

    private void validateSession(Long userId, String sessionId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!sessionId.startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
    }

    private void validatePatchSuggestionRequest(Long userId, CodeReviewPatchSuggestionRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
        if (request.getRelativePath() == null || request.getRelativePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }
    }

    private void validatePatchPreviewRequest(Long userId, CodeReviewPatchPreviewRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
        if ((request.getRelativePath() == null || request.getRelativePath().isBlank()) && request.getIssueId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath or issueId is required");
        }
    }

    private void validateApplyPatchRequest(Long userId, CodeReviewApplyPatchRequest request) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        if (!request.getSessionId().startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
        if (request.getRelativePath() == null || request.getRelativePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }
        if (request.getReplacementContent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "replacementContent is required");
        }
    }

    private List<CodeReviewIssue> analyzeDiff(String filePath, String diff, int maxIssues) {
        if (diff == null || diff.isBlank()) {
            return List.of();
        }
        List<CodeReviewIssue> issues = new ArrayList<>();
        int currentNewLine = 0;
        for (String rawLine : diff.split("\\R")) {
            Matcher matcher = HUNK_HEADER.matcher(rawLine);
            if (matcher.matches()) {
                currentNewLine = Integer.parseInt(matcher.group(1));
                continue;
            }
            if (rawLine.startsWith("+++") || rawLine.startsWith("---")) {
                continue;
            }
            if (rawLine.startsWith("+")) {
                String added = rawLine.substring(1);
                addDiffIssueIfNeeded(issues, filePath, currentNewLine <= 0 ? 1 : currentNewLine, added, maxIssues);
                currentNewLine++;
            } else if (rawLine.startsWith("-")) {
                // Removed line: does not advance new-file line number.
            } else if (currentNewLine > 0) {
                currentNewLine++;
            }
            if (issues.size() >= maxIssues) {
                break;
            }
        }
        return issues;
    }

    private List<String> selectGitChangedFiles(CodeReviewGitDiffRequest request) {
        List<String> changedFiles = gitReviewService.getChangedFiles();
        if (request.getRelativePaths() == null || request.getRelativePaths().isEmpty()) {
            return changedFiles;
        }
        Set<String> changedSet = new LinkedHashSet<>(changedFiles);
        return request.getRelativePaths().stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> path.trim().replace('\\', '/'))
                .filter(changedSet::contains)
                .toList();
    }

    private Map<String, Object> toGitChangedFileSummary(String path) {
        try {
            String diff = gitReviewService.getFileDiff(path);
            int additions = 0;
            int deletions = 0;
            for (String line : diff.split("\\R")) {
                if (line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (line.startsWith("+")) {
                    additions++;
                } else if (line.startsWith("-")) {
                    deletions++;
                }
            }
            return Map.of(
                    "path", path,
                    "additions", additions,
                    "deletions", deletions,
                    "truncated", diff.contains("[diff truncated]")
            );
        } catch (Exception e) {
            return Map.of(
                    "path", path,
                    "additions", 0,
                    "deletions", 0,
                    "truncated", false,
                    "error", e.getMessage() == null ? "failed to read diff" : e.getMessage()
            );
        }
    }

    private void addDiffIssueIfNeeded(List<CodeReviewIssue> issues, String filePath, int lineNo, String addedLine, int maxIssues) {
        if (issues.size() >= maxIssues) {
            return;
        }
        String lower = addedLine.toLowerCase(Locale.ROOT);
        if (addedLine.contains("printStackTrace()")) {
            issues.add(diffIssue(filePath, lineNo, CodeReviewIssueSeverity.MEDIUM, CodeReviewIssueCategory.RELIABILITY,
                    "新增代码直接打印异常堆栈",
                    "Git diff 新增行调用 printStackTrace()，缺少结构化日志和业务失败处理。",
                    addedLine,
                    "异常可能无法进入统一日志与告警链路，生产排障和补偿困难。",
                    "改为使用统一 logger，并结合业务语义返回错误、重试或抛出受控异常。"));
        } else if (addedLine.contains("System.out.println")) {
            issues.add(diffIssue(filePath, lineNo, CodeReviewIssueSeverity.LOW, CodeReviewIssueCategory.MAINTAINABILITY,
                    "新增代码使用 System.out.println",
                    "Git diff 新增行使用 System.out.println 输出日志。",
                    addedLine,
                    "生产环境日志缺少级别和上下文，不利于统一采集。",
                    "改为使用项目统一 logger。"));
        } else if (lower.contains("password") || lower.contains("secret") || lower.contains("apikey") || lower.contains("api_key")) {
            issues.add(diffIssue(filePath, lineNo, CodeReviewIssueSeverity.INFO, CodeReviewIssueCategory.SECURITY,
                    "新增代码包含敏感字段关键词",
                    "Git diff 新增行出现敏感字段关键词，需要确认是否存在泄露风险。",
                    addedLine,
                    "如果明文写入仓库、日志或响应，可能造成凭据泄露。",
                    "确认敏感值来自环境变量或安全存储，并避免输出到日志和前端。"));
        } else if (addedLine.contains("catch (Exception") && !addedLine.contains("throw")) {
            issues.add(diffIssue(filePath, lineNo, CodeReviewIssueSeverity.LOW, CodeReviewIssueCategory.RELIABILITY,
                    "新增宽泛异常捕获需要确认降级语义",
                    "Git diff 新增行捕获 Exception，需确认是否会吞掉真实失败。",
                    addedLine,
                    "调用方可能误判成功，导致状态不一致或排障困难。",
                    "补充明确日志、失败传播或业务降级策略。"));
        }
    }

    private CodeReviewIssue diffIssue(String filePath, int lineNo, CodeReviewIssueSeverity severity,
                                      CodeReviewIssueCategory category, String title, String description,
                                      String evidence, String impact, String recommendation) {
        return CodeReviewIssue.builder()
                .id("git_" + lineNo + "_" + category.name().toLowerCase(Locale.ROOT))
                .severity(severity)
                .category(category)
                .title(title)
                .description(description)
                .filePath(filePath)
                .startLine(lineNo)
                .endLine(lineNo)
                .evidence(evidence == null ? "" : evidence.trim())
                .impact(impact)
                .recommendation(recommendation)
                .patchable(!suggestPatch(title, evidence).isBlank())
                .suggestedPatch(suggestPatch(title, evidence))
                .build();
    }

    private List<String> defaultGitDiffTests(List<CodeReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of("运行变更文件相关测试，确认 Git diff 未引入回归。");
        }
        return List.of("为 Git diff 中发现的问题补充或更新对应测试，优先覆盖异常路径、权限边界和日志/降级行为。");
    }

    private List<String> selectReviewPaths(Long userId, AgentWorkspace workspace, List<AgentWorkspaceFile> files,
                                           CodeReviewWorkspaceRequest request) {
        int maxFiles = normalizeMaxFiles(request.getMaxFiles());
        if (request.getRelativePaths() != null && !request.getRelativePaths().isEmpty()) {
            return request.getRelativePaths().stream()
                    .filter(path -> path != null && !path.isBlank())
                    .map(String::trim)
                    .distinct()
                    .limit(maxFiles)
                    .toList();
        }
        String query = request.getQuery() == null ? "" : request.getQuery().trim();
        return files.stream()
                .filter(file -> isReviewableSource(file.getRelativePath()))
                .filter(file -> query.isBlank() || matchesQuery(userId, workspace.getId(), file, query))
                .map(AgentWorkspaceFile::getRelativePath)
                .limit(maxFiles)
                .toList();
    }

    private boolean matchesQuery(Long userId, Long workspaceId, AgentWorkspaceFile file, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        if (file.getRelativePath() != null && file.getRelativePath().toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
            return true;
        }
        try {
            Map<String, Object> content = workspaceService.readFileContent(userId, workspaceId, file.getRelativePath());
            return String.valueOf(content.getOrDefault("content", ""))
                    .toLowerCase(Locale.ROOT)
                    .contains(normalizedQuery);
        } catch (Exception e) {
            log.warn("Workspace search skipped unreadable file: {}, error={}", file.getRelativePath(), e.getMessage());
            return false;
        }
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

    private int normalizeMaxFiles(Integer requested) {
        if (requested == null || requested <= 0) {
            return 8;
        }
        return Math.min(requested, 20);
    }

    private String scopeType(CodeReviewWorkspaceRequest request) {
        if (request.getRelativePaths() != null && !request.getRelativePaths().isEmpty()) {
            return "EXPLICIT_FILES";
        }
        if (request.getQuery() != null && !request.getQuery().isBlank()) {
            return "QUERY";
        }
        return "WORKSPACE_SAMPLE";
    }

    private List<String> aggregateStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                unique.add(value.trim());
            }
            if (unique.size() >= 10) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    private ReviewInput buildReviewInput(CodeReviewRequest request, AgentWorkspace workspace, String relativePath,
                                         String fileName, String content, int maxIssues) {
        int maxChars = Math.max(1000, properties.getMaxFileChars());
        boolean truncated = content.length() > maxChars;
        String reviewContent = truncated ? content.substring(0, maxChars) : content;
        String numberedContent = withLineNumbers(reviewContent);
        return new ReviewInput(
                request.getSessionId(),
                workspace.getId(),
                relativePath,
                fileName,
                request.getFocus() == null ? "" : request.getFocus().trim(),
                content,
                reviewContent,
                numberedContent,
                truncated,
                maxIssues
        );
    }

    private CodeReviewResult tryModelReview(ReviewInput input) {
        ChatModel model = resolveModel();
        if (model == null) {
            return null;
        }
        try {
            List<Message> messages = List.of(
                    new SystemMessage(systemPrompt()),
                    new UserMessage(userPrompt(input))
            );
            String text = model.call(new Prompt(messages)).getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            CodeReviewResult result = objectMapper.readValue(extractJsonObject(text), CodeReviewResult.class);
            result.setModelUsed(modelName(model));
            return result;
        } catch (Exception e) {
            log.warn("Code review model result ignored: {}", e.getMessage());
            return null;
        }
    }

    private ChatModel resolveModel() {
        try {
            OpenAiChatModel openAi = openAiChatModelProvider.getIfAvailable();
            if (openAi != null && openAiApiKey != null && !openAiApiKey.isBlank()
                    && !"sk-placeholder".equals(openAiApiKey)) {
                return openAi;
            }
        } catch (Exception e) {
            log.warn("OpenAI/DeepSeek model unavailable for code review: {}", e.getMessage());
        }
        try {
            return ollamaChatModelProvider.getIfAvailable();
        } catch (Exception e) {
            log.warn("Ollama model unavailable for code review: {}", e.getMessage());
            return null;
        }
    }

    private String systemPrompt() {
        return """
                You are AI Studio's code review expert agent.
                Review only the provided real code. Do not invent files, line numbers, or behavior.
                Return ONLY a valid JSON object matching this shape:
                {
                  "summary": "short Chinese summary",
                  "riskLevel": "LOW|MEDIUM|HIGH",
                  "issues": [
                    {
                      "severity": "HIGH|MEDIUM|LOW|INFO",
                      "category": "BUG|SECURITY|RELIABILITY|PERFORMANCE|MAINTAINABILITY|TESTING|ARCHITECTURE|AI_ENGINEERING",
                      "title": "short issue title",
                      "description": "what is wrong",
                      "filePath": "provided path",
                      "startLine": 1,
                      "endLine": 1,
                      "evidence": "specific code evidence",
                      "impact": "runtime or engineering impact",
                      "recommendation": "minimal actionable fix",
                      "patchable": false,
                      "suggestedPatch": ""
                    }
                  ],
                  "testsToAdd": ["test suggestion"],
                  "patchPlan": ["minimal fix step"],
                  "warnings": []
                }
                Rules:
                - Every issue must cite the provided file path and a real line number from the numbered code.
                - If evidence is weak, mark severity INFO.
                - Prefer correctness, security, reliability, permission, transaction, concurrency, fallback, and testing issues.
                - Do not report style-only issues unless they affect maintainability.
                - Do not create more issues than requested.
                - Reply in Chinese inside JSON string values.
                """;
    }

    private String userPrompt(ReviewInput input) {
        return """
                Review target:
                - filePath: %s
                - fileName: %s
                - focus: %s
                - maxIssues: %d
                - truncated: %s

                Numbered code:
                %s
                """.formatted(
                input.relativePath(),
                input.fileName(),
                input.focus().isBlank() ? "general code review" : input.focus(),
                input.maxIssues(),
                input.truncated(),
                input.numberedContent()
        );
    }

    private CodeReviewResult localReview(ReviewInput input, String warning) {
        List<CodeReviewIssue> issues = new ArrayList<>();
        String[] lines = input.reviewContent().split("\\R", -1);
        for (int i = 0; i < lines.length && issues.size() < input.maxIssues(); i++) {
            String line = lines[i];
            String lower = line.toLowerCase(Locale.ROOT);
            int lineNo = i + 1;
            if (line.contains("printStackTrace()")) {
                issues.add(issue(input, lineNo, CodeReviewIssueSeverity.MEDIUM, CodeReviewIssueCategory.RELIABILITY,
                        "异常只打印堆栈，缺少业务化处理",
                        "代码调用 printStackTrace()，生产环境中容易丢失结构化日志和失败上下文。",
                        line,
                        "异常可能无法被监控系统准确捕获，问题定位和补偿困难。",
                        "改为结构化日志，并根据业务语义返回错误、重试或抛出受控异常。"));
            } else if (line.contains("System.out.println")) {
                issues.add(issue(input, lineNo, CodeReviewIssueSeverity.LOW, CodeReviewIssueCategory.MAINTAINABILITY,
                        "使用 System.out.println 输出日志",
                        "代码直接使用 System.out.println，缺少日志级别、上下文和统一格式。",
                        line,
                        "生产排障时难以按级别过滤，也不利于统一日志采集。",
                        "改为使用项目统一的 logger，并补充关键上下文。"));
            } else if (lower.contains("password") || lower.contains("secret") || lower.contains("apikey") || lower.contains("api_key")) {
                issues.add(issue(input, lineNo, CodeReviewIssueSeverity.INFO, CodeReviewIssueCategory.SECURITY,
                        "代码包含敏感字段关键词",
                        "当前行出现 password/secret/api key 相关关键词，需要确认是否存在敏感信息处理风险。",
                        line,
                        "如果明文写入日志、响应或仓库，可能造成凭据泄露。",
                        "确认敏感值来自环境变量或安全存储，避免日志输出和前端透传。"));
            } else if (line.contains("catch (Exception") && !line.contains("throw")) {
                issues.add(issue(input, lineNo, CodeReviewIssueSeverity.LOW, CodeReviewIssueCategory.RELIABILITY,
                        "宽泛异常捕获需要确认降级语义",
                        "代码捕获 Exception，需确认是否有明确的日志、降级或失败传播策略。",
                        line,
                        "宽泛捕获容易吞掉真实失败，导致调用方误判执行成功。",
                        "补充明确日志和业务降级；必要时抛出受控异常或返回失败状态。"));
            }
        }

        CodeReviewResult result = CodeReviewResult.builder()
                .success(true)
                .modelUsed("local-rules")
                .summary(issues.isEmpty()
                        ? "本地规则未发现明确高风险问题。建议接入模型审查以获得更完整的语义分析。"
                        : "本地规则发现 " + issues.size() + " 个需要关注的问题。")
                .riskLevel(calculateRiskLevel(issues))
                .issues(issues)
                .testsToAdd(defaultTests(input, issues))
                .patchPlan(defaultPatchPlan(issues))
                .warnings(new ArrayList<>(List.of(warning)))
                .build();
        if (input.truncated()) {
            result.getWarnings().add("文件内容超过审查字符上限，当前只审查前 " + input.reviewContent().length() + " 个字符。");
        }
        return result;
    }

    private CodeReviewIssue issue(ReviewInput input, int lineNo, CodeReviewIssueSeverity severity,
                                  CodeReviewIssueCategory category, String title, String description,
                                  String evidence, String impact, String recommendation) {
        return CodeReviewIssue.builder()
                .id("issue_" + lineNo + "_" + category.name().toLowerCase(Locale.ROOT))
                .severity(severity)
                .category(category)
                .title(title)
                .description(description)
                .filePath(input.relativePath())
                .startLine(lineNo)
                .endLine(lineNo)
                .evidence(evidence == null ? "" : evidence.trim())
                .impact(impact)
                .recommendation(recommendation)
                .patchable(!suggestPatch(title, evidence).isBlank())
                .suggestedPatch(suggestPatch(title, evidence))
                .build();
    }

    private String suggestPatch(String title, String evidence) {
        String safeEvidence = evidence == null ? "" : evidence.trim();
        if ((title != null && title.contains("printStackTrace")) || safeEvidence.contains("printStackTrace")) {
            return """
                    Suggested minimal patch:
                    - %s
                    + log.warn("Operation failed", e);

                    Note: adjust the log message and exception variable name to the surrounding method.
                    """.formatted(safeEvidence);
        }
        if (title != null && title.contains("System.out.println")) {
            return """
                    Suggested minimal patch:
                    - %s
                    + log.info("{}", value);

                    Note: replace value with the intended variable and prefer the existing project logger.
                    """.formatted(safeEvidence);
        }
        if (title != null && title.contains("敏感字段")) {
            return """
                    Suggested remediation:
                    - Do not hardcode or log sensitive values.
                    - Move credentials to environment variables or a secret manager.
                    - Mask sensitive fields in logs and API responses.
                    """;
        }
        if (title != null && title.contains("宽泛异常捕获")) {
            return """
                    Suggested remediation:
                    - Add structured logging with request/business context.
                    - Return a failed result, retry, or throw a controlled exception according to the caller contract.
                    - Avoid silently swallowing broad Exception.
                    """;
        }
        return "";
    }

    private PatchDraft buildReplacementDraft(String currentContent, String suggestedPatch) {
        List<String> warnings = new ArrayList<>();
        if (suggestedPatch == null || suggestedPatch.isBlank()) {
            warnings.add("No suggestedPatch was provided; replacementContent is identical to currentContent.");
            return new PatchDraft(false, currentContent, warnings);
        }

        String oldLine = null;
        String newLine = null;
        for (String line : suggestedPatch.split("\\R")) {
            String trimmed = line.trim();
            if (oldLine == null && trimmed.startsWith("- ")) {
                oldLine = trimmed.substring(2).trim();
            } else if (newLine == null && trimmed.startsWith("+ ")) {
                newLine = trimmed.substring(2).trim();
            }
        }
        if (oldLine == null || oldLine.isBlank() || newLine == null || newLine.isBlank()) {
            warnings.add("Suggested patch does not contain a simple '- old' and '+ new' replacement pair.");
            return new PatchDraft(false, currentContent, warnings);
        }

        String[] lines = currentContent.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals(oldLine)) {
                String indentation = lines[i].substring(0, lines[i].length() - lines[i].stripLeading().length());
                lines[i] = indentation + newLine;
                warnings.add("Generated replacementContent from a simple one-line suggestion. Review the diff before applying.");
                if (newLine.contains("log.") && !currentContent.contains(" log ") && !currentContent.contains("Logger")) {
                    warnings.add("The draft uses logger-style code; confirm the target file already has a logger or adjust before applying.");
                }
                return new PatchDraft(true, String.join("\n", lines), warnings);
            }
        }

        warnings.add("The line referenced by suggestedPatch was not found in the current file content.");
        return new PatchDraft(false, currentContent, warnings);
    }

    private PatchDraft tryModelFixDraft(CodeReviewIssueRecord issue, String relativePath, String currentContent,
                                        List<String> previousWarnings) {
        List<String> warnings = new ArrayList<>(previousWarnings == null ? List.of() : previousWarnings);
        ChatModel model = resolveModel();
        if (model == null) {
            warnings.add("No code-fix model is available; cannot generate full-file replacementContent.");
            return new PatchDraft(false, currentContent, warnings);
        }
        try {
            String system = """
                    You are AI Studio's code fix agent.
                    Produce a minimal safe fix for exactly one reviewed issue.
                    Return ONLY valid JSON:
                    {
                      "replacementContent": "complete full file content after the fix",
                      "summary": "short Chinese summary",
                      "warnings": ["risk or manual check"]
                    }
                    Rules:
                    - replacementContent must be the complete file, not a patch.
                    - Preserve unrelated code and formatting.
                    - Fix only the issue described by the user.
                    - Do not invent new files.
                    - If the fix is uncertain, still return the safest minimal draft and add warnings.
                    """;
            String user = """
                    File path: %s

                    Issue:
                    - id: %s
                    - severity: %s
                    - category: %s
                    - title: %s
                    - description: %s
                    - evidence: %s
                    - impact: %s
                    - recommendation: %s

                    Current full file content:
                    %s
                    """.formatted(
                    relativePath,
                    issue.getId(),
                    issue.getSeverity(),
                    issue.getCategory(),
                    issue.getTitle(),
                    issue.getDescription(),
                    issue.getEvidence(),
                    issue.getImpact(),
                    issue.getRecommendation(),
                    currentContent
            );
            String text = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))))
                    .getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                warnings.add("Model returned empty fix draft.");
                return new PatchDraft(false, currentContent, warnings);
            }
            Map<String, Object> parsed = objectMapper.readValue(extractJsonObject(text), new TypeReference<>() {
            });
            String replacementContent = String.valueOf(parsed.getOrDefault("replacementContent", ""));
            if (replacementContent.isBlank() || replacementContent.equals(currentContent)) {
                warnings.add("Model did not generate a changed replacementContent.");
                return new PatchDraft(false, currentContent, warnings);
            }
            Object modelWarnings = parsed.get("warnings");
            if (modelWarnings instanceof List<?> list) {
                list.stream()
                        .map(String::valueOf)
                        .filter(value -> !value.isBlank())
                        .forEach(warnings::add);
            }
            warnings.add("Agent generated a full-file fix draft. Review the diff before creating apply-request.");
            return new PatchDraft(true, replacementContent, warnings);
        } catch (Exception e) {
            log.warn("Code review issue fix draft ignored: {}", e.getMessage());
            warnings.add("Model fix generation failed: " + e.getMessage());
            return new PatchDraft(false, currentContent, warnings);
        }
    }

    private String buildDiffPreview(String relativePath, String currentContent, String replacementContent) {
        if (replacementContent == null || replacementContent.equals(currentContent)) {
            return "No content change generated for " + relativePath + ".";
        }
        String[] before = currentContent.split("\\R", -1);
        String[] after = replacementContent.split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        builder.append("--- ").append(relativePath).append("\n");
        builder.append("+++ ").append(relativePath).append(" (preview)\n");
        int max = Math.max(before.length, after.length);
        int emitted = 0;
        for (int i = 0; i < max && emitted < 20; i++) {
            String left = i < before.length ? before[i] : "";
            String right = i < after.length ? after[i] : "";
            if (!left.equals(right)) {
                builder.append("@@ line ").append(i + 1).append(" @@\n");
                if (i < before.length) {
                    builder.append("- ").append(left).append("\n");
                }
                if (i < after.length) {
                    builder.append("+ ").append(right).append("\n");
                }
                emitted++;
            }
        }
        if (emitted == 20 && max > 20) {
            builder.append("... diff preview truncated ...\n");
        }
        return builder.toString();
    }

    private Integer parseOptionalInteger(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value));
    }

    private String normalizeSuggestionPath(String requestedPath, String targetPath, String runId, Long issueId) {
        if (requestedPath != null && !requestedPath.isBlank()) {
            return requestedPath.trim();
        }
        String safeName = fileName(targetPath).replaceAll("[^A-Za-z0-9._-]", "_");
        if (issueId != null) {
            return "review/patch-suggestions/issue-" + issueId + "-" + safeName + "-" + runId + ".md";
        }
        return "review/patch-suggestions/" + safeName + "-" + runId + ".md";
    }

    private String buildPatchSuggestionMarkdown(CodeReviewResult review, Long issueId) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Code Review Patch Suggestions\n\n");
        builder.append("- Run ID: ").append(review.getRunId()).append("\n");
        if (issueId != null) {
            builder.append("- Issue ID: ").append(issueId).append("\n");
        }
        builder.append("- Target: ").append(review.getRelativePath()).append("\n");
        builder.append("- Risk: ").append(review.getRiskLevel()).append("\n");
        builder.append("- Source modified: false\n\n");
        builder.append("## Summary\n\n").append(review.getSummary()).append("\n\n");
        if (review.getIssues() == null || review.getIssues().isEmpty()) {
            builder.append("No patchable issues were found by the current review rules.\n");
            return builder.toString();
        }
        builder.append("## Issues and Suggestions\n\n");
        int index = 1;
        for (CodeReviewIssue issue : review.getIssues()) {
            builder.append("### ").append(index++).append(". ").append(issue.getTitle()).append("\n\n");
            builder.append("- Severity: ").append(issue.getSeverity()).append("\n");
            builder.append("- Category: ").append(issue.getCategory()).append("\n");
            builder.append("- Location: ").append(issue.getFilePath()).append(":").append(issue.getStartLine()).append("\n");
            builder.append("- Evidence: ").append(issue.getEvidence()).append("\n");
            builder.append("- Recommendation: ").append(issue.getRecommendation()).append("\n");
            if (Boolean.TRUE.equals(issue.getPatchable()) && issue.getSuggestedPatch() != null && !issue.getSuggestedPatch().isBlank()) {
                builder.append("\n");
                builder.append(issue.getSuggestedPatch()).append("\n");
            }
            builder.append("\n");
        }
        builder.append("## Safety\n\n");
        builder.append("This file is a patch suggestion only. No source file has been modified. Apply changes only after user confirmation.\n");
        return builder.toString();
    }

    private void persistFileResultSafely(Long userId, CodeReviewResult result) {
        try {
            persistenceService.saveFileResult(userId, result);
        } catch (Exception e) {
            log.warn("Code review file result persistence skipped: {}", e.getMessage());
        }
    }

    private void persistWorkspaceResultSafely(Long userId, CodeReviewWorkspaceResult result) {
        try {
            persistenceService.saveWorkspaceResult(userId, result);
        } catch (Exception e) {
            log.warn("Code review workspace result persistence skipped: {}", e.getMessage());
        }
    }

    private void normalizeResult(CodeReviewResult result, ReviewInput input) {
        if (result.getIssues() == null) {
            result.setIssues(new ArrayList<>());
        }
        int maxIssues = input.maxIssues();
        List<CodeReviewIssue> normalizedIssues = result.getIssues().stream()
                .limit(maxIssues)
                .peek(issue -> normalizeIssue(issue, input))
                .sorted(Comparator.comparing(this::severityRank))
                .toList();
        result.setIssues(new ArrayList<>(normalizedIssues));
        if (result.getSummary() == null || result.getSummary().isBlank()) {
            result.setSummary(result.getIssues().isEmpty() ? "未发现明确问题。" : "发现 " + result.getIssues().size() + " 个问题。");
        }
        if (result.getRiskLevel() == null || result.getRiskLevel().isBlank()) {
            result.setRiskLevel(calculateRiskLevel(result.getIssues()));
        }
        if (result.getTestsToAdd() == null) {
            result.setTestsToAdd(defaultTests(input, result.getIssues()));
        }
        if (result.getPatchPlan() == null) {
            result.setPatchPlan(defaultPatchPlan(result.getIssues()));
        }
        if (result.getWarnings() == null) {
            result.setWarnings(new ArrayList<>());
        }
        if (input.truncated() && result.getWarnings().stream().noneMatch(warning -> warning.contains("截断") || warning.contains("字符上限"))) {
            result.getWarnings().add("文件内容超过审查字符上限，当前只审查前 " + input.reviewContent().length() + " 个字符。");
        }
    }

    private void normalizeIssue(CodeReviewIssue issue, ReviewInput input) {
        if (issue.getId() == null || issue.getId().isBlank()) {
            issue.setId("issue_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        if (issue.getSeverity() == null) {
            issue.setSeverity(CodeReviewIssueSeverity.INFO);
        }
        if (issue.getCategory() == null) {
            issue.setCategory(CodeReviewIssueCategory.MAINTAINABILITY);
        }
        if (issue.getFilePath() == null || issue.getFilePath().isBlank()) {
            issue.setFilePath(input.relativePath());
        }
        if (issue.getStartLine() == null || issue.getStartLine() < 1) {
            issue.setStartLine(1);
        }
        if (issue.getEndLine() == null || issue.getEndLine() < issue.getStartLine()) {
            issue.setEndLine(issue.getStartLine());
        }
        if (issue.getPatchable() == null) {
            issue.setPatchable(false);
        }
        if (issue.getSuggestedPatch() == null) {
            issue.setSuggestedPatch("");
        }
    }

    private int severityRank(CodeReviewIssue issue) {
        return switch (issue.getSeverity() == null ? CodeReviewIssueSeverity.INFO : issue.getSeverity()) {
            case BLOCKER -> 0;
            case HIGH -> 1;
            case MEDIUM -> 2;
            case LOW -> 3;
            case INFO -> 4;
        };
    }

    private String calculateRiskLevel(List<CodeReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "LOW";
        }
        boolean high = issues.stream().anyMatch(issue -> issue.getSeverity() == CodeReviewIssueSeverity.BLOCKER
                || issue.getSeverity() == CodeReviewIssueSeverity.HIGH);
        if (high) {
            return "HIGH";
        }
        boolean medium = issues.stream().anyMatch(issue -> issue.getSeverity() == CodeReviewIssueSeverity.MEDIUM);
        return medium ? "MEDIUM" : "LOW";
    }

    private List<String> defaultTests(ReviewInput input, List<CodeReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of("补充或运行该文件相关的正常路径与异常路径测试，确认当前审查未覆盖的业务语义。");
        }
        return List.of("为 " + input.relativePath() + " 中发现的问题补充对应单元测试或集成测试，至少覆盖失败路径和权限/边界场景。");
    }

    private List<String> defaultPatchPlan(List<CodeReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
                .limit(5)
                .map(issue -> "修复：" + issue.getTitle())
                .toList();
    }

    private int normalizeMaxIssues(Integer requested) {
        int defaultMax = Math.max(1, properties.getDefaultMaxIssues());
        if (requested == null || requested <= 0) {
            return defaultMax;
        }
        return Math.min(requested, 20);
    }

    private String withLineNumbers(String content) {
        String[] lines = content.split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            builder.append(String.format(Locale.ROOT, "%4d | %s", i + 1, lines[i])).append('\n');
        }
        return builder.toString();
    }

    private String extractJsonObject(String text) {
        String trimmed = text.trim();
        String fence = Character.toString((char) 96).repeat(3);
        if (trimmed.startsWith(fence)) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf(fence);
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("model output does not contain JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private String modelName(ChatModel model) {
        if (model instanceof OpenAiChatModel) {
            return "openai-compatible";
        }
        if (model instanceof OllamaChatModel) {
            return "ollama";
        }
        return model.getClass().getSimpleName();
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private record ReviewInput(
            String sessionId,
            Long workspaceId,
            String relativePath,
            String fileName,
            String focus,
            String originalContent,
            String reviewContent,
            String numberedContent,
            boolean truncated,
            int maxIssues
    ) {
    }

    private record PatchDraft(
            boolean applicable,
            String replacementContent,
            List<String> warnings
    ) {
    }
}
