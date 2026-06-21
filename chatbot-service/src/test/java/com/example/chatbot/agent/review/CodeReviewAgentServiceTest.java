package com.example.chatbot.agent.review;

import com.example.chatbot.agent.AgentPendingActionService;
import com.example.chatbot.agent.runtime.AgentRuntime;
import com.example.chatbot.agent.runtime.AgentRuntimeProperties;
import com.example.chatbot.agent.runtime.AgentStepType;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.AgentPendingAction;
import com.example.chatbot.entity.CodeReviewIssueRecord;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeReviewAgentServiceTest {

    @Mock
    private AgentWorkspaceService workspaceService;

    @Mock
    private ObjectProvider<OpenAiChatModel> openAiProvider;

    @Mock
    private ObjectProvider<OllamaChatModel> ollamaProvider;

    @Mock
    private GitReviewService gitReviewService;

    @Mock
    private AgentPendingActionService pendingActionService;

    @Mock
    private CodeReviewPersistenceService persistenceService;

    private CodeReviewAgentService service;

    @BeforeEach
    void setUp() {
        CodeReviewProperties properties = new CodeReviewProperties();
        properties.setEnabled(true);
        properties.setMaxFileChars(30000);
        properties.setDefaultMaxIssues(8);
        service = new CodeReviewAgentService(
                workspaceService,
                properties,
                new AgentRuntime(new AgentRuntimeProperties()),
                gitReviewService,
                pendingActionService,
                persistenceService,
                new ObjectMapper(),
                openAiProvider,
                ollamaProvider
        );
    }

    @Test
    @DisplayName("Review file falls back to local conservative rules when no model is available")
    void reviewFileUsesLocalRulesWhenModelUnavailable() {
        CodeReviewRequest request = request("7_session", "src/main/java/App.java", null, null);
        when(openAiProvider.getIfAvailable()).thenReturn(null);
        when(ollamaProvider.getIfAvailable()).thenReturn(null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(Map.of(
                        "relativePath", "src/main/java/App.java",
                        "fileName", "App.java",
                        "content", """
                                class App {
                                  void run() {
                                    try {
                                      risky();
                                    } catch (Exception e) {
                                      e.printStackTrace();
                                    }
                                  }
                                }
                                """
                ));

        CodeReviewResult result = service.reviewFile(7L, request);

        assertTrue(result.isSuccess());
        assertEquals("local-rules", result.getModelUsed());
        assertEquals("MEDIUM", result.getRiskLevel());
        assertFalse(result.getIssues().isEmpty());
        assertEquals("src/main/java/App.java", result.getIssues().get(0).getFilePath());
        assertEquals(CodeReviewIssueCategory.RELIABILITY, result.getIssues().get(0).getCategory());
        assertTrue(result.getIssues().get(0).getPatchable());
        assertFalse(result.getIssues().get(0).getSuggestedPatch().isBlank());
        assertFalse(result.getWarnings().isEmpty());
        assertEquals(5, result.getSteps().size());
        assertEquals(AgentStepType.CLASSIFY_SCOPE, result.getSteps().get(0).getType());
        assertEquals(AgentStepType.REPORT, result.getSteps().get(4).getType());
        verify(persistenceService).saveFileResult(eq(7L), any(CodeReviewResult.class));
    }

    @Test
    @DisplayName("Review rejects sessions that do not belong to the authenticated user")
    void rejectsForeignSession() {
        CodeReviewRequest request = request("8_session", "src/main/java/App.java", null, null);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.reviewFile(7L, request));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(workspaceService);
    }

    @Test
    @DisplayName("Review respects requested max issue limit")
    void respectsMaxIssueLimit() {
        CodeReviewRequest request = request("7_session", "src/main/java/App.java", null, 1);
        when(openAiProvider.getIfAvailable()).thenReturn(null);
        when(ollamaProvider.getIfAvailable()).thenReturn(null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(Map.of(
                        "relativePath", "src/main/java/App.java",
                        "fileName", "App.java",
                        "content", """
                                class App {
                                  void a() { System.out.println("debug"); }
                                  void b() { String password = "demo"; }
                                }
                                """
                ));

        CodeReviewResult result = service.reviewFile(7L, request);

        assertEquals(1, result.getIssues().size());
    }

    @Test
    @DisplayName("Review propagates workspace file not found errors")
    void propagatesWorkspaceFileNotFound() {
        CodeReviewRequest request = request("7_session", "src/main/java/Missing.java", null, null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.readFileContent(anyLong(), anyLong(), anyString()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file not found"));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.reviewFile(7L, request));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    @DisplayName("Workspace review aggregates explicit file review results")
    void reviewWorkspaceAggregatesExplicitFiles() {
        CodeReviewWorkspaceRequest request = new CodeReviewWorkspaceRequest();
        request.setSessionId("7_session");
        request.setRelativePaths(List.of("src/main/java/App.java", "src/main/java/Config.java"));
        request.setMaxIssuesPerFile(2);

        when(openAiProvider.getIfAvailable()).thenReturn(null);
        when(ollamaProvider.getIfAvailable()).thenReturn(null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.listFiles(7L, 10L)).thenReturn(List.of(
                workspaceFile("src/main/java/App.java"),
                workspaceFile("src/main/java/Config.java")
        ));
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(fileContent("src/main/java/App.java", """
                        class App {
                          void run() { System.out.println("debug"); }
                        }
                        """));
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/Config.java"))
                .thenReturn(fileContent("src/main/java/Config.java", """
                        class Config {
                          String password = "demo";
                        }
                        """));

        CodeReviewWorkspaceResult result = service.reviewWorkspace(7L, request);

        assertTrue(result.isSuccess());
        assertEquals("EXPLICIT_FILES", result.getScopeType());
        assertEquals(2, result.getReviewedFileCount());
        assertEquals(2, result.getFiles().size());
        assertEquals(2, result.getIssues().size());
        assertEquals(5, result.getSteps().size());
        assertEquals(AgentStepType.COLLECT_CONTEXT, result.getSteps().get(1).getType());
        verify(persistenceService).saveWorkspaceResult(eq(7L), any(CodeReviewWorkspaceResult.class));
    }

    @Test
    @DisplayName("Patch suggestion creates a markdown file in workspace without applying source changes")
    void createPatchSuggestionWritesMarkdownFile() {
        CodeReviewPatchSuggestionRequest request = new CodeReviewPatchSuggestionRequest();
        request.setSessionId("7_session");
        request.setRelativePath("src/main/java/App.java");
        request.setIssueId(12L);
        request.setMaxIssues(3);

        when(openAiProvider.getIfAvailable()).thenReturn(null);
        when(ollamaProvider.getIfAvailable()).thenReturn(null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(fileContent("src/main/java/App.java", """
                        class App {
                          void run(Exception e) { e.printStackTrace(); }
                        }
                        """));
        AgentWorkspaceFile suggestionFile = workspaceFile("review/patch-suggestions/App.java-review.md");
        when(workspaceService.createFile(anyLong(), anyString(), any())).thenReturn(suggestionFile);
        when(workspaceService.toFileMap(suggestionFile)).thenReturn(Map.of(
                "relativePath", "review/patch-suggestions/App.java-review.md",
                "fileName", "App.java-review.md"
        ));

        CodeReviewPatchSuggestionResult result = service.createPatchSuggestion(7L, request);

        assertTrue(result.isSuccess());
        assertEquals("src/main/java/App.java", result.getTargetPath());
        assertEquals(12L, result.getIssueId());
        assertTrue(result.getSuggestionPath().contains("issue-12-"));
        assertTrue(result.getSuggestionPath().startsWith("review/patch-suggestions/"));
        assertEquals("review/patch-suggestions/App.java-review.md", result.getSuggestionFile().get("relativePath"));
        assertFalse(result.getReview().getIssues().isEmpty());
        verify(workspaceService).createFile(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Apply patch request creates pending action instead of updating source immediately")
    void requestApplyPatchCreatesPendingAction() {
        CodeReviewApplyPatchRequest request = new CodeReviewApplyPatchRequest();
        request.setSessionId("7_session");
        request.setRelativePath("src/main/java/App.java");
        request.setReplacementContent("class App {}");
        request.setExpectedVersion(2);
        request.setReason("review fix");
        AgentPendingAction action = AgentPendingAction.builder()
                .id(88L)
                .userId(7L)
                .sessionId("7_session")
                .actionType(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH)
                .status("PENDING")
                .expireTime(java.time.LocalDateTime.now().plusMinutes(10))
                .build();
        when(pendingActionService.requestApplyWorkspacePatch(
                7L,
                "7_session",
                "src/main/java/App.java",
                "class App {}",
                2,
                "review fix"
        )).thenReturn(action);

        CodeReviewApplyPatchResult result = service.requestApplyWorkspacePatch(7L, request);

        assertTrue(result.isSuccess());
        assertEquals(88L, result.getActionId());
        assertEquals("PENDING", result.getStatus());
        assertEquals(AgentPendingActionService.ACTION_APPLY_WORKSPACE_PATCH, result.getActionType());
        verifyNoInteractions(workspaceService);
    }

    @Test
    @DisplayName("Patch preview creates replacement content without applying source changes")
    void createPatchPreviewBuildsReplacementContent() {
        CodeReviewPatchPreviewRequest request = new CodeReviewPatchPreviewRequest();
        request.setSessionId("7_session");
        request.setRelativePath("src/main/java/App.java");
        request.setIssueId(12L);
        request.setSuggestedPatch("""
                Suggested minimal patch:
                - e.printStackTrace();
                + log.warn("Operation failed", e);
                """);
        when(persistenceService.getIssue(7L, 12L)).thenReturn(CodeReviewIssueRecord.builder()
                .id(12L)
                .userId(7L)
                .sessionId("7_session")
                .filePath("src/main/java/App.java")
                .suggestedPatch(request.getSuggestedPatch())
                .build());
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(Map.of(
                        "relativePath", "src/main/java/App.java",
                        "fileName", "App.java",
                        "version", 3,
                        "content", """
                                class App {
                                  void run(Exception e) {
                                    e.printStackTrace();
                                  }
                                }
                                """
                ));

        CodeReviewPatchPreviewResult result = service.createPatchPreview(7L, request);

        assertTrue(result.isSuccess());
        assertTrue(result.isApplicable());
        assertEquals(3, result.getExpectedVersion());
        assertEquals(12L, result.getIssueId());
        assertTrue(result.getReplacementContent().contains("log.warn(\"Operation failed\", e);"));
        assertTrue(result.getDiffPreview().contains("-     e.printStackTrace();"));
        assertTrue(result.getDiffPreview().contains("+     log.warn(\"Operation failed\", e);"));
        verifyNoInteractions(pendingActionService);
    }

    @Test
    @DisplayName("Workspace review can select files by query")
    void reviewWorkspaceSelectsFilesByQuery() {
        CodeReviewWorkspaceRequest request = new CodeReviewWorkspaceRequest();
        request.setSessionId("7_session");
        request.setQuery("printStackTrace");
        request.setMaxFiles(5);
        request.setMaxIssuesPerFile(3);

        when(openAiProvider.getIfAvailable()).thenReturn(null);
        when(ollamaProvider.getIfAvailable()).thenReturn(null);
        when(workspaceService.getOrCreateWorkspace(7L, "7_session"))
                .thenReturn(AgentWorkspace.builder().id(10L).userId(7L).sessionId("7_session").build());
        when(workspaceService.listFiles(7L, 10L)).thenReturn(List.of(
                workspaceFile("src/main/java/App.java"),
                workspaceFile("src/main/java/Other.java")
        ));
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/App.java"))
                .thenReturn(fileContent("src/main/java/App.java", """
                        class App {
                          void run(Exception e) { e.printStackTrace(); }
                        }
                        """));
        when(workspaceService.readFileContent(7L, 10L, "src/main/java/Other.java"))
                .thenReturn(fileContent("src/main/java/Other.java", """
                        class Other {
                          void ok() {}
                        }
                        """));

        CodeReviewWorkspaceResult result = service.reviewWorkspace(7L, request);

        assertTrue(result.isSuccess());
        assertEquals("QUERY", result.getScopeType());
        assertEquals(1, result.getReviewedFileCount());
        assertEquals("src/main/java/App.java", result.getFiles().get(0).getRelativePath());
        assertEquals(1, result.getIssues().size());
        assertEquals(5, result.getSteps().size());
    }

    @Test
    @DisplayName("Git diff review reports issues on added lines")
    void reviewGitDiffReportsAddedLineIssues() {
        CodeReviewGitDiffRequest request = new CodeReviewGitDiffRequest();
        request.setSessionId("7_session");
        request.setMaxFiles(5);
        request.setMaxIssuesPerFile(3);
        when(gitReviewService.getChangedFiles()).thenReturn(List.of("src/main/java/App.java"));
        when(gitReviewService.getFileDiff("src/main/java/App.java")).thenReturn("""
                diff --git a/src/main/java/App.java b/src/main/java/App.java
                index 1111111..2222222 100644
                --- a/src/main/java/App.java
                +++ b/src/main/java/App.java
                @@ -1,3 +1,6 @@
                 class App {
                +  void run(Exception e) {
                +    e.printStackTrace();
                +  }
                 }
                """);

        CodeReviewWorkspaceResult result = service.reviewGitDiff(7L, request);

        assertTrue(result.isSuccess());
        assertEquals("GIT_DIFF", result.getScopeType());
        assertEquals(1, result.getReviewedFileCount());
        assertEquals(1, result.getIssues().size());
        assertEquals(CodeReviewIssueCategory.RELIABILITY, result.getIssues().get(0).getCategory());
        assertEquals("src/main/java/App.java", result.getIssues().get(0).getFilePath());
        assertEquals(5, result.getSteps().size());
        assertEquals(AgentStepType.COLLECT_CONTEXT, result.getSteps().get(1).getType());
    }

    @Test
    @DisplayName("Git diff review can restrict review to selected changed files")
    void reviewGitDiffUsesSelectedChangedFiles() {
        CodeReviewGitDiffRequest request = new CodeReviewGitDiffRequest();
        request.setSessionId("7_session");
        request.setRelativePaths(List.of("src/main/java/Config.java"));
        request.setMaxFiles(5);
        request.setMaxIssuesPerFile(3);
        when(gitReviewService.getChangedFiles()).thenReturn(List.of(
                "src/main/java/App.java",
                "src/main/java/Config.java"
        ));
        when(gitReviewService.getFileDiff("src/main/java/Config.java")).thenReturn("""
                diff --git a/src/main/java/Config.java b/src/main/java/Config.java
                index 1111111..2222222 100644
                --- a/src/main/java/Config.java
                +++ b/src/main/java/Config.java
                @@ -1,3 +1,4 @@
                 class Config {
                +  String password = "demo";
                 }
                """);

        CodeReviewWorkspaceResult result = service.reviewGitDiff(7L, request);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getReviewedFileCount());
        assertEquals("src/main/java/Config.java", result.getFiles().get(0).getRelativePath());
        assertEquals("src/main/java/Config.java", result.getIssues().get(0).getFilePath());
        verify(gitReviewService).getFileDiff("src/main/java/Config.java");
    }

    @Test
    @DisplayName("Git diff changed files endpoint includes line change summaries")
    @SuppressWarnings("unchecked")
    void listGitChangedFilesIncludesSummaries() {
        when(gitReviewService.getChangedFiles()).thenReturn(List.of("src/main/java/App.java"));
        when(gitReviewService.getFileDiff("src/main/java/App.java")).thenReturn("""
                diff --git a/src/main/java/App.java b/src/main/java/App.java
                index 1111111..2222222 100644
                --- a/src/main/java/App.java
                +++ b/src/main/java/App.java
                @@ -1,4 +1,5 @@
                 class App {
                -  void old() {}
                +  void run(Exception e) {
                +    e.printStackTrace();
                +  }
                 }
                """);

        Map<String, Object> result = service.listGitChangedFiles(7L, "7_session", 10);

        assertEquals(true, result.get("success"));
        assertEquals(List.of("src/main/java/App.java"), result.get("files"));
        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("fileDetails");
        assertEquals("src/main/java/App.java", details.get(0).get("path"));
        assertEquals(3, details.get(0).get("additions"));
        assertEquals(1, details.get(0).get("deletions"));
    }

    @Test
    @DisplayName("Git diff file preview returns diff text and summary")
    @SuppressWarnings("unchecked")
    void getGitFileDiffPreviewReturnsDiffAndSummary() {
        when(gitReviewService.getFileDiff("src/main/java/App.java")).thenReturn("""
                diff --git a/src/main/java/App.java b/src/main/java/App.java
                index 1111111..2222222 100644
                --- a/src/main/java/App.java
                +++ b/src/main/java/App.java
                @@ -1,3 +1,4 @@
                 class App {
                +  void run() {}
                 }
                """);

        Map<String, Object> result = service.getGitFileDiffPreview(7L, "7_session", "src/main/java/App.java");

        assertEquals(true, result.get("success"));
        assertEquals("src/main/java/App.java", result.get("path"));
        assertTrue(String.valueOf(result.get("diffPreview")).contains("void run()"));
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertEquals(1, summary.get("additions"));
        assertEquals(0, summary.get("deletions"));
    }

    private CodeReviewRequest request(String sessionId, String relativePath, String focus, Integer maxIssues) {
        CodeReviewRequest request = new CodeReviewRequest();
        request.setSessionId(sessionId);
        request.setRelativePath(relativePath);
        request.setFocus(focus);
        request.setMaxIssues(maxIssues);
        return request;
    }

    private AgentWorkspaceFile workspaceFile(String relativePath) {
        return AgentWorkspaceFile.builder()
                .id((long) relativePath.hashCode())
                .workspaceId(10L)
                .userId(7L)
                .sessionId("7_session")
                .relativePath(relativePath)
                .fileName(relativePath.substring(relativePath.lastIndexOf('/') + 1))
                .contentType("text/plain")
                .fileSize(100L)
                .fileKey("key-" + relativePath)
                .version(1)
                .build();
    }

    private Map<String, Object> fileContent(String relativePath, String content) {
        return Map.of(
                "relativePath", relativePath,
                "fileName", relativePath.substring(relativePath.lastIndexOf('/') + 1),
                "content", content
        );
    }
}
