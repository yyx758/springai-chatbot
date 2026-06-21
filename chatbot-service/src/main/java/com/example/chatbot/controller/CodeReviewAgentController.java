package com.example.chatbot.controller;

import com.example.chatbot.agent.review.CodeReviewAgentService;
import com.example.chatbot.agent.review.CodeReviewApplyPatchRequest;
import com.example.chatbot.agent.review.CodeReviewApplyPatchResult;
import com.example.chatbot.agent.review.CodeReviewGitDiffRequest;
import com.example.chatbot.agent.review.CodeReviewIssueStatusUpdateRequest;
import com.example.chatbot.agent.review.CodeReviewPatchSuggestionRequest;
import com.example.chatbot.agent.review.CodeReviewPatchSuggestionResult;
import com.example.chatbot.agent.review.CodeReviewPersistenceService;
import com.example.chatbot.agent.review.CodeReviewPatchPreviewRequest;
import com.example.chatbot.agent.review.CodeReviewPatchPreviewResult;
import com.example.chatbot.agent.review.CodeReviewRequest;
import com.example.chatbot.agent.review.CodeReviewResult;
import com.example.chatbot.agent.review.CodeReviewWorkspaceRequest;
import com.example.chatbot.agent.review.CodeReviewWorkspaceResult;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/agent/review")
@RequiredArgsConstructor
public class CodeReviewAgentController {

    private final CodeReviewAgentService codeReviewAgentService;
    private final CodeReviewPersistenceService persistenceService;

    @PostMapping("/file")
    public CodeReviewResult reviewFile(@RequestBody CodeReviewRequest request, HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.reviewFile(resolveUserId(httpServletRequest), request);
    }

    @PostMapping("/workspace")
    public CodeReviewWorkspaceResult reviewWorkspace(@RequestBody CodeReviewWorkspaceRequest request,
                                                     HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.reviewWorkspace(resolveUserId(httpServletRequest), request);
    }

    @PostMapping("/git-diff")
    public CodeReviewWorkspaceResult reviewGitDiff(@RequestBody CodeReviewGitDiffRequest request,
                                                   HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.reviewGitDiff(resolveUserId(httpServletRequest), request);
    }

    @GetMapping("/git-diff/files")
    public Map<String, Object> listGitDiffFiles(@RequestParam String sessionId,
                                                @RequestParam(defaultValue = "50") Integer maxFiles,
                                                HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.listGitChangedFiles(resolveUserId(httpServletRequest), sessionId, maxFiles);
    }

    @GetMapping("/git-diff/file")
    public Map<String, Object> getGitDiffFile(@RequestParam String sessionId,
                                              @RequestParam String path,
                                              HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.getGitFileDiffPreview(resolveUserId(httpServletRequest), sessionId, path);
    }

    @PostMapping("/patch-suggestion")
    public CodeReviewPatchSuggestionResult createPatchSuggestion(@RequestBody CodeReviewPatchSuggestionRequest request,
                                                                 HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.createPatchSuggestion(resolveUserId(httpServletRequest), request);
    }

    @PostMapping("/patch/preview")
    public CodeReviewPatchPreviewResult createPatchPreview(@RequestBody CodeReviewPatchPreviewRequest request,
                                                           HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.createPatchPreview(resolveUserId(httpServletRequest), request);
    }

    @PostMapping("/patch/apply-request")
    public CodeReviewApplyPatchResult requestApplyPatch(@RequestBody CodeReviewApplyPatchRequest request,
                                                        HttpServletRequest httpServletRequest) {
        return codeReviewAgentService.requestApplyWorkspacePatch(resolveUserId(httpServletRequest), request);
    }

    @GetMapping("/runs")
    public Map<String, Object> listRuns(@RequestParam(required = false) String sessionId,
                                        @RequestParam(defaultValue = "20") Integer limit,
                                        HttpServletRequest httpServletRequest) {
        Long userId = resolveUserId(httpServletRequest);
        ensureSessionOwnedByUserIfPresent(userId, sessionId);
        return Map.of(
                "success", true,
                "runs", persistenceService.listRuns(userId, sessionId, limit == null ? 20 : limit).stream()
                        .map(persistenceService::toRunCard)
                        .toList()
        );
    }

    @GetMapping("/runs/{runId}/issues")
    public Map<String, Object> listRunIssues(@PathVariable String runId,
                                             @RequestParam(required = false) String status,
                                             HttpServletRequest httpServletRequest) {
        Long userId = resolveUserId(httpServletRequest);
        return Map.of(
                "success", true,
                "runId", runId,
                "status", status == null || status.isBlank() ? "ALL" : status.trim().toUpperCase(),
                "issues", persistenceService.listIssues(userId, runId, status).stream()
                        .map(persistenceService::toIssueCard)
                        .toList()
        );
    }

    @PostMapping("/issues/{issueId}/status")
    public Map<String, Object> updateIssueStatus(@PathVariable Long issueId,
                                                 @RequestBody CodeReviewIssueStatusUpdateRequest request,
                                                 HttpServletRequest httpServletRequest) {
        Long userId = resolveUserId(httpServletRequest);
        return Map.of(
                "success", true,
                "issue", persistenceService.toIssueCard(
                        persistenceService.updateIssueStatus(userId, issueId, request == null ? null : request.getStatus())
                )
        );
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }

    private void ensureSessionOwnedByUserIfPresent(Long userId, String sessionId) {
        if (sessionId != null && !sessionId.isBlank() && !sessionId.startsWith(userId + "_")) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
    }
}
