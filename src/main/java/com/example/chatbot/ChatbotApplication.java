package com.example.chatbot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 智能客服系统主应用类
 * 基于Spring Boot + Spring AI + MyBatis + DeepSeek
 * 
 * @author yyvb
 */
@SpringBootApplication
@MapperScan("com.example.chatbot.mapper")
public class ChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatbotApplication.class, args);
        System.out.println("==============================================");
        System.out.println("智能客服系统启动成功！");
        System.out.println("访问地址: http://localhost:8080");
        System.out.println("==============================================");
    }
} 