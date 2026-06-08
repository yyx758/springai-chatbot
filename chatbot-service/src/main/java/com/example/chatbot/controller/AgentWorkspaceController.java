package com.example.chatbot.controller;

import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.workspace.AgentWorkspaceService;
import com.example.chatbot.workspace.WorkspaceFileCreateRequest;
import com.example.chatbot.workspace.WorkspaceFileSaveToKnowledgeRequest;
import com.example.chatbot.workspace.WorkspaceFileUpdateRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/workspaces")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private final AgentWorkspaceService workspaceService;

    @GetMapping("/current")
    public Map<String, Object> current(@RequestParam String sessionId, HttpServletRequest request) {
        AgentWorkspace workspace = workspaceService.getOrCreateWorkspace(resolveUserId(request), sessionId);
        return Map.of("success", true, "workspace", workspaceService.toWorkspaceMap(workspace),
                "files", workspaceService.listFiles(workspace.getUserId(), workspace.getId()).stream()
                        .map(workspaceService::toFileMap)
                        .toList());
    }

    @GetMapping("/{workspaceId}/files")
    public Map<String, Object> listFiles(@PathVariable Long workspaceId, HttpServletRequest request) {
        Long userId = resolveUserId(request);
        return Map.of("success", true, "files", workspaceService.listFiles(userId, workspaceId).stream()
                .map(workspaceService::toFileMap)
                .toList());
    }

    @PostMapping("/{workspaceId}/files")
    public Map<String, Object> createFile(@PathVariable Long workspaceId,
                                          @RequestBody WorkspaceFileCreateRequest body,
                                          HttpServletRequest request) {
        Long userId = resolveUserId(request);
        AgentWorkspace workspace = workspaceService.getWorkspace(userId, workspaceId);
        AgentWorkspaceFile file = workspaceService.createFile(userId, workspace.getSessionId(), body);
        return Map.of("success", true, "file", workspaceService.toFileMap(file));
    }

    @PutMapping("/{workspaceId}/files/content")
    public Map<String, Object> updateFile(@PathVariable Long workspaceId,
                                          @RequestBody WorkspaceFileUpdateRequest body,
                                          HttpServletRequest request) {
        AgentWorkspaceFile file = workspaceService.updateFile(resolveUserId(request), workspaceId, body);
        return Map.of("success", true, "file", workspaceService.toFileMap(file));
    }

    @GetMapping("/{workspaceId}/files/content")
    public Map<String, Object> readFile(@PathVariable Long workspaceId,
                                        @RequestParam("path") String path,
                                        HttpServletRequest request) {
        return Map.of("success", true, "file", workspaceService.readFileContent(resolveUserId(request), workspaceId, path));
    }

    @PostMapping("/{workspaceId}/files/save-to-knowledge")
    public Map<String, Object> saveToKnowledge(@PathVariable Long workspaceId,
                                               @RequestBody WorkspaceFileSaveToKnowledgeRequest body,
                                               HttpServletRequest request) {
        KnowledgeDocument document = workspaceService.saveToKnowledge(resolveUserId(request), workspaceId, body);
        return Map.of("success", true, "document", document);
    }

    @GetMapping("/{workspaceId}/files/download")
    public ResponseEntity<byte[]> download(@PathVariable Long workspaceId,
                                           @RequestParam("path") String path,
                                           HttpServletRequest request) {
        Long userId = resolveUserId(request);
        AgentWorkspaceFile file = workspaceService.requireWorkspaceFile(userId, workspaceId, path);
        byte[] bytes = workspaceService.readFileBytes(userId, workspaceId, path);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(bytes);
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
