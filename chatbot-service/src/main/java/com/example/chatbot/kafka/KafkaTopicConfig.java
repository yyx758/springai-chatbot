package com.example.chatbot.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 配置
 * 集中管理所有 Topic 的创建，方便后续扩展
 */
@Configuration
public class KafkaTopicConfig {

    /** 聊天事件 Topic */
    public static final String TOPIC_CHAT_EVENTS = "chat.events";

    /** 知识库事件 Topic */
    public static final String TOPIC_KNOWLEDGE_EVENTS = "knowledge.events";

    /** 通知事件 Topic（邮件等） */
    public static final String TOPIC_NOTIFICATION_EVENTS = "notification.events";

    @Bean
    public NewTopic chatEventsTopic() {
        return TopicBuilder.name(TOPIC_CHAT_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic knowledgeEventsTopic() {
        return TopicBuilder.name(TOPIC_KNOWLEDGE_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic notificationEventsTopic() {
        return TopicBuilder.name(TOPIC_NOTIFICATION_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
