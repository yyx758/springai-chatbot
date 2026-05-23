package com.example.chatbot.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 通知事件消息体
 * 用于异步发送邮件（注册验证码、密码重置等）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件类型：SEND_VERIFICATION_CODE / SEND_RESET_CODE / WELCOME_EMAIL */
    private String eventType;

    /** 收件人邮箱 */
    private String toEmail;

    /** 用户名（欢迎邮件用） */
    private String username;

    /** 事件产生时间 */
    private LocalDateTime eventTime;
}
