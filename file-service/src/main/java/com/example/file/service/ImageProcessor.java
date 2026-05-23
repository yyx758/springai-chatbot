package com.example.file.service;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Set;

@Slf4j
@Component
public class ImageProcessor {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    private static final long MAX_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int THUMBNAIL_SIZE = 200;
    private static final long COMPRESS_THRESHOLD = 2 * 1024 * 1024; // 2MB

    public boolean isImage(String contentType) {
        return contentType != null && ALLOWED_TYPES.contains(contentType.toLowerCase());
    }

    public void validateImage(String contentType, long fileSize) {
        if (!isImage(contentType)) {
            throw new IllegalArgumentException("不支持的图片格式: " + contentType);
        }
        if (fileSize > MAX_SIZE) {
            throw new IllegalArgumentException("图片大小超过限制: " + (fileSize / 1024 / 1024) + "MB (最大 10MB)");
        }
    }

    public byte[] compressIfNeeded(byte[] imageBytes, String contentType) {
        if (!"image/jpeg".equals(contentType) || imageBytes.length <= COMPRESS_THRESHOLD) {
            return imageBytes;
        }

        try {
            log.info("【ImageProcessor】压缩图片，原始大小: {}KB", imageBytes.length / 1024);
            ByteArrayInputStream input = new ByteArrayInputStream(imageBytes);
            BufferedImage image = ImageIO.read(input);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thumbnails.of(image)
                    .scale(1.0)
                    .outputQuality(0.8)
                    .outputFormat("jpg")
                    .toOutputStream(output);

            byte[] compressed = output.toByteArray();
            log.info("【ImageProcessor】压缩完成，新大小: {}KB", compressed.length / 1024);
            return compressed;
        } catch (IOException e) {
            log.warn("【ImageProcessor】压缩失败，使用原图", e);
            return imageBytes;
        }
    }

    public byte[] generateThumbnail(byte[] imageBytes, String contentType) {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(imageBytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            String format = getFormat(contentType);
            Thumbnails.of(input)
                    .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                    .keepAspectRatio(true)
                    .outputFormat(format)
                    .toOutputStream(output);

            log.info("【ImageProcessor】缩略图生成成功，大小: {}KB", output.size() / 1024);
            return output.toByteArray();
        } catch (IOException e) {
            log.warn("【ImageProcessor】缩略图生成失败", e);
            return null;
        }
    }

    private String getFormat(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }
}
