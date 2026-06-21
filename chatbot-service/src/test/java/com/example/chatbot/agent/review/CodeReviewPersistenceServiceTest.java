package com.example.chatbot.agent.review;

import com.example.chatbot.entity.CodeReviewIssueRecord;
import com.example.chatbot.entity.CodeReviewRunRecord;
import com.example.chatbot.mapper.CodeReviewIssueMapper;
import com.example.chatbot.mapper.CodeReviewRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewPersistenceServiceTest {

    @Mock
    private CodeReviewRunMapper runMapper;

    @Mock
    private CodeReviewIssueMapper issueMapper;

    private CodeReviewPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new CodeReviewPersistenceService(runMapper, issueMapper);
    }

    @Test
    @DisplayName("Save file review result persists run and issue records")
    void saveFileResultPersistsRunAndIssues() {
        CodeReviewIssue issue = CodeReviewIssue.builder()
                .severity(CodeReviewIssueSeverity.MEDIUM)
                .category(CodeReviewIssueCategory.RELIABILITY)
                .title("异常处理不完整")
                .description("desc")
                .filePath("src/App.java")
                .startLine(3)
                .endLine(3)
                .evidence("e.printStackTrace();")
                .impact("impact")
                .recommendation("recommendation")
                .patchable(true)
                .suggestedPatch("patch")
                .build();
        CodeReviewResult result = CodeReviewResult.builder()
                .runId("run_1")
                .sessionId("7_session")
                .relativePath("src/App.java")
                .riskLevel("MEDIUM")
                .summary("summary")
                .issues(List.of(issue))
                .build();

        service.saveFileResult(7L, result);

        ArgumentCaptor<CodeReviewRunRecord> runCaptor = ArgumentCaptor.forClass(CodeReviewRunRecord.class);
        verify(runMapper).insert(runCaptor.capture());
        assertEquals("run_1", runCaptor.getValue().getRunId());
        assertEquals("FILE", runCaptor.getValue().getScopeType());
        assertEquals("src/App.java", runCaptor.getValue().getTargetPath());

        ArgumentCaptor<CodeReviewIssueRecord> issueCaptor = ArgumentCaptor.forClass(CodeReviewIssueRecord.class);
        verify(issueMapper).insert(issueCaptor.capture());
        assertEquals("run_1", issueCaptor.getValue().getRunId());
        assertEquals("MEDIUM", issueCaptor.getValue().getSeverity());
        assertTrue(issueCaptor.getValue().getPatchable());
    }

    @Test
    @DisplayName("Issue records are converted to card-ready map")
    void issueRecordToCard() {
        CodeReviewIssueRecord issue = CodeReviewIssueRecord.builder()
                .id(1L)
                .runId("run_1")
                .severity("LOW")
                .category("MAINTAINABILITY")
                .title("title")
                .filePath("src/App.java")
                .startLine(2)
                .endLine(2)
                .patchable(true)
                .status("OPEN")
                .build();

        Map<String, Object> card = service.toIssueCard(issue);

        assertEquals("run_1", card.get("runId"));
        assertEquals("LOW", card.get("severity"));
        assertEquals("src/App.java", card.get("filePath"));
        assertEquals(true, card.get("patchable"));
    }

    @Test
    @DisplayName("Update issue status validates and persists allowed status")
    void updateIssueStatusPersistsAllowedStatus() {
        CodeReviewIssueRecord issue = CodeReviewIssueRecord.builder()
                .id(12L)
                .userId(7L)
                .runId("run_1")
                .status("OPEN")
                .build();
        when(issueMapper.selectOne(any())).thenReturn(issue);

        CodeReviewIssueRecord updated = service.updateIssueStatus(7L, 12L, "fixed");

        assertEquals("FIXED", updated.getStatus());
        verify(issueMapper).updateById(issue);
    }

    @Test
    @DisplayName("List issues rejects unsupported status filter")
    void listIssuesRejectsUnsupportedStatusFilter() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.listIssues(7L, "run_1", "invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }
}
