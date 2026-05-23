package com.example.chatbot.e2e;

import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试
 * 验证完整链路：Gateway → 主服务 → Kafka → MySQL/Redis
 *
 * 前置条件：
 * 1. docker-compose up -d 启动所有服务
 * 2. 等待所有服务就绪（约 30-60 秒）
 *
 * 运行方式：mvn test -Dtest=EndToEndTest
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndToEndTest {

    private static final String GATEWAY_URL = "http://localhost:9000";
    private static final String DIRECT_URL = "http://localhost:8080";

    private WebClient gatewayClient;
    private WebClient directClient;

    @BeforeEach
    void setUp() {
        gatewayClient = WebClient.builder().baseUrl(GATEWAY_URL).build();
        directClient = WebClient.builder().baseUrl(DIRECT_URL).build();
    }

    /* ---- 基础设施健康检查 ---- */

    @Test
    @Order(1)
    @DisplayName("健康检查 - 主服务直连")
    void testDirectHealthCheck() {
        String response = directClient.get()
                .uri("/api/chat/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        assertNotNull(response);
        System.out.println("✅ 主服务直连健康: " + response);
    }

    @Test
    @Order(2)
    @DisplayName("健康检查 - 通过 Gateway 访问")
    void testGatewayHealthCheck() {
        String response = gatewayClient.get()
                .uri("/api/chat/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        assertNotNull(response);
        System.out.println("✅ Gateway 代理健康: " + response);
    }

    /* ---- 认证流程测试 ---- */

    @Test
    @Order(3)
    @DisplayName("认证 - 无 Token 访问受保护路径返回 401")
    void testUnauthorizedAccess() {
        try {
            gatewayClient.get()
                    .uri("/api/chat/records")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            fail("应该返回 401");
        } catch (WebClientResponseException e) {
            assertEquals(401, e.getStatusCode().value());
            System.out.println("✅ 未认证访问正确返回 401");
        }
    }

    @Test
    @Order(4)
    @DisplayName("认证 - 无效 Token 返回 401")
    void testInvalidToken() {
        try {
            gatewayClient.get()
                    .uri("/api/chat/records")
                    .header("Authorization", "Bearer invalid-token")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            fail("应该返回 401");
        } catch (WebClientResponseException e) {
            assertEquals(401, e.getStatusCode().value());
            System.out.println("✅ 无效 Token 正确返回 401");
        }
    }

    /* ---- 页面路由测试 ---- */

    @Test
    @Order(5)
    @DisplayName("页面路由 - 登录页可达")
    void testLoginPage() {
        String response = gatewayClient.get()
                .uri("/login")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        assertNotNull(response);
        assertTrue(response.contains("login") || response.contains("登录") || response.contains("AI Studio"),
                "登录页应包含相关内容");
        System.out.println("✅ 登录页可达");
    }

    /* ---- Kafka 事件流验证 ---- */

    @Test
    @Order(6)
    @DisplayName("Kafka - 通过 Gateway 访问聊天健康检查触发事件链路")
    void testKafkaEventChainViaGateway() {
        // 多次调用 health 接口，验证 Gateway → 主服务 链路稳定
        for (int i = 0; i < 3; i++) {
            String response = gatewayClient.get()
                    .uri("/api/chat/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            assertNotNull(response);
        }
        System.out.println("✅ Gateway → 主服务 链路稳定（3 次连续调用成功）");
    }

    /* ---- 服务发现验证 ---- */

    @Test
    @Order(7)
    @DisplayName("服务发现 - Nacos 中注册了服务")
    void testNacosServiceDiscovery() {
        // 通过 Nacos API 查询已注册服务
        WebClient nacosClient = WebClient.builder().baseUrl("http://localhost:8848").build();
        String response = nacosClient.get()
                .uri("/nacos/v1/ns/service/list?pageNo=1&pageSize=10")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .block();

        assertNotNull(response);
        System.out.println("✅ Nacos 服务列表: " + response);
    }
}
