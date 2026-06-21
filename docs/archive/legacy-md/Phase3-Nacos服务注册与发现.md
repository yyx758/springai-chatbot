# Phase 3: Nacos 服务注册与发现

## 一、本阶段目标

引入 Nacos 作为服务注册中心，实现：
- **服务注册**：后端服务启动时自动注册到 Nacos
- **服务发现**：Gateway 从 Nacos 获取服务地址，不再硬编码
- **动态路由**：服务上下线时 Gateway 自动感知

### 架构变化

```
【Phase 2】
用户 → Gateway (:9000) → http://localhost:8080 (硬编码地址)

【Phase 3】
用户 → Gateway (:9000) → Nacos 查询 → chatbot-service 实例
                            │
                            ├→ 实例1: localhost:8080
                            ├→ 实例2: 192.168.1.10:8080
                            └→ 实例3: 192.168.1.11:8080
```

---

## 二、什么是 Nacos？

**Nacos** = **Na**ming + **Co**nfiguration **S**ervice

| 功能 | 说明 |
|------|------|
| **服务注册** | 服务启动时将自己的地址注册到 Nacos |
| **服务发现** | 消费者从 Nacos 查询提供者的地址 |
| **配置中心** | 集中管理各服务的配置（后续可用） |
| **健康检查** | 自动检测服务是否存活，下线不健康实例 |

### Nacos vs 其他注册中心

| 特性 | Nacos | Eureka | Consul | Zookeeper |
|------|-------|--------|--------|-----------|
| CP/AP | CP+AP 可切换 | AP | CP | CP |
| 配置中心 | ✅ 内置 | ❌ | ✅ | ❌ |
| 健康检查 | TCP/HTTP/MySQL | 客户端心跳 | TCP/HTTP/gRPC | Keep Alive |
| 管理界面 | ✅ 自带 | ✅ 自带 | ✅ 自带 | ❌ |
| 国内生态 | ⭐⭐⭐ | ⭐⭐ | ⭐ | ⭐⭐ |

---

## 三、前置环境

### 3.1 启动 Nacos

```bash
# 已在 docker-compose.yml 中配置
docker-compose up -d nacos

# 验证
curl http://localhost:8848/nacos/v1/console/health/readiness
# 返回 OK

# 管理界面
# http://localhost:8848/nacos
# 默认账号密码: nacos/nacos
```

---

## 四、代码改动详解

### 4.1 依赖添加

**主服务 (pom.xml)：**
```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-alibaba-dependencies</artifactId>
            <version>2023.0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Gateway (gateway/pom.xml)：**
```xml
<!-- Nacos 服务发现 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>

<!-- 负载均衡（Gateway 通过 lb:// 访问服务必须） -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>
```

### 4.2 主服务配置 (application.yml)

```yaml
spring:
  application:
    name: chatbot-service          # 服务名，Nacos 中注册的名称

  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848  # Nacos 地址
        namespace: public            # 命名空间
```

### 4.3 Gateway 配置 (gateway/application.yml)

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848

    gateway:
      # 开启服务发现路由
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true

      routes:
        - id: chat-service
          uri: lb://chatbot-service   # lb:// = 负载均衡 + 服务发现
          predicates:
            - Path=/api/chat/**
```

**关键变化：**
- `uri: http://localhost:8080` → `uri: lb://chatbot-service`
- `lb://` 表示通过负载均衡从 Nacos 获取服务实例地址
- Gateway 自动维护服务实例列表，无需手动配置

---

## 五、服务注册流程

```
1. 主服务启动
   │
   ▼
2. NacosDiscoveryAutoConfiguration 自动配置
   │
   ▼
3. 向 Nacos 发送注册请求
   POST http://localhost:8848/nacos/v1/ns/instance
   {
     "serviceName": "chatbot-service",
     "ip": "192.168.1.5",
     "port": 8080,
     "metadata": {...}
   }
   │
   ▼
4. Nacos 保存实例信息
   │
   ▼
5. 每 5 秒发送心跳保活
   PUT http://localhost:8848/nacos/v1/ns/instance/beat
   │
   ▼
6. Gateway 从 Nacos 查询服务实例
   GET http://localhost:8848/nacos/v1/ns/instance/list?serviceName=chatbot-service
   │
   ▼
7. Gateway 缓存实例列表，定期刷新（默认 30 秒）
```

