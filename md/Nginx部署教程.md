# Nginx 部署教程

本文档记录 AI Studio 项目使用 Nginx 反向代理的完整配置过程。

## 一、Nginx 安装

服务器上使用的是手动编译安装的 Nginx，路径为 `/usr/local/nginx/`。

```bash
# 下载 Nginx 源码
wget http://nginx.org/download/nginx-1.26.2.tar.gz
tar -zxvf nginx-1.26.2.tar.gz
cd nginx-1.26.2

# 编译安装
./configure --prefix=/usr/local/nginx --with-http_ssl_module --with-http_stub_status_module
make && make install

# 验证
/usr/local/nginx/sbin/nginx -v
```

## 二、核心配置

配置文件路径：`/usr/local/nginx/conf/nginx.conf`

完整配置见 [`nginx-config/ai-studio.conf`](../nginx-config/ai-studio.conf)。

### 关键配置说明

#### 1. 限流

```nginx
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;

location /api/ {
    limit_req zone=api_limit burst=5 nodelay;
}
```

- 每个 IP 每秒最多 10 个 API 请求
- 突发队列 5 个，超出直接返回 503
- 防止恶意刷接口

#### 2. SSE 流式响应

```nginx
location /api/ {
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 600s;
    chunked_transfer_encoding on;
}
```

- `proxy_buffering off` 是 SSE 流式输出的关键，否则 Nginx 会缓冲响应导致前端收不到实时数据
- `proxy_read_timeout 600s` 保证长连接不会被 Nginx 提前断开（AI 回复可能需要较长时间）

#### 3. 端口隐藏

后端 Spring Boot 绑定 `127.0.0.1:8080`，外网无法直接访问：

```yaml
# application.yml
server:
  port: 8080
  address: 127.0.0.1
```

所有流量必须经过 Nginx 的 80 端口，实现统一入口。

#### 4. URL 映射

| 用户访问 | 实际转发 | 说明 |
|---------|---------|------|
| `http://IP/` | `http://127.0.0.1:8080/chat` | 首页直接进入聊天界面 |
| `http://IP/chat` | 301 → `/` | 避免重复路径 |
| `http://IP/login` | `http://127.0.0.1:8080/login` | 登录页 |
| `http://IP/admin` | `http://127.0.0.1:8080/admin` | 管理后台 |
| `http://IP/api/*` | `http://127.0.0.1:8080/api/*` | API 接口（带限流） |

#### 5. 安全头

```nginx
server_tokens off;
```

隐藏 Nginx 版本号，防止攻击者根据版本漏洞定向攻击。

## 三、Favicon 配置

Nginx worker 进程以 `nobody` 用户运行，无法读取 `/home/yyx758/` 目录下的文件。解决方案：

```bash
# 将 favicon 复制到 Nginx 可读的目录
cp /home/yyx758/static/favicon.ico /usr/local/nginx/html/favicon.ico
```

```nginx
location = /favicon.ico {
    root /usr/local/nginx/html;
    access_log off;
    log_not_found off;
}
```

## 四、启动与管理

```bash
# 检测配置语法
/usr/local/nginx/sbin/nginx -t

# 启动
/usr/local/nginx/sbin/nginx

# 重新加载配置（不中断服务）
/usr/local/nginx/sbin/nginx -s reload

# 停止
/usr/local/nginx/sbin/nginx -s stop

# 查看进程
ps aux | grep nginx
```

## 五、验证

```bash
# 测试首页是否转发到 /chat
curl -I http://localhost/
# 应返回 200，内容是 chat.html

# 测试 /chat 301 跳转
curl -I http://localhost/chat
# 应返回 301 → /

# 测试 API 限流
for i in $(seq 1 20); do curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/chat/health; done
# 前 10 个返回 200，后续返回 503

# 测试 SSE 流式（需要有效 token）
curl -N -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"message":"你好","model":"deepseek"}' \
  http://localhost/api/chat/stream
# 应看到 data: 逐行返回
```

## 六、常见问题

### 1. SSE 流式响应被缓冲

**症状**：前端收不到实时数据，等全部生成完才一次性显示。

**解决**：确保 `proxy_buffering off;` 已配置在 `/api/` 的 location 中。

### 2. 502 Bad Gateway

**原因**：后端 Spring Boot 未启动或端口不对。

**排查**：
```bash
curl http://127.0.0.1:8080/chat/health
# 如果不通，检查 Spring Boot 是否在运行
```

### 3. favicon 403 Forbidden

**原因**：Nginx worker 用户 `nobody` 无权读取文件。

**解决**：将 favicon 复制到 `/usr/local/nginx/html/`。

### 4. 限流过于严格

修改 `rate=10r/s` 和 `burst=5` 的值，或对特定 IP 添加白名单：
```nginx
# 白名单不限流
geo $limit {
    default 1;
    127.0.0.1 0;
}
map $limit $api_key {
    0 "";
    1 $binary_remote_addr;
}
limit_req_zone $api_key zone=api_limit:10m rate=10r/s;
```
