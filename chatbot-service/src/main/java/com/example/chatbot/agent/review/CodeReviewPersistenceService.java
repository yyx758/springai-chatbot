package com.example.chatbot.agent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.entity.CodeReviewIssueRecord;
import com.example.chatbot.entity.CodeReviewRunRecord;
import com.example.chatbot.mapper.CodeReviewIssueMapper;
import com.example.chatbot.mapper.CodeReviewRunMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CodeReviewPersistenceService {

    private static final Set<String> ALLOWED_ISSUE_STATUSES = Set.of("OPEN", "ACCEPTED", "IGNORED", "FIXED");

    private final CodeReviewRunMapper runMapper;
    private final CodeReviewIssueMapper issueMapper;

    @Transactional
    public void saveFileResult(Long userId, CodeReviewResult result) {
        if (result == null || result.getRunId() == null || result.getSessionId() == null) {
            return;
        }
        saveRun(userId, CodeReviewRunRecord.builder()
                .runId(result.getRunId())
                .userId(userId)
                .sessionId(result.getSessionId())
                .scopeType("FILE")
                .targetPath(result.getRelativePath())
                .reviewedFileCount(1)
                .riskLevel(nullToDefault(result.getRiskLevel(), "LOW"))
                .summary(result.getSummary())
                .status("COMPLETED")
                .createdTime(LocalDateTime.now())
                .build());
        saveIssues(userId, result.getSessionId(), result.getRunId(), result.getIssues());
    }

    @Transactional
    public void saveWorkspaceResult(Long userId, CodeReviewWorkspaceResult result) {
        if (result == null || result.getRunId() == null || result.getSessionId() == null) {
            return;
        }
        saveRun(userId, CodeReviewRunRecord.builder()
                .runId(result.getRunId())
                .userId(userId)
                .sessionId(result.getSessionId())
                .scopeType(nullToDefault(result.getScopeType(), "WORKSPACE"))
                .targetPath("")
                .reviewedFileCount(result.getReviewedFileCount())
                .riskLevel(nullToDefault(result.getRiskLevel(), "LOW"))
                .summary(result.getSummary())
                .status("COMPLETED")
                .createdTime(LocalDateTime.now())
                .build());
        saveIssues(userId, result.getSessionId(), result.getRunId(), result.getIssues());
    }

    public List<CodeReviewRunRecord> listRuns(Long userId, String sessionId, int limit) {
        LambdaQueryWrapper<CodeReviewRunRecord> wrapper = new LambdaQueryWrapper<CodeReviewRunRecord>()
                .eq(CodeReviewRunRecord::getUserId, userId)
                .orderByDesc(CodeReviewRunRecord::getCreatedTime)
                .last("LIMIT " + normalizeLimit(limit));
        if (sessionId != null && !sessionId.isBlank()) {
            wrapper.eq(CodeReviewRunRecord::getSessionId, sessionId.trim());
        }
        return runMapper.selectList(wrapper);
    }

    public List<CodeReviewIssueRecord> listIssues(Long userId, String runId) {
        return listIssues(userId, runId, null);
    }

    public List<CodeReviewIssueRecord> listIssues(Long userId, String runId, String status) {
        String normalizedStatus = normalizeOptionalIssueStatus(status);
        LambdaQueryWrapper<CodeReviewIssueRecord> wrapper = new LambdaQueryWrapper<CodeReviewIssueRecord>()
                .eq(CodeReviewIssueRecord::getUserId, userId)
                .eq(CodeReviewIssueRecord::getRunId, runId)
                .orderByAsc(CodeReviewIssueRecord::getId);
        if (normalizedStatus != null) {
            wrapper.eq(CodeReviewIssueRecord::getStatus, normalizedStatus);
        }
        return issueMapper.selectList(wrapper);
    }

    public CodeReviewIssueRecord getIssue(Long userId, Long issueId) {
        CodeReviewIssueRecord issue = issueMapper.selectOne(new LambdaQueryWrapper<CodeReviewIssueRecord>()
                .eq(CodeReviewIssueRecord::getUserId, userId)
                .eq(CodeReviewIssueRecord::getId, issueId)
                .last("LIMIT 1"));
        if (issue == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "code review issue not found");
        }
        return issue;
    }

    public CodeReviewIssueRecord updateIssueStatus(Long userId, Long issueId, String status) {
        String normalizedStatus = normalizeIssueStatus(status);
        CodeReviewIssueRecord issue = getIssue(userId, issueId);
        issue.setStatus(normalizedStatus);
        issue.setUpdatedTime(LocalDateTime.now());
        issueMapper.updateById(issue);
        return issue;
    }

    public Map<String, Object> toRunCard(CodeReviewRunRecord run) {
        return Map.of(
                "runId", run.getRunId(),
                "sessionId", run.getSessionId(),
                "scopeType", run.getScopeType(),
                "targetPath", run.getTargetPath() == null ? "" : run.getTargetPath(),
                "reviewedFileCount", run.getReviewedFileCount() == null ? 0 : run.getReviewedFileCount(),
                "riskLevel", run.getRiskLevel(),
                "summary", run.getSummary() == null ? "" : run.getSummary(),
                "status", run.getStatus(),
                "createdTime", run.getCreatedTime()
        );
    }

    public Map<String, Object> toIssueCard(CodeReviewIssueRecord issue) {
        return Map.ofEntries(
                Map.entry("id", issue.getId() == null ? 0L : issue.getId()),
                Map.entry("runId", issue.getRunId()),
                Map.entry("severity", issue.getSeverity()),
                Map.entry("category", issue.getCategory()),
                Map.entry("title", issue.getTitle()),
                Map.entry("description", issue.getDescription() == null ? "" : issue.getDescription()),
                Map.entry("filePath", issue.getFilePath() == null ? "" : issue.getFilePath()),
                Map.entry("startLine", issue.getStartLine() == null ? 0 : issue.getStartLine()),
                Map.entry("endLine", issue.getEndLine() == null ? 0 : issue.getEndLine()),
                Map.entry("evidence", issue.getEvidence() == null ? "" : issue.getEvidence()),
                Map.entry("impact", issue.getImpact() == null ? "" : issue.getImpact()),
                Map.entry("recommendation", issue.getRecommendation() == null ? "" : issue.getRecommendation()),
                Map.entry("patchable", Boolean.TRUE.equals(issue.getPatchable())),
                Map.entry("suggestedPatch", issue.getSuggestedPatch() == null ? "" : issue.getSuggestedPatch()),
                Map.entry("status", issue.getStatus()),
                Map.entry("createdTime", issue.getCreatedTime() == null ? "" : issue.getCreatedTime())
        );
    }

    private void saveRun(Long userId, CodeReviewRunRecord record) {
        CodeReviewRunRecord existing = runMapper.selectOne(new LambdaQueryWrapper<CodeReviewRunRecord>()
                .eq(CodeReviewRunRecord::getUserId, userId)
                .eq(CodeReviewRunRecord::getRunId, record.getRunId())
                .last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        runMapper.insert(record);
    }

    private void saveIssues(Long userId, String sessionId, String runId, List<CodeReviewIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        for (CodeReviewIssue issue : issues) {
            CodeReviewIssueRecord record = CodeReviewIssueRecord.builder()
                    .runId(runId)
                    .userId(userId)
                    .sessionId(sessionId)
                    .severity(issue.getSeverity() == null ? "INFO" : issue.getSeverity().name())
                    .category(issue.getCategory() == null ? "MAINTAINABILITY" : issue.getCategory().name())
                    .title(limit(issue.getTitle(), 255))
                    .description(issue.getDescription())
                    .filePath(issue.getFilePath())
                    .startLine(issue.getStartLine())
                    .endLine(issue.getEndLine())
                    .evidence(issue.getEvidence())
                    .impact(issue.getImpact())
                    .recommendation(issue.getRecommendation())
                    .patchable(Boolean.TRUE.equals(issue.getPatchable()))
                    .suggestedPatch(issue.getSuggestedPatch())
                    .status("OPEN")
                    .createdTime(LocalDateTime.now())
                    .build();
            issueMapper.insert(record);
        }
    }

    private String nullToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 100);
    }

    private String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String normalizeIssueStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status is required");
        }
        String normalized = status.trim().toUpperCase();
        if (!ALLOWED_ISSUE_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported issue status: " + status);
        }
        return normalized;
    }

    private String normalizeOptionalIssueStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        return normalizeIssueStatus(status);
    }
}
