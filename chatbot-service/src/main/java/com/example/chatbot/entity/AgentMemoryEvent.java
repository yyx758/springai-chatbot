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
@TableName("agent_memory_event")
public class AgentMemoryEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("memory_id")
    private Long memoryId;

    @TableField("user_id")
    private Long userId;

    @TableField("event_type")
    private String eventType;

    @TableField("source_session_id")
    private String sourceSessionId;

    @TableField("source_record_id")
    private Long sourceRecordId;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("created_time")
    private LocalDateTime createdTime;
}
