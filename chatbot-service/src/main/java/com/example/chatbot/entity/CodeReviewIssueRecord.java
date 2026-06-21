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
@TableName("code_review_issue")
public class CodeReviewIssueRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private String runId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    private String severity;

    private String category;

    private String title;

    private String description;

    @TableField("file_path")
    private String filePath;

    @TableField("start_line")
    private Integer startLine;

    @TableField("end_line")
    private Integer endLine;

    private String evidence;

    private String impact;

    private String recommendation;

    private Boolean patchable;

    @TableField("suggested_patch")
    private String suggestedPatch;

    private String status;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;
}
