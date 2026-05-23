package com.example.file.controller;

import com.example.file.entity.FileRecord;
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

    @GetMapping
    public Map<String, Object> listFiles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Map.of("success", true, "data", fileService.listFiles(page, size));
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

    @GetMapping("/download/{*fileKey}")
    public void download(@PathVariable String fileKey, HttpServletResponse response) {
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
    public Map<String, Object> getFileInfo(@PathVariable String fileKey) {
        if (fileKey.startsWith("/")) {
            fileKey = fileKey.substring(1);
        }
        FileRecord record = fileService.getFileInfo(fileKey);
        if (record == null) {
            return Map.of("success", false, "error", "文件不存在");
        }
        return Map.of("success", true, "data", record);
    }

    @DeleteMapping("/delete/{*fileKey}")
    public Map<String, Object> delete(@PathVariable String fileKey) {
        if (fileKey.startsWith("/")) {
            fileKey = fileKey.substring(1);
        }
        boolean deleted = fileService.delete(fileKey);
        if (deleted) {
            return Map.of("success", true, "message", "文件删除成功");
        }
        return Map.of("success", false, "error", "文件不存在");
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

    @PostMapping("/batch")
    public Map<String, Object> batchGetInfo(@RequestBody Map<String, List<String>> request) {
        List<String> fileKeys = request.get("fileKeys");
        if (fileKeys == null || fileKeys.isEmpty()) {
            return Map.of("success", false, "error", "fileKeys 不能为空");
        }
        List<FileRecord> records = fileService.batchGetInfo(fileKeys);
        return Map.of("success", true, "data", records);
    }
}
