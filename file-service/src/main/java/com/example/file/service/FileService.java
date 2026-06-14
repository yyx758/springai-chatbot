package com.example.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.file.entity.FileRecord;
import com.example.file.mapper.FileRecordMapper;
import com.example.file.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final FileStorage fileStorage;
    private final FileRecordMapper fileRecordMapper;
    private final ImageProcessor imageProcessor;
    private final KnowledgeDocParser docParser;

    public record KnowledgeUploadResult(FileRecord fileRecord, String parsedContent) {}

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    public FileRecord upload(MultipartFile file, String bizType, String bizId, Long uploaderId) throws IOException {
        String contentType = file.getContentType();
        long fileSize = file.getSize();
        String originalName = file.getOriginalFilename();

        // 图片校验和处理
        byte[] fileBytes = file.getBytes();
        byte[] thumbnailBytes = null;
        String thumbnailKey = null;

        if (imageProcessor.isImage(contentType)) {
            imageProcessor.validateImage(contentType, fileSize);
            fileBytes = imageProcessor.compressIfNeeded(fileBytes, contentType);
            thumbnailBytes = imageProcessor.generateThumbnail(fileBytes, contentType);
        }

        // 生成文件键
        String fileKey = generateFileKey(originalName);

        // 存储主文件
        fileStorage.store(new ByteArrayInputStream(fileBytes), fileKey, contentType);

        // 存储缩略图
        if (thumbnailBytes != null) {
            thumbnailKey = fileKey.replace(".", "_thumb.");
            fileStorage.store(new ByteArrayInputStream(thumbnailBytes), thumbnailKey, contentType);
        }

        // 保存元数据
        FileRecord record = FileRecord.builder()
                .fileKey(fileKey)
                .originalName(originalName)
                .contentType(contentType)
                .fileSize((long) fileBytes.length)
                .storageType("LOCAL")
                .storagePath(fileKey)
                .thumbnailKey(thumbnailKey)
                .uploaderId(uploaderId)
                .bizType(bizType)
                .bizId(bizId)
                .downloadCount(0)
                .build();

        fileRecordMapper.insert(record);
        log.info("【FileService】文件上传成功: key={}, bizType={}, size={}KB",
                fileKey, bizType, fileBytes.length / 1024);

        return record;
    }

    public InputStream download(String fileKey) {
        // 增加下载计数
        FileRecord record = getByFileKey(fileKey);
        if (record != null) {
            record.setDownloadCount(record.getDownloadCount() + 1);
            fileRecordMapper.updateById(record);
        }
        return fileStorage.load(fileKey);
    }

    public FileRecord getFileInfo(String fileKey) {
        return getByFileKey(fileKey);
    }

    public boolean delete(String fileKey, Long userId) {
        FileRecord record = getByFileKey(fileKey);
        if (record == null || !canAccess(record, userId)) {
            return false;
        }

        // 删除文件
        fileStorage.delete(fileKey);
        if (record.getThumbnailKey() != null) {
            fileStorage.delete(record.getThumbnailKey());
        }

        // 删除元数据
        fileRecordMapper.delete(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getFileKey, fileKey));

        log.info("【FileService】文件删除成功: key={}", fileKey);
        return true;
    }

    /**
     * 上传知识库文档，自动解析内容
     */
    public IPage<FileRecord> listFiles(int page, int size, Long userId) {
        LambdaQueryWrapper<FileRecord> wrapper = new LambdaQueryWrapper<FileRecord>()
                .orderByDesc(FileRecord::getCreatedTime);
        // 如果传了 userId，只返回该用户的文件
        if (userId != null && userId > 0) {
            wrapper.eq(FileRecord::getUploaderId, userId);
        }
        return fileRecordMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 检查用户是否有权访问该文件
     */
    public boolean canAccess(FileRecord record, Long userId) {
        if (record == null) return false;
        if (userId == null || userId <= 0) return false;
        if (record.getUploaderId() == null || record.getUploaderId() <= 0) return false;
        return record.getUploaderId().equals(userId);
    }

    public KnowledgeUploadResult uploadKnowledgeDoc(MultipartFile file, Long uploaderId) throws IOException {
        String contentType = file.getContentType();
        String originalName = file.getOriginalFilename();

        if (!docParser.isSupported(contentType, originalName)) {
            throw new IllegalArgumentException("不支持的文件格式，请上传 PDF、DOCX、TXT 或 MD 文件");
        }

        // 生成文件键
        String fileKey = generateFileKey(originalName);

        // 存储原始文件
        byte[] fileBytes = file.getBytes();
        fileStorage.store(new ByteArrayInputStream(fileBytes), fileKey, contentType);

        // 解析文档内容
        String parsedContent = docParser.parse(new ByteArrayInputStream(fileBytes), contentType, originalName);

        // 保存元数据
        FileRecord record = FileRecord.builder()
                .fileKey(fileKey)
                .originalName(originalName)
                .contentType(contentType)
                .fileSize((long) fileBytes.length)
                .storageType("LOCAL")
                .storagePath(fileKey)
                .uploaderId(uploaderId)
                .bizType("KNOWLEDGE_DOC")
                .downloadCount(0)
                .build();

        fileRecordMapper.insert(record);
        log.info("【FileService】知识库文档上传+解析成功: key={}, 解析字符数={}", fileKey, parsedContent.length());

        return new KnowledgeUploadResult(record, parsedContent);
    }

    public FileRecord uploadGeneratedKnowledgeMarkdown(String title, String content, String bizId, Long uploaderId) {
        String safeTitle = sanitizeFilename(title);
        String originalName = safeTitle.endsWith(".md") ? safeTitle : safeTitle + ".md";
        byte[] fileBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        String contentType = "text/markdown";
        String fileKey = generateFileKey(originalName);

        fileStorage.store(new ByteArrayInputStream(fileBytes), fileKey, contentType);

        FileRecord record = FileRecord.builder()
                .fileKey(fileKey)
                .originalName(originalName)
                .contentType(contentType)
                .fileSize((long) fileBytes.length)
                .storageType("LOCAL")
                .storagePath(fileKey)
                .uploaderId(uploaderId)
                .bizType("KNOWLEDGE_DOC")
                .bizId(bizId)
                .downloadCount(0)
                .build();

        fileRecordMapper.insert(record);
        log.info("銆怓ileService銆慉gent鐢熸垚鐭ヨ瘑鏂囨。宸插瓨鍏ユ枃浠舵湇鍔? key={}, bizId={}", fileKey, bizId);
        return record;
    }

    public List<FileRecord> batchGetInfo(List<String> fileKeys) {
        return fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .in(FileRecord::getFileKey, fileKeys));
    }

    public FileRecord uploadGeneratedWorkspaceText(String relativePath, String content, String contentType,
                                                   String bizId, Long uploaderId) {
        String originalName = sanitizeWorkspaceFilename(relativePath);
        String finalContentType = contentType == null || contentType.isBlank() ? guessTextContentType(originalName) : contentType.trim();
        byte[] fileBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        String fileKey = generateFileKey(originalName);

        fileStorage.store(new ByteArrayInputStream(fileBytes), fileKey, finalContentType);

        FileRecord record = FileRecord.builder()
                .fileKey(fileKey)
                .originalName(originalName)
                .contentType(finalContentType)
                .fileSize((long) fileBytes.length)
                .storageType("LOCAL")
                .storagePath(fileKey)
                .uploaderId(uploaderId)
                .bizType("AGENT_WORKSPACE")
                .bizId(bizId)
                .downloadCount(0)
                .build();

        fileRecordMapper.insert(record);
        log.info("Agent workspace file generated: key={}, bizId={}, size={}KB", fileKey, bizId, fileBytes.length / 1024);
        return record;
    }

    private FileRecord getByFileKey(String fileKey) {
        return fileRecordMapper.selectOne(new LambdaQueryWrapper<FileRecord>()
                .eq(FileRecord::getFileKey, fileKey));
    }

    private String generateFileKey(String originalName) {
        String date = LocalDate.now().format(DATE_FORMAT);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String ext = getExtension(originalName);
        return date + "/" + uuid + ext;
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    private String sanitizeFilename(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            value = "generated-knowledge";
        }
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (value.length() > 80) {
            value = value.substring(0, 80);
        }
        return value;
    }

    private String sanitizeWorkspaceFilename(String relativePath) {
        String value = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (value.isBlank()) {
            value = "workspace-file.txt";
        }
        if (value.length() > 120) {
            value = value.substring(value.length() - 120);
        }
        return value;
    }

    private String guessTextContentType(String originalName) {
        String lower = originalName == null ? "" : originalName.toLowerCase();
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        return "text/plain";
    }
}
