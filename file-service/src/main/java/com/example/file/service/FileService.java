package com.example.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    public boolean delete(String fileKey) {
        FileRecord record = getByFileKey(fileKey);
        if (record == null) {
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

    public List<FileRecord> batchGetInfo(List<String> fileKeys) {
        return fileRecordMapper.selectList(new LambdaQueryWrapper<FileRecord>()
                .in(FileRecord::getFileKey, fileKeys));
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
}
