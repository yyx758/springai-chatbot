package com.example.chatbot.workspace;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.chatbot.dto.KnowledgeDocumentCreateRequest;
import com.example.chatbot.entity.AgentWorkspace;
import com.example.chatbot.entity.AgentWorkspaceFile;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.AgentWorkspaceFileMapper;
import com.example.chatbot.mapper.AgentWorkspaceMapper;
import com.example.chatbot.service.FileServiceClient;
import com.example.chatbot.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentWorkspaceService {

    private final AgentWorkspaceMapper workspaceMapper;
    private final AgentWorkspaceFileMapper fileMapper;
    private final AgentWorkspaceProperties properties;
    private final FileServiceClient fileServiceClient;
    private final RagService ragService;

    public AgentWorkspace getOrCreateWorkspace(Long userId, String sessionId) {
        ensureSessionOwnedByUser(userId, sessionId);
        AgentWorkspace workspace = workspaceMapper.selectOne(new LambdaQueryWrapper<AgentWorkspace>()
                .eq(AgentWorkspace::getUserId, userId)
                .eq(AgentWorkspace::getSessionId, sessionId)
                .last("LIMIT 1"));
        if (workspace != null) {
            return workspace;
        }
        AgentWorkspace created = AgentWorkspace.builder()
                .userId(userId)
                .sessionId(sessionId)
                .name("Agent Workspace")
                .status("ACTIVE")
                .rootKey(userId + "/" + sessionId)
                .build();
        workspaceMapper.insert(created);
        return created;
    }

    public AgentWorkspace getWorkspace(Long userId, Long workspaceId) {
        AgentWorkspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !userId.equals(workspace.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace not found");
        }
        return workspace;
    }

    public List<AgentWorkspaceFile> listFiles(Long userId, Long workspaceId) {
        AgentWorkspace workspace = getWorkspace(userId, workspaceId);
        return fileMapper.selectList(new LambdaQueryWrapper<AgentWorkspaceFile>()
                .eq(AgentWorkspaceFile::getWorkspaceId, workspace.getId())
                .orderByAsc(AgentWorkspaceFile::getRelativePath));
    }

    public AgentWorkspaceFile createFile(Long userId, String sessionId, WorkspaceFileCreateRequest request) {
        AgentWorkspace workspace = getOrCreateWorkspace(userId, sessionId);
        String path = normalizePath(request == null ? null : request.getRelativePath());
        String content = request == null || request.getContent() == null ? "" : request.getContent();
        String contentType = normalizeContentType(path, request == null ? null : request.getContentType());
        boolean overwrite = request != null && Boolean.TRUE.equals(request.getOverwrite());

        AgentWorkspaceFile existing = findFile(workspace.getId(), path);
        if (existing != null && !overwrite) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "workspace file already exists");
        }
        if (existing == null) {
            long count = fileMapper.selectCount(new LambdaQueryWrapper<AgentWorkspaceFile>()
                    .eq(AgentWorkspaceFile::getWorkspaceId, workspace.getId()));
            if (count >= properties.getMaxFiles()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace file limit exceeded");
            }
        }
        return saveFileVersion(userId, workspace, existing, path, content, contentType, null);
    }

    public AgentWorkspaceFile updateFile(Long userId, Long workspaceId, WorkspaceFileUpdateRequest request) {
        AgentWorkspace workspace = getWorkspace(userId, workspaceId);
        String path = normalizePath(request == null ? null : request.getRelativePath());
        AgentWorkspaceFile existing = requireFile(workspace.getId(), path);
        if (request != null && request.getExpectedVersion() != null
                && !request.getExpectedVersion().equals(existing.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "workspace file version conflict");
        }
        String content = request == null || request.getContent() == null ? "" : request.getContent();
        return saveFileVersion(userId, workspace, existing, path, content, existing.getContentType(), existing.getVersion() + 1);
    }

    public AgentWorkspaceFile appendFile(Long userId, String sessionId, String relativePath, String content) {
        AgentWorkspace workspace = getOrCreateWorkspace(userId, sessionId);
        String path = normalizePath(relativePath);
        AgentWorkspaceFile existing = findFile(workspace.getId(), path);
        if (existing == null) {
            WorkspaceFileCreateRequest request = new WorkspaceFileCreateRequest();
            request.setRelativePath(path);
            request.setContent(content);
            request.setOverwrite(false);
            return createFile(userId, sessionId, request);
        }
        String current = readFileContent(userId, workspace.getId(), path).get("content").toString();
        return saveFileVersion(userId, workspace, existing, path, current + (content == null ? "" : content),
                existing.getContentType(), existing.getVersion() + 1);
    }

    public Map<String, Object> readFileContent(Long userId, Long workspaceId, String relativePath) {
        AgentWorkspace workspace = getWorkspace(userId, workspaceId);
        String path = normalizePath(relativePath);
        AgentWorkspaceFile file = requireFile(workspace.getId(), path);
        byte[] bytes = fileServiceClient.getFileBytes(file.getFileKey(), userId);
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file content not found");
        }
        Map<String, Object> result = toFileMap(file);
        result.put("content", new String(bytes, StandardCharsets.UTF_8));
        return result;
    }

    public byte[] readFileBytes(Long userId, Long workspaceId, String relativePath) {
        AgentWorkspace workspace = getWorkspace(userId, workspaceId);
        AgentWorkspaceFile file = requireFile(workspace.getId(), normalizePath(relativePath));
        byte[] bytes = fileServiceClient.getFileBytes(file.getFileKey(), userId);
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file content not found");
        }
        return bytes;
    }

    public AgentWorkspaceFile requireWorkspaceFile(Long userId, Long workspaceId, String relativePath) {
        AgentWorkspace workspace = getWorkspace(userId, workspaceId);
        return requireFile(workspace.getId(), normalizePath(relativePath));
    }

    public KnowledgeDocument saveToKnowledge(Long userId, Long workspaceId, WorkspaceFileSaveToKnowledgeRequest request) {
        String path = normalizePath(request == null ? null : request.getRelativePath());
        Map<String, Object> content = readFileContent(userId, workspaceId, path);
        AgentWorkspaceFile file = requireWorkspaceFile(userId, workspaceId, path);

        KnowledgeDocumentCreateRequest createRequest = new KnowledgeDocumentCreateRequest();
        createRequest.setTitle(request != null && request.getTitle() != null && !request.getTitle().isBlank()
                ? request.getTitle().trim()
                : stripExtension(file.getFileName()));
        createRequest.setContent(String.valueOf(content.get("content")));
        createRequest.setTags(request == null ? null : request.getTags());
        createRequest.setEnabled(request == null || request.getEnabled() == null ? true : request.getEnabled());
        createRequest.setFileKey(file.getFileKey());
        return ragService.createDocument(userId, createRequest);
    }

    public Map<String, Object> toWorkspaceMap(AgentWorkspace workspace) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", workspace.getId());
        result.put("userId", workspace.getUserId());
        result.put("sessionId", workspace.getSessionId());
        result.put("name", workspace.getName());
        result.put("status", workspace.getStatus());
        result.put("rootKey", workspace.getRootKey());
        result.put("createdTime", workspace.getCreatedTime());
        result.put("updatedTime", workspace.getUpdatedTime());
        return result;
    }

    public Map<String, Object> toFileMap(AgentWorkspaceFile file) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", file.getId());
        result.put("workspaceId", file.getWorkspaceId());
        result.put("relativePath", file.getRelativePath());
        result.put("fileName", file.getFileName());
        result.put("contentType", file.getContentType());
        result.put("fileSize", file.getFileSize());
        result.put("fileKey", file.getFileKey());
        result.put("version", file.getVersion());
        result.put("openUrl", "/api/agent/workspaces/" + file.getWorkspaceId() + "/files/content?path=" + file.getRelativePath());
        result.put("downloadUrl", "/api/agent/workspaces/" + file.getWorkspaceId() + "/files/download?path=" + file.getRelativePath());
        result.put("createdTime", file.getCreatedTime());
        result.put("updatedTime", file.getUpdatedTime());
        return result;
    }

    private AgentWorkspaceFile saveFileVersion(Long userId, AgentWorkspace workspace, AgentWorkspaceFile existing,
                                               String path, String content, String contentType, Integer nextVersion) {
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxFileSizeBytes()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace file size limit exceeded");
        }
        Map<String, Object> fileInfo = fileServiceClient.createGeneratedWorkspaceFile(
                userId, workspace.getId(), path, content, contentType);
        if (fileInfo == null || fileInfo.get("fileKey") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "failed to persist workspace file");
        }
        if (existing == null) {
            AgentWorkspaceFile created = AgentWorkspaceFile.builder()
                    .workspaceId(workspace.getId())
                    .userId(userId)
                    .sessionId(workspace.getSessionId())
                    .relativePath(path)
                    .fileName(fileName(path))
                    .contentType(contentType)
                    .fileSize((long) bytes.length)
                    .fileKey(String.valueOf(fileInfo.get("fileKey")))
                    .version(1)
                    .build();
            fileMapper.insert(created);
            return created;
        }
        existing.setContentType(contentType);
        existing.setFileSize((long) bytes.length);
        existing.setFileKey(String.valueOf(fileInfo.get("fileKey")));
        existing.setVersion(nextVersion == null ? existing.getVersion() + 1 : nextVersion);
        existing.setUpdatedTime(LocalDateTime.now());
        fileMapper.updateById(existing);
        return existing;
    }

    private AgentWorkspaceFile findFile(Long workspaceId, String path) {
        return fileMapper.selectOne(new LambdaQueryWrapper<AgentWorkspaceFile>()
                .eq(AgentWorkspaceFile::getWorkspaceId, workspaceId)
                .eq(AgentWorkspaceFile::getRelativePath, path)
                .last("LIMIT 1"));
    }

    private AgentWorkspaceFile requireFile(Long workspaceId, String path) {
        AgentWorkspaceFile file = findFile(workspaceId, path);
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file not found");
        }
        return file;
    }

    private void ensureSessionOwnedByUser(Long userId, String sessionId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not authenticated");
        }
        if (sessionId == null || sessionId.isBlank() || !sessionId.startsWith(userId + "_")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "session does not belong to current user");
        }
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "relativePath is required");
        }
        String path = rawPath.trim();
        if (path.contains("\\") || path.startsWith("/") || path.startsWith("~") || path.matches("^[A-Za-z]:.*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid workspace path");
        }
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid workspace path");
            }
            if (properties.getBlockedPathSegments().contains(part)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace path is blocked");
            }
        }
        if (path.length() > properties.getMaxPathLength()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace path is too long");
        }
        String ext = extension(path);
        if (!properties.getAllowedExtensions().contains(ext) && !properties.getAllowedFilenames().contains(fileName(path))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "workspace file extension is not allowed");
        }
        return path;
    }

    private String normalizeContentType(String path, String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType.trim();
        }
        String ext = extension(path);
        return switch (ext) {
            case ".md" -> "text/markdown";
            case ".json" -> "application/json";
            case ".csv" -> "text/csv";
            case ".html" -> "text/html";
            case ".css" -> "text/css";
            case ".js", ".jsx", ".ts", ".tsx" -> "text/javascript";
            case ".xml" -> "application/xml";
            case ".yml", ".yaml", ".properties", ".toml", ".ini", ".conf",
                    ".gitignore", ".gitattributes", ".dockerignore", ".editorconfig",
                    ".java", ".kt", ".py", ".go", ".rs", ".c", ".cpp", ".h", ".hpp", ".cs",
                    ".php", ".rb", ".swift", ".sql", ".sh", ".bat", ".ps1", ".gradle", ".vue",
                    ".scss", ".less" -> "text/plain";
            default -> "text/plain";
        };
    }

    private String extension(String path) {
        String name = fileName(path);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String stripExtension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }
}
