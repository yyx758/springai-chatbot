# Phase 2: Spring Cloud Gateway 网关服务

## 一、本阶段目标

引入 Spring Cloud Gateway 作为统一入口，实现：
- **统一路由**：所有请求先经过 Gateway，再转发到后端服务
- **JWT 鉴权**：在网关层统一验证 Token，替代各服务内部的 AuthInterceptor
- **角色鉴权**：`/api/admin/**` 路径自动校验 ADMIN 角色
- **请求日志**：记录所有请求的路径、耗时、状态码

### 架构变化

```
【Phase 1】
用户 → localhost:8080 (所有功能混在一起)

【Phase 2】
用户 → localhost:9000 (Gateway)
         ├─ JWT 鉴权过滤
         ├─ 请求日志
         ├─ 路由转发
         │
         └→ localhost:8080 (后端服务)
              ├─ /api/auth/**
              ├─ /api/chat/**
              ├─ /api/knowledge/**
              └─ /api/admin/**
```

---

## 二、项目结构

```
gateway/
├── pom.xml
└── src/main/java/com/example/chatbot/gateway/
    ├── GatewayApplication.java         # 启动类
    ├── config/
    │   └── JwtConfig.java              # JWT 解析配置
    └── filter/
        ├── AuthGlobalFilter.java       # 全局鉴权过滤器
        └── RequestLoggingFilter.java   # 请求日志过滤器
```

---

## 三、核心代码解析

### 3.1 依赖说明（pom.xml）

```xml
<!-- Spring Cloud Gateway（基于 WebFlux，非 Servlet） -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>

<!-- JWT 解析 -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>

<!-- Reactive Redis（限流扩展用） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
```

**注意：** Gateway 基于 WebFlux（响应式），不能引入 `spring-boot-starter-web`，否则会冲突。

### 3.2 路由配置（application.yml）

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: auth-service
          uri: http://localhost:8080    # 后端服务地址
          predicates:
            - Path=/api/auth/**         # 匹配路径

        - id: chat-service
          uri: http://localhost:8080
          predicates:
            - Path=/api/chat/**

        # ... 其他路由
```

**路由匹配规则：**
- `predicates`：匹配条件（路径、方法、Header 等）
- `filters`：对请求/响应的加工处理
- `uri`：转发目标地址

### 3.3 JWT 鉴权过滤器（AuthGlobalFilter.java）

```java
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. OPTIONS 放行
        // 2. 排除路径放行（如 /api/auth/login）
        // 3. 提取 Authorization: Bearer <token>
        // 4. 解析 JWT，提取 userId、role
        // 5. /api/admin/** 校验 ADMIN 角色
        // 6. 将用户信息注入 Header 传递给下游
        //    X-Auth-UserId, X-Auth-Username, X-Auth-Role
    }
}
```

**过滤器执行顺序：**
- `Ordered.HIGHEST_PRECEDENCE` = 最先执行
- 本过滤器 `order = -100`，确保在路由之前执行鉴权

**用户信息传递方式：**
Gateway 解析 Token 后，通过 `X-Auth-*` Header 传递给下游服务。
下游服务需要改造为从 Header 读取用户信息（而不是从 JWT 重新解析）。

### 3.4 排除路径配置

```yaml
app:
  auth:
    jwt-secret: change-this-dev-secret-key-at-least-32-chars
    exclude-paths:
      - /api/auth/login
      - /api/auth/register
      - /api/auth/send-code
      - /api/auth/refresh
      - /api/auth/forgot-password
      - /api/auth/reset-password
      - /api/chat/health
      - /actuator/**
```

---

## 四、运行和测试

### 4.1 启动顺序

```bash
# 1. 确保 Kafka 运行中
docker ps | grep kafka

# 2. 启动后端服务（端口 8080）
cd springaI-chatbot
mvn spring-boot:run

# 3. 启动 Gateway（端口 9000）
cd gateway
mvn spring-boot:run
```

### 4.2 访问方式

| 方式 | 地址 | 说明 |
|------|------|------|
| 直接访问后端 | http://localhost:8080 | 原有方式，仍可用 |
| 通过 Gateway | http://localhost:9000 | 新方式，经过鉴权 |

### 4.3 运行测试

```bash
cd gateway
mvn test -Dtest=GatewayIntegrationTest
```

### 4.4 手动验证

```bash
# 1. 无 Token 访问受保护路径 → 应返回 401
curl http://localhost:9000/api/chat/records
# {"success":false,"error":"未登录或登录已过期"}

# 2. 访问排除路径 → 应放行
curl http://localhost:9000/api/chat/health

# 3. 使用有效 Token 访问 → 应正常转发
curl -H "Authorization: Bearer <your-token>" http://localhost:9000/api/chat/records
```

---

## 五、Gateway 过滤器执行链

```
请求进入 Gateway
    │
    ▼
[RequestLoggingFilter] order=-50    ← 记录请求日志
    │
    ▼
[AuthGlobalFilter] order=-100       ← JWT 鉴权（最先执行）
    │
    ▼
[路由匹配]                          ← 根据 path 匹配路由
    │
    ▼
[转发到后端服务]                     ← uri: http://localhost:8080
    │
    ▼
[响应返回]                          ← 记录响应状态和耗时
```

---

## 六、下游服务改造说明（后续）

当前阶段，后端服务的 `AuthInterceptor` 仍然保留。后续微服务拆分时，有两种方案：

**方案 A：保留 AuthInterceptor + 信任 Gateway Header**
- Gateway 鉴权后设置 `X-Auth-*` Header
- 后端服务读取 Header，跳过 JWT 解析
- 需要确保内网安全，外部无法伪造 Header

**方案 B：移除 AuthInterceptor，完全依赖 Gateway**
- 后端服务不再做鉴权
- 所有请求必须经过 Gateway
- 更简洁，但需要严格的网络安全策略

建议采用 **方案 A**（双保险），Gateway 做主鉴权，服务内做兜底校验。

---

## 七、常见问题

### Q1: Gateway 和后端服务端口冲突？

Gateway 默认 9000，后端 8080，不会冲突。修改 `server.port` 即可。

### Q2: SSE 流式响应在 Gateway 下正常吗？

Spring Cloud Gateway 原生支持 SSE 代理转发，无需额外配置。
如果出现超时，可在路由配置中增加 `response-timeout`：

```yaml
routes:
  - id: chat-service
    uri: http://localhost:8080
    predicates:
      - Path=/api/chat/**
    metadata:
      response-timeout: 300000
```

### Q3: Gateway 启动报 WebFlux 冲突？

确保 pom.xml 中没有引入 `spring-boot-starter-web`。Gateway 基于 WebFlux，两者不能共存。

---

## 八、下一步（Phase 3 预告）

Phase 3 将引入 **Nacos 服务注册与发现**：
- 后端服务注册到 Nacos
- Gateway 从 Nacos 发现服务（不再硬编码 localhost:8080）
- 动态路由，服务上下线自动感知