---

## 六、运行和测试

### 6.1 启动顺序

```bash
# 1. 启动基础设施
docker-compose up -d

# 2. 启动主服务（会自动注册到 Nacos）
mvn spring-boot:run

# 3. 启动 Gateway
cd gateway && mvn spring-boot:run
```

### 6.2 验证服务注册

**方式一：Nacos 管理界面**
- 打开 http://localhost:8848/nacos
- 登录：nacos / nacos
- 查看"服务列表"，应该能看到 `chatbot-service` 和 `chatbot-gateway`

**方式二：API 查询**
```bash
# 查询已注册的服务
curl http://localhost:8848/nacos/v1/ns/service/list?pageNo=1\&pageSize=10

# 查询 chatbot-service 的实例
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=chatbot-service
```

**方式三：运行测试**
```bash
mvn test -Dtest=NacosDiscoveryTest
```

### 6.3 端到端验证

```bash
# 通过 Gateway 访问（Gateway 从 Nacos 获取后端地址）
curl http://localhost:9000/api/chat/health

# 应该正常返回，说明 Gateway → Nacos → 后端服务 链路通畅
```

---

## 七、负载均衡

当同一服务有多个实例时，Gateway 自动进行负载均衡：

```
chatbot-service 在 Nacos 注册了 3 个实例：
  - 192.168.1.10:8080
  - 192.168.1.11:8080
  - 192.168.1.12:8080

Gateway 收到请求后：
  请求1 → 192.168.1.10:8080
  请求2 → 192.168.1.11:8080
  请求3 → 192.168.1.12:8080
  请求4 → 192.168.1.10:8080 (轮询)
```

默认负载均衡策略：**轮询（Round Robin）**

### 自定义负载均衡

如需修改策略，可在 Gateway 配置中指定：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: chat-service
          uri: lb://chatbot-service
          predicates:
            - Path=/api/chat/**
          metadata:
            # 使用随机策略
            loadbalancer:
              random: true
```

---

## 八、健康检查

Nacos 会自动检测服务健康状态：

| 状态 | 含义 | 触发条件 |
|------|------|---------|
| **HEALTHY** | 健康 | 心跳正常 |
| **UNHEALTHY** | 不健康 | 心跳超时（默认 15 秒） |
| **DOWN** | 下线 | 服务主动注销或被剔除 |

**心跳机制：**
- 服务每 5 秒向 Nacos 发送一次心跳
- Nacos 超过 15 秒未收到心跳，标记为不健康
- 超过 30 秒未收到心跳，从实例列表中剔除

---

## 九、常见问题

### Q1: 服务注册失败？

```bash
# 检查 Nacos 是否运行
curl http://localhost:8848/nacos/v1/console/health/readiness

# 检查服务日志
# 应看到: nacos registry register chatbot-service success
```

### Q2: Gateway 无法发现服务？

1. 确认服务已注册到 Nacos（管理界面查看）
2. 确认 Gateway 的 `spring.cloud.nacos.discovery.server-addr` 配置正确
3. 确认 Gateway 的 `spring.cloud.gateway.discovery.locator.enabled=true`

### Q3: lb:// 报错？

确保 Gateway pom.xml 中引入了 `spring-cloud-starter-loadbalancer`。

### Q4: 本机开发，多个服务如何区分？

```bash
# 主服务用默认端口 8080
mvn spring-boot:run

# 第二个实例用不同端口
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
```

---

## 十、Nacos 管理界面

访问 http://localhost:8848/nacos

| 功能 | 路径 | 说明 |
|------|------|------|
| 服务列表 | 服务管理 → 服务列表 | 查看所有注册的服务 |
| 服务详情 | 点击服务名 | 查看实例列表、元数据 |
| 配置管理 | 配置管理 → 配置列表 | 管理各服务配置（后续可用） |
| 命名空间 | 命名空间 | 隔离不同环境（dev/test/prod） |

---

## 十一、下一步（Phase 4 预告）

Phase 4 将进行**微服务拆分**：
- 将 Auth、Chat、Knowledge 拆分为独立服务
- 各服务独立注册到 Nacos
- Kafka 用于服务间事件通信
- Gateway 根据路径路由到不同服务
