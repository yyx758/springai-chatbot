package com.example.file.controller;

import com.example.file.entity.FileRecord;
import com.example.file.dto.GeneratedKnowledgeFileRequest;
import com.example.file.dto.GeneratedWorkspaceFileRequest;
import com.example.file.service.FileService;
import com.example.file.storage.FileStorage;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileStorage fileStorage;
    /**
     * 文件列表（按用户隔离，不传 userId 则只返回公开文件）
     */
    @GetMapping
    public Map<String, Object> listFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = "X-Auth-UserId", required = false) Long userId) {
        return Map.of("success", true, "data", fileService.listFiles(page, size, userId));
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("bizType") String bizType,
            @RequestParam(value = "bizId", required = false) String bizId,
            @RequestHeader(value = "X-Auth-UserId", defaultValue = "0") Long uploaderId) {

        try {
            FileRecord record = fileService.upload(file, bizType, bizId, uploaderId);

            String url = "/api/files/download/" + record.getFileKey();
            String thumbnailUrl = record.getThumbnailKey() != null
                    ? "/api/files/download/" + record.getThumbnailKey() : null;

            return Map.of(
                    "success", true,
                    "data", Map.of(
                            "fileKey", record.getFileKey(),
                            "url", url,
                            "thumbnailUrl", thumbnailUrl != null ? thumbnailUrl : "",
                            "originalName", record.getOriginalName(),
                            "fileSize", record.getFileSize(),
                            "contentType", record.getContentType()
                    )
            );
        } catch (Exception e) {
            log.error("【FileController】文件上传失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 文件下载（检查所属权）
     * 不再信任 query 参数中的 userId，只信任 Gateway 注入的 Header。
     */
    @GetMapping("/download/{*fileKey}")
    public void download(@PathVariable String fileKey,
                         @RequestHeader(value = "X-Auth-UserId", required = false) Long headerUserId,
                         HttpServletResponse response) {
        // 不信任 query 参数，只使用 Header
        if (headerUserId == null || headerUserId <= 0) {
            try {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"success\":false,\"error\":\"未登录或登录已过期\"}");
            } catch (Exception e) {
                log.warn("【FileController】发送 401 响应失败", e);
            }
            return;
        }
        Long userId = headerUserId;
        if (fileKey.startsWith("/")) {
            fileKey = fileKey.substring(1);
        }
        try {
            FileRecord info = fileService.getFileInfo(fileKey);
            if (info == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("{\"success\":false,\"error\":\"文件不存在\"}");
                return;
            }

            // 权限检查：文件所属用户和请求用户不一致时拒绝
            if (!fileService.canAccess(info, userId)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("{\"success\":false,\"error\":\"无权访问该文件\"}");
                return;
            }

            response.setContentType(info.getContentType());
            response.setHeader("Content-Disposition",
                    "inline; filename=" + URLEncoder.encode(info.getOriginalName(), StandardCharsets.UTF_8));
            response.setHeader("Cache-Control", "max-age=86400");

            try (InputStream in = fileService.download(fileKey);
                 OutputStream out = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }
        } catch (Exception e) {
            log.error("【FileController】文件下载失败: {}", fileKey, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @GetMapping("/info/{*fileKey}")
    public Map<String, Object> getFileInfo(@PathVariable String fileKey,
                                           @RequestHeader(value = "X-Auth-UserId", required = false) Long userId) {
        if (fileKey.startsWith("/")) {
            fileKey = fileKey.substring(1);
        }
        FileRecord record = fileService.getFileInfo(fileKey);
        if (record == null) {
            return Map.of("success", false, "error", "文件不存在");
        }
        if (!fileService.canAccess(record, userId)) {
            return Map.of("success", false, "error", "无权访问该文件");
        }
        return Map.of("success", true, "data", record);
    }

    /**
     * 文件删除（检查所属权）
     */
    @DeleteMapping("/delete/{*fileKey}")
    public Map<String, Object> delete(@PathVariable String fileKey,
                                       @RequestHeader(value = "X-Auth-UserId", defaultValue = "0") Long userId) {
        if (fileKey.startsWith("/")) {
            fileKey = fileKey.substring(1);
        }
        boolean deleted = fileService.delete(fileKey, userId);
        if (deleted) {
            return Map.of("success", true, "message", "文件删除成功");
        }
        return Map.of("success", false, "error", "文件不存在或无权删除");
    }

    /**
     * 上传知识库文档（自动解析 PDF/DOCX/TXT）
     */
    @PostMapping("/upload/knowledge")
    public Map<String, Object> uploadKnowledge(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-Auth-UserId", defaultValue = "0") Long uploaderId) {

        try {
            FileService.KnowledgeUploadResult result = fileService.uploadKnowledgeDoc(file, uploaderId);
            return Map.of(
                    "success", true,
                    "data", Map.of(
                            "fileKey", result.fileRecord().getFileKey(),
                            "originalName", result.fileRecord().getOriginalName(),
                            "fileSize", result.fileRecord().getFileSize(),
                            "contentType", result.fileRecord().getContentType(),
                            "parsedContent", result.parsedContent(),
                            "charCount", result.parsedContent().length()
                    )
            );
        } catch (Exception e) {
            log.error("【FileController】知识库文档上传失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/generated/knowledge")
    public Map<String, Object> createGeneratedKnowledgeFile(
            @RequestBody GeneratedKnowledgeFileRequest request,
            @RequestHeader(value = "X-Auth-UserId", defaultValue = "0") Long uploaderId) {
        try {
            if (request == null || request.getContent() == null || request.getContent().isBlank()) {
                return Map.of("success", false, "error", "content 涓嶈兘涓虹┖");
            }
            FileRecord record = fileService.uploadGeneratedKnowledgeMarkdown(
                    request.getTitle(),
                    request.getContent(),
                    request.getBizId(),
                    uploaderId);
            return Map.of(
                    "success", true,
                    "data", Map.of(
                            "fileKey", record.getFileKey(),
                            "url", "/api/files/download/" + record.getFileKey(),
                            "originalName", record.getOriginalName(),
                            "fileSize", record.getFileSize(),
                            "contentType", record.getContentType(),
                            "bizType", record.getBizType(),
                            "bizId", record.getBizId() == null ? "" : record.getBizId()
                    )
            );
        } catch (Exception e) {
            log.error("銆怓ileController銆慉gent鐢熸垚鐭ヨ瘑鏂囨。鍏ュ簱澶辫触", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/generated/workspace")
    public Map<String, Object> createGeneratedWorkspaceFile(
            @RequestBody GeneratedWorkspaceFileRequest request,
            @RequestHeader(value = "X-Auth-UserId", defaultValue = "0") Long uploaderId) {
        try {
            if (request == null || request.getRelativePath() == null || request.getRelativePath().isBlank()) {
                return Map.of("success", false, "error", "relativePath is required");
            }
            FileRecord record = fileService.uploadGeneratedWorkspaceText(
                    request.getRelativePath(),
                    request.getContent(),
                    request.getContentType(),
                    request.getBizId(),
                    uploaderId);
            return Map.of(
                    "success", true,
                    "data", Map.of(
                            "fileKey", record.getFileKey(),
                            "url", "/api/files/download/" + record.getFileKey(),
                            "originalName", record.getOriginalName(),
                            "fileSize", record.getFileSize(),
                            "contentType", record.getContentType(),
                            "bizType", record.getBizType(),
                            "bizId", record.getBizId() == null ? "" : record.getBizId()
                    )
            );
        } catch (Exception e) {
            log.error("Agent workspace file generation failed", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/batch")
    public Map<String, Object> batchGetInfo(@RequestBody Map<String, List<String>> request,
                                             @RequestHeader(value = "X-Auth-UserId", required = false) Long userId) {
        List<String> fileKeys = request.get("fileKeys");
        if (fileKeys == null || fileKeys.isEmpty()) {
            return Map.of("success", false, "error", "fileKeys 不能为空");
        }
        List<FileRecord> records = fileService.batchGetInfo(fileKeys);
        // 过滤：只能看自己的文件
        records = records.stream()
                .filter(r -> fileService.canAccess(r, userId))
                .toList();
        return Map.of("success", true, "data", records);
    }
}
