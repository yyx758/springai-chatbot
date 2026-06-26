package com.example.chatbot.agent.review.conversation;

import com.example.chatbot.agent.review.CodeReviewAgentService;
import com.example.chatbot.agent.review.CodeReviewGitDiffRequest;
import com.example.chatbot.agent.review.CodeReviewIssue;
import com.example.chatbot.agent.review.CodeReviewIssueCategory;
import com.example.chatbot.agent.review.CodeReviewIssueSeverity;
import com.example.chatbot.agent.review.CodeReviewPersistenceService;
import com.example.chatbot.agent.review.CodeReviewWorkspaceResult;
import com.example.chatbot.entity.CodeReviewRunRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewConversationOrchestratorTest {

    @Mock
    private CodeReviewAgentService codeReviewAgentService;

    @Mock
    private CodeReviewPersistenceService persistenceService;

    private CodeReviewConversationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new CodeReviewConversationOrchestrator(
                new RuleBasedCodeReviewIntentClassifier(),
                codeReviewAgentService,
                persistenceService
        );
    }

    @Test
    void handleGitDiffReviewCallsReviewServiceAndBindsActiveRun() {
        when(codeReviewAgentService.reviewGitDiff(eq(7L), any(CodeReviewGitDiffRequest.class)))
                .thenReturn(CodeReviewWorkspaceResult.builder()
                        .success(true)
                        .runId("run-1")
                        .sessionId("7_session")
                        .scopeType("GIT_DIFF")
                        .reviewedFileCount(1)
                        .riskLevel("HIGH")
                        .summary("Git diff review completed")
                        .issues(List.of(CodeReviewIssue.builder()
                                .severity(CodeReviewIssueSeverity.HIGH)
                                .category(CodeReviewIssueCategory.SECURITY)
                                .title("Missing authorization")
                                .filePath("src/main/java/App.java")
                                .build()))
                        .build());

        Optional<CodeReviewConversationResponse> response = orchestrator.handle(
                7L,
                "7_session",
                "帮我审查这次 Git diff，重点看权限问题"
        );

        assertTrue(response.isPresent());
        assertEquals(CodeReviewIntentType.REVIEW_GIT_DIFF, response.get().getType());
        assertEquals("run-1", response.get().getRunId());
        assertEquals(1, response.get().getIssueCount());
        assertEquals("run-1", orchestrator.getContext(7L, "7_session").getActiveReviewRunId());

        ArgumentCaptor<CodeReviewGitDiffRequest> requestCaptor = ArgumentCaptor.forClass(CodeReviewGitDiffRequest.class);
        verify(codeReviewAgentService).reviewGitDiff(eq(7L), requestCaptor.capture());
        assertEquals("7_session", requestCaptor.getValue().getSessionId());
        assertEquals("权限/鉴权", requestCaptor.getValue().getFocus());
    }

    @Test
    void handleGeneralChatReturnsEmpty() {
        Optional<CodeReviewConversationResponse> response = orchestrator.handle(
                7L,
                "7_session",
                "讲讲 Spring Bean 生命周期"
        );

        assertTrue(response.isEmpty());
    }

    @Test
    void setActiveRunRejectsRunFromOtherSessionOrUser() {
        when(persistenceService.listRuns(7L, "7_session", 100)).thenReturn(List.of(
                CodeReviewRunRecord.builder().runId("run-owned").userId(7L).sessionId("7_session").build()
        ));

        assertThrows(ResponseStatusException.class,
                () -> orchestrator.setActiveReviewRun(7L, "7_session", "run-other"));
    }
}
