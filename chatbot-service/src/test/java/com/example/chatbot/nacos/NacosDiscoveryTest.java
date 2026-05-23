package com.example.chatbot.nacos;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nacos 服务发现集成测试
 * 前置条件：
 * 1. Nacos 运行在 localhost:8848
 * 2. 主服务已启动并注册到 Nacos
 *
 * 运行方式：mvn test -Dtest=NacosDiscoveryTest
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NacosDiscoveryTest {

    @Autowired(required = false)
    private DiscoveryClient discoveryClient;

    @Test
    @Order(1)
    @DisplayName("Nacos 服务发现 - DiscoveryClient 注入成功")
    void testDiscoveryClientInjected() {
        assertNotNull(discoveryClient, "DiscoveryClient 应该被成功注入");
        System.out.println("✅ DiscoveryClient 注入成功");
        System.out.println("   实现类: " + discoveryClient.getClass().getSimpleName());
    }

    @Test
    @Order(2)
    @DisplayName("Nacos 服务发现 - 本服务已注册")
    void testServiceRegistered() {
        assertNotNull(discoveryClient);

        List<String> services = discoveryClient.getServices();
        System.out.println("✅ Nacos 已注册服务数: " + services.size());
        services.forEach(s -> System.out.println("   - " + s));

        // 验证本服务已注册
        boolean registered = services.stream()
                .anyMatch(s -> s.contains("chatbot"));
        assertTrue(registered, "chatbot-service 应该已注册到 Nacos");
    }

    @Test
    @Order(3)
    @DisplayName("Nacos 服务发现 - 获取服务实例详情")
    void testServiceInstances() {
        assertNotNull(discoveryClient);

        List<String> services = discoveryClient.getServices();
        for (String service : services) {
            List<ServiceInstance> instances = discoveryClient.getInstances(service);
            for (ServiceInstance instance : instances) {
                System.out.printf("✅ 服务实例: %s -> %s:%d%n",
                        service,
                        instance.getHost(),
                        instance.getPort());
            }
        }
    }
}
