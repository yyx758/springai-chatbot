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
@TableName("agent_pending_action")
public class AgentPendingAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("action_type")
    private String actionType;

    @TableField("tool_name")
    private String toolName;

    @TableField("arguments_json")
    private String argumentsJson;

    private String status;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("created_time")
    private LocalDateTime createdTime;

    @TableField("confirmed_time")
    private LocalDateTime confirmedTime;

    @TableField("result_summary")
    private String resultSummary;

    @TableField("error_message")
    private String errorMessage;
}
