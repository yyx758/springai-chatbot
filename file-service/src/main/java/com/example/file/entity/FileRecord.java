package com.example.file.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("file_record")
public class FileRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileKey;

    private String originalName;

    private String contentType;

    private Long fileSize;

    private String storageType;

    private String storagePath;

    private String thumbnailKey;

    private Long uploaderId;

    private String bizType;

    private String bizId;

    private Integer downloadCount;

    private LocalDateTime createdTime;

    private LocalDateTime updatedTime;
}
