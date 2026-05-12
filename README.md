# AI Studio — 智能客服聊天机器人

基于 Spring Boot + Spring AI + MyBatis-Plus 构建的智能客服系统，支持多模型 AI 对话、RAG 检索增强、邮箱验证码注册、RBAC 权限管理和管理后台。

## 目录

- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [功能列表](#功能列表),  
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [环境变量](#环境变量)
- [API 文档](#api-文档)
- [数据库设计](#数据库设计)
- [安全设计](#安全设计)
- [部署](#部署)

---

## 技术栈

| 类别 | 技术 | 说明 |
|------|------|------|
| 后端框架 | Spring Boot 3.2 + Java 17 | 主框架 |
| AI 集成 | Spring AI + DeepSeek + Ollama | 多模型对话、流式输出（SSE） |
| ORM | MyBatis-Plus 3.5 | 分页、Lambda 查询 |
| 数据库 | MySQL + Flyway 迁移 | 持久化 + 版本管理 |
| 缓存 | Redis | 会话缓存、Token 存储、验证码 |
| 认证安全 | JWT + BCrypt + RBAC | 双 Token 刷新、角色权限控制 |
| 邮件 | Spring Mail（SMTP） | 邮箱验证码注册 |
| 模板引擎 | Thymeleaf + Bootstrap 5 | 服务端渲染页面 |
| 运维 | Cloudflare Tunnel + Linux | 内网穿透暴露本地模型 |
| CI/CD | GitHub Actions | 自动构建、Docker 镜像 |

---

## 项目亮点

- **自研 RAG 检索增强**：基于关键词匹配评分算法，无需向量数据库即可实现知识库问答
- **双模型架构**：云端 DeepSeek + 本地 Ollama 互为备份，运行时自由切换
- **JWT 双令牌 + 静默刷新**：Access Token 30 分钟 + Refresh Token 7 天，Token 轮转防重放
- **注解驱动 RBAC**：自研 `@RequireRole` 注解，不依赖 Spring Security，轻量且灵活
- **Cloudflare Tunnel 内网穿透**：QUIC 协议 + HTTP/2 多路复用，无需公网 IP 即可对外提供本地 AI 服务

---

## 系统架构

### 整体架构图

```
                      互联网用户
                          │
                          ▼
                  ┌───────────────┐
                  │  Cloudflare    │
                  │  Tunnel        │
                  │  (内网穿透)     │
                  └───┬───────┬───┘
                      │       │
            ollama.域名  │       │  app.域名 或 IP:8080
                      │       │
                      ▼       ▼
        ┌──────────────────────────────────┐
        │         远程 Linux 服务器          │
        │                                  │
        │  ┌──────────┐   ┌────────────┐  │
        │  │  Ollama   │   │ Spring Boot │  │
        │  │ :11434    │◄──│ :8080       │  │
        │  │ qwen2.5   │   │ (本项目)    │  │
        │  └──────────┘   └──┬───┬─────┘  │
        │                    │   │         │
        │              ┌─────┘   └────┐    │
        │              ▼              ▼    │
        │        ┌──────────┐  ┌────────┐  │
        │        │  MySQL    │  │  Redis  │  │
        │        │  :3306    │  │  :6379  │  │
        │        └──────────┘  └────────┘  │
        └──────────────────────────────────┘
                      │
                      ▼
              ┌───────────────┐
              │  DeepSeek API  │  (云端大模型，可选)
              │  api.deepseek  │
              └───────────────┘
```

### 模型访问路径

| 模型 | 运行位置 | 访问方式 | 说明 |
|------|---------|---------|------|
| **Ollama** (qwen2.5) | 远程 Linux 服务器本地 | `https://ollama.你的域名` → Cloudflare Tunnel → `localhost:11434` | 本地模型，通过 CF Tunnel 暴露给应用内调用 |
| **DeepSeek** | 云端 API | `https://api.deepseek.com` | 云端大模型，直接网络访问 |

### 关键设计

- **Ollama 本地运行**在远程 Linux 服务器上（端口 11434），不直接暴露给互联网
- **Cloudflare Tunnel** 将 Ollama API 通过域名 `ollama.你的域名` 安全暴露，无需公网 IP
- Spring Boot 应用通过 Cloudflare Tunnel 域名访问 Ollama（配置在 `spring.ai.ollama.base-url`）
- 用户访问应用通过 `http://服务器IP:8080` 或额外的 Tunnel 域名
- 双模型互为备份：Ollama 本地模型离线可用，DeepSeek 云端模型提供更强能力

---

## 功能列表

### AI 对话

- **双模型支持**：DeepSeek（云端大模型）+ Ollama（本地模型），运行时切换
- **同步对话**：`POST /api/chat/message`，返回完整回复
- **流式对话**：`POST /api/chat/stream`，SSE 协议，打字机效果
- **上下文记忆**：多轮对话上下文，可配置最大历史轮数
- **会话管理**：多会话隔离，会话历史查询与删除

### RAG 检索增强

- **本地关键词召回**：无需向量数据库，纯文本匹配评分
- **召回算法**：标题匹配 +40，正文匹配 +30，标签匹配 +20，子词加权
- **可配置 Top-K**：支持 1/3/5 个召回结果
- **降级保护**：检索失败时自动降级为普通对话

### 用户认证

- **邮箱验证码注册**：SMTP 发送 6 位验证码，5 分钟有效，60 秒发送间隔
- **JWT 双令牌**：Access Token（30 分钟） + Refresh Token（7 天）
- **Token 轮转**：刷新时旧 Refresh Token 被原子删除，防止重放攻击
- **静默刷新**：前端 `authFetch()` 遇到 401 自动刷新令牌，用户无感知

### RBAC 权限管理

- **双角色**：USER（普通用户）、ADMIN（管理员）
- **注解驱动**：`@RequireRole("ADMIN")` 类级/方法级注解
- **拦截器检查**：基于 `HandlerInterceptor`，不引入 Spring Security

### 管理后台

- **仪表盘**：用户总数、对话总数、知识文档数
- **用户管理**：角色切换、启用/禁用、删除
- **文档管理**：查看/删除所有用户的知识文档

### 知识库管理

- 用户级文档 CRUD
- 标签系统
- 启用/禁用控制
- 检索效果测试

### 运维

- Flyway 数据库自动迁移
- 全局异常处理和参数校验
- 健康检查端点
- GitHub Actions CI/CD
- 所有敏感值环境变量注入

---

## 项目结构

```
src/main/java/com/example/chatbot/
├── ChatbotApplication.java              # Spring Boot 主入口
├── config/
│   ├── GlobalExceptionHandler.java      # 全局异常处理（400/401/403/500）
│   ├── MybatisPlusConfig.java           # MyBatis-Plus 分页插件
│   ├── RedisConfig.java                 # Redis 序列化配置
│   ├── SecurityBeansConfig.java         # BCryptPasswordEncoder Bean
│   └── WebMvcConfig.java                # 拦截器注册
├── controller/
│   ├── AdminController.java             # 管理 API（@RequireRole("ADMIN")）
│   ├── AuthController.java              # 认证 API（登录/注册/刷新/验证码）
│   ├── ChatbotController.java           # 聊天 API（同步/流式/历史）
│   ├── KnowledgeController.java         # 知识库 API（CRUD/检索）
│   └── PageController.java              # 页面路由（/ /login /chat /admin）
├── dto/
│   ├── AdminStatsResponse.java          # 管理统计响应
│   ├── AuthResponse.java                # 认证响应（含双 token）
│   ├── ChatRequest.java                 # 聊天请求
│   ├── ChatResponse.java                # 聊天响应
│   ├── KnowledgeDocumentCreateRequest.java  # 文档创建请求
│   ├── KnowledgeSearchRequest.java      # 知识检索请求
│   ├── LoginRequest.java                # 登录请求
│   ├── RagReference.java                # RAG 引用片段
│   ├── RefreshTokenRequest.java         # 刷新令牌请求
│   ├── RegisterRequest.java             # 注册请求（含邮箱验证码）
│   ├── SendCodeRequest.java             # 发送验证码请求
│   ├── UpdateEnabledRequest.java        # 状态更新请求
│   ├── UpdateRoleRequest.java           # 角色更新请求
│   └── UserDto.java                     # 用户信息 DTO
├── entity/
│   ├── ChatRecord.java                  # 聊天记录实体
│   ├── KnowledgeDocument.java           # 知识文档实体
│   └── UserAccount.java                 # 用户账户实体
├── mapper/
│   ├── ChatRecordMapper.java            # 聊天记录 Mapper
│   ├── KnowledgeDocumentMapper.java     # 知识文档 Mapper
│   └── UserAccountMapper.java           # 用户 Mapper
├── security/
│   ├── AuthInterceptor.java             # 认证拦截器（JWT + RBAC）
│   ├── ForbiddenException.java          # 403 权限异常
│   ├── JwtTokenProvider.java            # JWT 令牌提供者
│   ├── RefreshTokenStore.java           # Redis 刷新令牌存储
│   └── RequireRole.java                 # 角色注解
└── service/
    ├── AdminService.java                # 管理员业务逻辑
    ├── AuthService.java                 # 认证业务逻辑
    ├── ChatbotService.java              # 聊天核心逻辑（AI + RAG + 历史）
    ├── EmailService.java                # 邮件验证码服务
    └── RagService.java                  # RAG 检索评分引擎

src/main/resources/
├── application.yml                      # 应用配置
├── db/migration/                        # Flyway 迁移脚本
│   ├── V1__create_user_account_table.sql
│   ├── V2__create_knowledge_document_table.sql
│   ├── V3__add_role_and_enabled_to_user_account.sql
│   └── V4__add_email_to_user_account.sql
└── templates/                           # Thymeleaf 模板
    ├── login.html                       # 登录注册页面
    ├── chat.html                        # 聊天主界面
    └── admin.html                       # 管理后台
```

---

## 快速开始

### 环境要求

- JDK 17+
- MySQL 8.0+
- Redis 6.0+
- Maven 3.8+

### 1. 初始化数据库

创建 MySQL 数据库（Flyway 会自动建表）：

```sql
CREATE DATABASE IF NOT EXISTS chatbot DEFAULT CHARSET utf8mb4;
```

### 2. 配置环境变量

```bash
# AI 模型密钥
export DEEPSEEK_API_KEY="你的 DeepSeek API Key"
export SPRING_AI_OPENAI_ENABLED=true

# JWT 密钥（至少 32 位随机字符串）
export APP_JWT_SECRET="你的JWT密钥-至少32个字符-请使用随机字符串"

# SMTP 邮件（QQ 邮箱为例）
export SMTP_HOST="smtp.qq.com"
export SMTP_PORT="587"
export SMTP_USERNAME="你的QQ号@qq.com"
export SMTP_PASSWORD="QQ邮箱授权码"
```

### 3. 修改数据库和 Redis 连接

编辑 `src/main/resources/application.yml`：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/chatbot?...
    username: root
    password: 你的MySQL密码
  data:
    redis:
      host: localhost
      port: 6379
      password: 你的Redis密码
```

### 4. 启动项目

```bash
mvn spring-boot:run
```

### 5. 访问

打开浏览器访问 `http://localhost:8080/`

### 6. 设置管理员

先用邮箱注册一个账号，再在数据库中设为管理员：

```sql
UPDATE user_account SET role = 'ADMIN' WHERE username = '你的用户名';
```

之后登录时选择"管理员"身份即可进入管理后台。

---

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DEEPSEEK_API_KEY` | — | DeepSeek API 密钥 |
| `SPRING_AI_OPENAI_ENABLED` | false | 是否启用 DeepSeek |
| `APP_JWT_SECRET` | `change-this...` | JWT 签名密钥（生产环境必须修改） |
| `APP_TOKEN_EXPIRE_MS` | 1800000 | Access Token 过期时间（毫秒） |
| `APP_REFRESH_TOKEN_EXPIRE_MS` | 604800000 | Refresh Token 过期时间（毫秒） |
| `SMTP_HOST` | smtp.qq.com | SMTP 服务器地址 |
| `SMTP_PORT` | 587 | SMTP 端口 |
| `SMTP_USERNAME` | — | 发件邮箱地址 |
| `SMTP_PASSWORD` | — | 发件邮箱授权码 |

### SMTP 授权码获取（QQ 邮箱）

1. 登录 [QQ 邮箱](https://mail.qq.com) → 设置 → 账户
2. 找到 POP3/SMTP 服务 → 点击开启
3. 按提示发送短信 → 获得 16 位授权码
4. 将授权码填入 `SMTP_PASSWORD`

| 其他邮箱 | SMTP 地址 | 端口 |
|----------|----------|------|
| 163 邮箱 | smtp.163.com | 465 |
| Gmail | smtp.gmail.com | 587 |
| Outlook | smtp.office365.com | 587 |

---

## API 文档

### 基础 URL

```
http://localhost:8080
```

### 认证说明

- 认证方式：请求头 `Authorization: Bearer <access_token>`
- Access Token 过期时间：30 分钟
- Refresh Token 过期时间：7 天
- 常见 HTTP 状态码：200 成功、400 参数错误、401 未登录/token 过期、403 权限不足、500 服务器错误

### 页面路由

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 登录注册页 |
| GET | `/login` | 登录注册页（同 /） |
| GET | `/chat` | 聊天界面 |
| GET | `/admin` | 管理后台（需 ADMIN） |

### 认证接口 `/api/auth`

不校验认证：

```
POST /api/auth/send-code   发送邮箱验证码（60 秒内同邮箱只能发一次）
POST /api/auth/register    注册新用户
POST /api/auth/login       用户登录
POST /api/auth/refresh     刷新令牌（Token 轮转）
```

需要认证：

```
GET  /api/auth/me          获取当前用户信息
```

**send-code** — 请求体：
```json
{ "email": "user@example.com" }
```

**register** — 请求体：
```json
{
  "username": "test",
  "email": "user@example.com",
  "code": "824719",
  "password": "123456",
  "displayName": "测试用户"
}
```

**login** — 请求体：
```json
{
  "username": "test",
  "password": "123456"
}
```

**login / register / refresh** — 响应体：
```json
{
  "userId": 1,
  "username": "test",
  "displayName": "测试用户",
  "token": "eyJhbGciOi...",
  "refreshToken": "a1b2c3d4...",
  "expiresIn": 1800
}
```

### 聊天接口 `/api/chat`

全部需要认证：

```
POST /api/chat/message              同步聊天
POST /api/chat/stream               SSE 流式聊天
GET  /api/chat/records              聊天记录（分页   ?page=1&size=10）
GET  /api/chat/stats                个人统计
GET  /api/chat/history/{sessionId}  会话历史
DELETE /api/chat/{sessionId}         删除会话
```

无需认证：

```
GET  /api/chat/health               健康检查
```

**chat/message** — 请求体：
```json
{
  "message": "你好",
  "sessionId": "1_session123",
  "model": "deepseek",
  "useRag": true,
  "ragTopK": 3
}
```

### 知识库接口 `/api/knowledge`

全部需要认证：

```
POST   /api/knowledge/documents              创建文档
GET    /api/knowledge/documents              文档列表（分页 ?page=1&size=10）
DELETE /api/knowledge/documents/{id}          删除文档
POST   /api/knowledge/search                 检索测试
```

**创建文档** — 请求体：
```json
{
  "title": "产品说明",
  "content": "这是产品的详细说明...",
  "tags": "产品,说明",
  "enabled": true
}
```

### 管理接口 `/api/admin`

全部需要认证 + ADMIN 角色：

```
GET    /api/admin/stats                        系统统计
GET    /api/admin/users                        用户列表（分页 ?page=1&size=20）
PUT    /api/admin/users/{id}/role              修改角色
PUT    /api/admin/users/{id}/enabled            启/禁用用户
DELETE /api/admin/users/{id}                    删除用户
GET    /api/admin/documents                    全部文档（分页）
DELETE /api/admin/documents/{id}                删除文档
```

**修改角色** — 请求体：
```json
{ "role": "ADMIN" }
```

**启/禁用** — 请求体：
```json
{ "enabled": false }
```

---

## 数据库设计

### user_account

| 列 | 类型 | 约束 | 说明 |
|-----|------|------|------|
| id | BIGINT | PK, AUTO_INCREMENT | 主键 |
| username | VARCHAR(64) | NOT NULL, UNIQUE | 用户名 |
| email | VARCHAR(128) | NULL, UNIQUE | 邮箱（V4 新增） |
| password_hash | VARCHAR(255) | NOT NULL | BCrypt 密码哈希 |
| display_name | VARCHAR(64) | NOT NULL | 显示名称 |
| role | VARCHAR(16) | NOT NULL, DEFAULT 'USER' | 角色：USER / ADMIN |
| enabled | TINYINT(1) | NOT NULL, DEFAULT 1 | 是否启用 |
| created_time | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 创建时间 |
| updated_time | TIMESTAMP | ON UPDATE CURRENT_TIMESTAMP | 更新时间 |

### chat_record

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT | 主键 |
| user_message | TEXT | 用户消息 |
| bot_response | TEXT | AI 回复 |
| session_id | VARCHAR(128) | 会话 ID（格式：`{userId}_{uuid}`） |
| created_time | TIMESTAMP | 创建时间 |

### knowledge_document

| 列 | 类型 | 说明 |
|-----|------|------|
| id | BIGINT | 主键 |
| user_id | BIGINT | 所属用户 ID |
| title | VARCHAR(128) | 文档标题 |
| content | TEXT | 文档正文 |
| tags | VARCHAR(256) | 标签 |
| enabled | TINYINT(1) | 是否启用，默认 1 |
| created_time | TIMESTAMP | 创建时间 |
| updated_time | TIMESTAMP | 更新时间 |

### Redis 键设计

| Key 模式 | 用途 | TTL |
|----------|------|-----|
| `refresh_token:{tokenId}` | 刷新令牌 → userId | 7 天 |
| `email_code:{email}` | 邮箱验证码 | 5 分钟 |
| `email_send_interval:{email}` | 发送间隔锁 | 60 秒 |
| `chat:history:{sessionId}` | 聊天历史缓存 | 2 小时 |

---

## 安全设计

### 认证流程

```
1. 注册: 邮箱 → 发送验证码 → 填写验证码 + 用户名 + 密码 → 创建用户
2. 登录: 用户名 + 密码 → 返回 AccessToken + RefreshToken
3. 请求: 携带 Authorization: Bearer <AccessToken>
4. 过期: 401 → 前端自动用 RefreshToken 刷新 → 获得新 Token 对 → 重试请求
5. 退出: 清除前端 Token，跳转登录页
```

### 安全措施

| 措施 | 实现 |
|------|------|
| 密码加密 | BCrypt（spring-security-crypto） |
| 验证码有效期 | Redis TTL 5 分钟自动过期 |
| 验证码一次性 | 验证通过后立即删除 |
| 发送频率限制 | Redis SETNX，60 秒间隔 |
| 邮箱唯一 | MySQL 唯一索引，一邮箱一账号 |
| Token 轮转 | Redis `getAndDelete` 原子操作 |
| 角色检查 | `@RequireRole` 注解 + Interceptor |
| 管理员自保护 | 不允许删除自己的账户 |
| 用户禁用 | 开启后登录被拒 + RefreshToken 可被吊销 |
| 防并发刷新 | 前端 `refreshPromise` 互斥锁 |

---

## 部署

### 部署架构说明

本项目部署在一台远程 Linux 服务器上，Ollama 模型运行在服务器本地，通过 Cloudflare Tunnel 内网穿透暴露 Ollama API 给应用调用。

```
用户浏览器 ──→ http://服务器IP:8080 ──→ Spring Boot 应用
                                          │
                              ┌───────────┼───────────┐
                              │           │           │
                              ▼           ▼           ▼
                           MySQL      Redis       Ollama
                           :3306      :6379       :11434
                                                    │
                                                    ▼
                              Cloudflare Tunnel ──→ ollama.域名
                              (QUIC 协议，实现在线调用本地模型)
```

### 一、远程 Linux 服务器环境准备

#### 1. 安装必要软件

```bash
# JDK 17
sudo apt install openjdk-17-jdk -y

# MySQL 8.0
sudo apt install mysql-server -y
sudo mysql_secure_installation

# Redis
sudo apt install redis-server -y
sudo systemctl enable redis-server

# Maven
sudo apt install maven -y

# Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Cloudflare Tunnel (cloudflared)
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o /usr/local/bin/cloudflared
chmod +x /usr/local/bin/cloudflared
```

#### 2. 初始化服务

```bash
# 启动 MySQL 并创建数据库
sudo systemctl start mysql
sudo mysql -e "CREATE DATABASE IF NOT EXISTS chatbot DEFAULT CHARSET utf8mb4;"
sudo mysql -e "ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY '你的密码';"

# 启动 Redis
sudo systemctl start redis-server

# 拉取 Ollama 模型
ollama pull qwen2.5:0.5b
```

### 二、Cloudflare Tunnel 配置

Cloudflare Tunnel 用于将 Ollama API 安全地暴露到公网，无需服务器有公网 IP。

#### 1. 登录并创建 Tunnel

```bash
cloudflared tunnel login        # 打开浏览器登录 CF 账号
cloudflared tunnel create ollama-tunnel
cloudflared tunnel list         # 记下 Tunnel ID
```

#### 2. 创建配置文件

编辑 `~/.cloudflared/config.yml`：

```yaml
tunnel: 你的Tunnel-ID
credentials-file: /root/.cloudflared/你的Tunnel-ID.json

protocol: quic              # QUIC 协议，比 HTTP/2 更快
no-tls-verify: false
retries: 3
grace-period: 30s

ha-connections: 4           # 4 个并发连接提高稳定性

loglevel: info

ingress:
  - hostname: ollama.你的域名.com
    service: http://localhost:11434
    originRequest:
      connectTimeout: 30s
      disableChunkedEncoding: false  # 流式响应必需
      http2Origin: true              # HTTP/2 多路复用
      keepAliveConnections: 100
      keepAliveTimeout: 90s
      tcpKeepAlive: 30s

  - service: http_status:404        # 默认规则（必需）
```

#### 3. 配置 DNS 并启动

```bash
cloudflared tunnel route dns ollama-tunnel ollama.你的域名.com
cloudflared service install        # 安装为系统服务
cloudflared service start          # 启动 Tunnel（后台运行）

# 验证
curl https://ollama.你的域名.com/api/tags
```

#### 4. 修改 application.yml

```yaml
spring:
  ai:
    ollama:
      base-url: https://ollama.你的域名.com   # 指向 Cloudflare Tunnel 域名
```

### 三、部署应用

#### 方式一：JAR 包直接运行

```bash
# 在服务器上克隆项目
git clone <your-repo-url>
cd springaI-chatbot

# 打包
mvn clean package -DskipTests

# 配置环境变量并启动
export DEEPSEEK_API_KEY="你的密钥"
export SPRING_AI_OPENAI_ENABLED=true
export APP_JWT_SECRET="随机32位以上字符串"
export SMTP_HOST="smtp.qq.com"
export SMTP_USERNAME="你的QQ号@qq.com"
export SMTP_PASSWORD="QQ邮箱授权码"

nohup java -jar target/springai-chatbot-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```

#### 方式二：Docker 部署

```bash
mvn clean package -DskipTests
docker build -t ai-studio .

docker run -d --name ai-studio \
  --network host \
  -e DEEPSEEK_API_KEY="xxx" \
  -e SPRING_AI_OPENAI_ENABLED=true \
  -e APP_JWT_SECRET="xxx" \
  -e SMTP_HOST="smtp.qq.com" \
  -e SMTP_USERNAME="xxx" \
  -e SMTP_PASSWORD="xxx" \
  ai-studio
```

> 使用 `--network host` 可以访问宿主机上的 MySQL、Redis 和 Ollama。

#### 方式三：应用也通过 Cloudflare Tunnel 暴露

如果希望用户通过域名访问应用（而非 IP:8080），可新增一条 ingress 规则：

```yaml
ingress:
  - hostname: app.你的域名.com
    service: http://localhost:8080
  - hostname: ollama.你的域名.com
    service: http://localhost:11434
  # ...
```

然后配置 DNS：

```bash
cloudflared tunnel route dns ollama-tunnel app.你的域名.com
```

用户即可通过 `https://app.你的域名.com` 访问应用。

### 四、服务管理

```bash
# 查看应用日志
tail -f app.log

# 查看 Ollama 状态
ollama list
curl http://localhost:11434/api/tags

# 查看 Cloudflare Tunnel 状态
cloudflared tunnel info ollama-tunnel
cloudflared tunnel list

# 查看 MySQL 状态
sudo systemctl status mysql

# 查看 Redis 状态
sudo systemctl status redis-server
```

### 注意事项

1. **生产环境必须修改** `APP_JWT_SECRET` 为随机长字符串（≥ 32 位）
2. 数据库密码和 Redis 密码不要硬编码，使用环境变量
3. DeepSeek 默认关闭，需设置 `SPRING_AI_OPENAI_ENABLED=true`
4. SMTP 必须正确配置，否则注册功能不可用
5. Cloudflare Tunnel 的 QUIC 协议在某些防火墙下可能被阻，可回退为 `protocol: http2`
6. Ollama 模型首次推理较慢，建议服务器配置 GPU 或使用更高性能 CPU
7. 如果服务器内存有限，`qwen2.5:0.5b` 是最轻量选择，也可换成其他小模型
