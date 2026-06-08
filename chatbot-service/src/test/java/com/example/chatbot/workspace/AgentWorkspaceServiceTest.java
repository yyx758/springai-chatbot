package com.example.chatbot.workspace;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.mapper.AgentWorkspaceFileMapper;
import com.example.chatbot.mapper.AgentWorkspaceMapper;
import com.example.chatbot.service.FileServiceClient;
import com.example.chatbot.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentWorkspaceServiceTest {

    @Mock
    private AgentWorkspaceMapper workspaceMapper;

    @Mock
    private AgentWorkspaceFileMapper fileMapper;

    @Mock
    private FileServiceClient fileServiceClient;

    @Mock
    private RagService ragService;

    private AgentWorkspaceService service;

    @BeforeEach
    void setUp() {
        AgentWorkspaceProperties properties = new AgentWorkspaceProperties();
        service = new AgentWorkspaceService(workspaceMapper, fileMapper, properties, fileServiceClient, ragService);
    }

    @Test
    @DisplayName("Workspace path rejects parent traversal before persistence")
    void rejectsParentTraversal() {
        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath("../secret.md");
        request.setContent("content");

        assertThrows(ResponseStatusException.class, () -> service.createFile(7L, "7_session", request));

        verifyNoInteractions(fileServiceClient);
    }

    @Test
    @DisplayName("Create workspace file persists through file service and metadata table")
    void createsWorkspaceFile() {
        when(workspaceMapper.selectOne(any())).thenReturn(null);
        when(workspaceMapper.insert(any(AgentWorkspace.class))).thenAnswer(invocation -> {
            AgentWorkspace workspace = invocation.getArgument(0);
            workspace.setId(100L);
            return 1;
        });
        when(fileMapper.selectOne(any())).thenReturn(null);
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(fileServiceClient.createGeneratedWorkspaceFile(eq(7L), eq(100L), eq("plans/ai.md"), eq("# Plan"), eq("text/markdown")))
                .thenReturn(Map.of("fileKey", "2026/abc.md", "fileSize", 6));
        when(fileMapper.insert(any(AgentWorkspaceFile.class))).thenAnswer(invocation -> {
            AgentWorkspaceFile file = invocation.getArgument(0);
            file.setId(200L);
            return 1;
        });

        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath("plans/ai.md");
        request.setContent("# Plan");
        request.setOverwrite(false);

        AgentWorkspaceFile file = service.createFile(7L, "7_session", request);

        assertEquals("plans/ai.md", file.getRelativePath());
        assertEquals("2026/abc.md", file.getFileKey());
        assertEquals(1, file.getVersion());
        verify(fileMapper).insert(any(AgentWorkspaceFile.class));
    }

    @Test
    @DisplayName("Create workspace Java source file for project code editing")
    void createsJavaSourceFile() {
        when(workspaceMapper.selectOne(any())).thenReturn(null);
        when(workspaceMapper.insert(any(AgentWorkspace.class))).thenAnswer(invocation -> {
            AgentWorkspace workspace = invocation.getArgument(0);
            workspace.setId(101L);
            return 1;
        });
        when(fileMapper.selectOne(any())).thenReturn(null);
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(fileServiceClient.createGeneratedWorkspaceFile(eq(7L), eq(101L),
                eq("src/main/java/com/example/App.java"), anyString(), eq("text/plain")))
                .thenReturn(Map.of("fileKey", "2026/App.java", "fileSize", 20));
        when(fileMapper.insert(any(AgentWorkspaceFile.class))).thenAnswer(invocation -> {
            AgentWorkspaceFile file = invocation.getArgument(0);
            file.setId(201L);
            return 1;
        });

        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath("src/main/java/com/example/App.java");
        request.setContent("class App {}");

        AgentWorkspaceFile file = service.createFile(7L, "7_session", request);

        assertEquals("src/main/java/com/example/App.java", file.getRelativePath());
        assertEquals("App.java", file.getFileName());
        assertEquals("text/plain", file.getContentType());
    }

    @Test
    @DisplayName("Create workspace project dotfile")
    void createsProjectDotfile() {
        when(workspaceMapper.selectOne(any())).thenReturn(null);
        when(workspaceMapper.insert(any(AgentWorkspace.class))).thenAnswer(invocation -> {
            AgentWorkspace workspace = invocation.getArgument(0);
            workspace.setId(102L);
            return 1;
        });
        when(fileMapper.selectOne(any())).thenReturn(null);
        when(fileMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(fileServiceClient.createGeneratedWorkspaceFile(eq(7L), eq(102L),
                eq(".gitignore"), eq("target/\n"), eq("text/plain")))
                .thenReturn(Map.of("fileKey", "2026/.gitignore", "fileSize", 8));
        when(fileMapper.insert(any(AgentWorkspaceFile.class))).thenAnswer(invocation -> {
            AgentWorkspaceFile file = invocation.getArgument(0);
            file.setId(202L);
            return 1;
        });

        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath(".gitignore");
        request.setContent("target/\n");

        AgentWorkspaceFile file = service.createFile(7L, "7_session", request);

        assertEquals(".gitignore", file.getRelativePath());
        assertEquals(".gitignore", file.getFileName());
    }

    @Test
    @DisplayName("Workspace path rejects generated output directories")
    void rejectsBuildOutputDirectory() {
        WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
        request.setRelativePath("target/classes/application.yml");
        request.setContent("server:\n  port: 8080");

        assertThrows(ResponseStatusException.class, () -> service.createFile(7L, "7_session", request));

        verifyNoMoreInteractions(fileServiceClient);
    }
}
