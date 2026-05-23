package com.example.chatbot.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库事件消息体
 * 当知识文档创建/更新/删除时发布，ChatService 消费后刷新 RAG 索引
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型：KNOWLEDGE_CREATED / KNOWLEDGE_UPDATED / KNOWLEDGE_DELETED */
    private String eventType;

    /** 文档 ID */
    private Long documentId;

    /** 文档所属用户 ID */
    private Long userId;

    /** 文档标题 */
    private String title;

    /** 事件产生时间 */
    private LocalDateTime eventTime;
}
