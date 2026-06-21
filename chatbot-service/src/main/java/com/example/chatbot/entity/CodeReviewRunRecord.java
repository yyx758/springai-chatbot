package com.example.chatbot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
@TableName("code_review_run")
public class CodeReviewRunRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private String runId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("scope_type")
    private String scopeType;

    @TableField("target_path")
    private String targetPath;

    @TableField("reviewed_file_count")
    private Integer reviewedFileCount;

    @TableField("risk_level")
    private String riskLevel;

    private String summary;

    private String status;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
