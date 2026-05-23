package com.example.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;

@Slf4j
@Component
public class LocalStorage implements FileStorage {

    @Value("${file.storage.local.base-path:./uploads}")
    private String basePath;

    @Value("${file.storage.local.url-prefix:/api/files}")
    private String urlPrefix;

    @Override
    public String store(InputStream data, String fileKey, String contentType) {
        try {
            Path filePath = Path.of(basePath, fileKey);
            Files.createDirectories(filePath.getParent());
            Files.copy(data, filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("【LocalStorage】文件存储成功: {}", fileKey);
            return filePath.toString();
        } catch (IOException e) {
            log.error("【LocalStorage】文件存储失败: {}", fileKey, e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream load(String fileKey) {
        try {
            Path filePath = Path.of(basePath, fileKey);
            if (!Files.exists(filePath)) {
                throw new FileNotFoundException("文件不存在: " + fileKey);
            }
            return Files.newInputStream(filePath);
        } catch (IOException e) {
            log.error("【LocalStorage】文件加载失败: {}", fileKey, e);
            throw new RuntimeException("文件加载失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        try {
            Path filePath = Path.of(basePath, fileKey);
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.info("【LocalStorage】文件删除成功: {}", fileKey);
            }
            return deleted;
        } catch (IOException e) {
            log.error("【LocalStorage】文件删除失败: {}", fileKey, e);
            return false;
        }
    }

    @Override
    public String getUrl(String fileKey) {
        return urlPrefix + "/download/" + fileKey;
    }

    @Override
    public boolean exists(String fileKey) {
        return Files.exists(Path.of(basePath, fileKey));
    }
}
