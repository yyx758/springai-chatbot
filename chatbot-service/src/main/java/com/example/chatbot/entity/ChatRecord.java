package com.example.chatbot.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天记录实体类
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_record") // 指定表名
public class ChatRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String userMessage;
    private String botResponse;
    @JsonIgnore
    private String imageData;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    private String sessionId;

    private String eventId;

    private Long userId;

} 
