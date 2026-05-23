package com.example.chatbot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Studio 网关服务
 * 职责：路由转发、JWT 鉴权、限流
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
        System.out.println("==============================================");
        System.out.println("AI Studio Gateway 启动成功！");
        System.out.println("网关地址: http://localhost:9000");
        System.out.println("==============================================");
    }
}
