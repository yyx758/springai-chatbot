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
@TableName("agent_long_term_memory")
public class AgentLongTermMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("scope_type")
    private String scopeType;

    @TableField("scope_key")
    private String scopeKey;

    @TableField("memory_type")
    private String memoryType;

    private String name;

    private String description;

    private String content;

    @TableField("load_hint")
    private String loadHint;

    @TableField("source_type")
    private String sourceType;

    private String status;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("updated_time")
    private LocalDateTime updatedTime;

    @TableField("last_used_time")
    private LocalDateTime lastUsedTime;

    @TableField("use_count")
    private Long useCount;

    @TableField("content_hash")
    private String contentHash;
}
