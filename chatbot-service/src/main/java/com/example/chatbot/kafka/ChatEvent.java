package com.example.chatbot.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天事件消息体
 * 通过 Kafka 在 ChatbotService（生产者）和持久化消费者之间传递
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型：CHAT_COMPLETED / CHAT_ERROR */
    private String eventType;

    /** 会话 ID */
    private String sessionId;

    /** 用户原始消息 */
    private String userMessage;

    /** AI 回复内容 */
    private String botResponse;

    /** 图片 Base64 数据（多模态场景） */
    private String imageData;

    /** 图片 MIME 类型 */
    private String imageMimeType;

    /** 图片原始字节（Base64 编码后传输） */
    private byte[] imageBytes;

    /** 文件服务的文件键（新方式，替代 imageBytes） */
    private String imageFileKey;

    /** 事件产生时间 */
    private LocalDateTime eventTime;

    /** 用户 ID，用于后续扩展（如统计、审计） */
    private String userId;
}
