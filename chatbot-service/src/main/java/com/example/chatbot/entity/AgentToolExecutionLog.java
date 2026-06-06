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
@TableName("agent_tool_execution_log")
public class AgentToolExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("tool_name")
    private String toolName;

    @TableField("tool_level")
    private String toolLevel;

    @TableField("arguments_json")
    private String argumentsJson;

    @TableField("result_summary")
    private String resultSummary;

    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_time")
    private LocalDateTime startedTime;

    @TableField("finished_time")
    private LocalDateTime finishedTime;
}
