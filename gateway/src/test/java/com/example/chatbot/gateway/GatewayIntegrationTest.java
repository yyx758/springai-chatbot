package com.example.chatbot.gateway;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Gateway 集成测试
 * 验证路由、鉴权、CORS 等功能
 *
 * 前置条件：
 * 1. 后端服务运行在 localhost:8080（或修改为可用地址）
 * 2. Redis 运行在 localhost:6379
 *
 * 运行方式：mvn test -Dtest=GatewayIntegrationTest
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@AutoConfigureWebTestClient
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @Order(1)
    @DisplayName("Gateway 启动验证 - 排除路径放行")
    void testExcludePathAllowed() {
        // /api/chat/health 是排除路径，无需 Token
        webTestClient.get()
                .uri("/api/chat/health")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @Order(2)
    @DisplayName("Gateway 鉴权拦截 - 无 Token 访问受保护路径返回 401")
    void testNoTokenReturns401() {
        webTestClient.get()
                .uri("/api/chat/records")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("未登录或登录已过期");
    }

    @Test
    @Order(3)
    @DisplayName("Gateway 鉴权拦截 - 无效 Token 返回 401")
    void testInvalidTokenReturns401() {
        webTestClient.get()
                .uri("/api/chat/records")
                .header("Authorization", "Bearer invalid-token-here")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false);
    }

    @Test
    @Order(4)
    @DisplayName("Gateway 路由转发 - 页面路由可达")
    void testPageRoute() {
        webTestClient.get()
                .uri("/login")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @Order(5)
    @DisplayName("Gateway CORS 配置 - OPTIONS 预检请求放行")
    void testCorsPreflight() {
        webTestClient.options()
                .uri("/api/chat/stream")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "*");
    }
}
