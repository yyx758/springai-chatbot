package com.example.chatbot.gateway.filter;

import com.example.chatbot.gateway.config.JwtConfig;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import java.nio.charset.StandardCharsets;

/**
 * 全局 JWT 鉴权过滤器
 *
 * 工作流程：
 * 1. 检查请求路径是否在排除列表中（如 /api/auth/login）
 * 2. 从 Authorization Header 提取 Bearer Token
 * 3. 解析 JWT，提取 userId、role
 * 4. 将用户信息通过 Header 传递给下游服务
 * 5. 对 /api/admin/** 路径额外校验 ADMIN 角色
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtConfig jwtConfig;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public int getOrder() {
        // 高优先级，在路由之前执行
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        String method = request.getMethod().name();

        // OPTIONS 请求放行（CORS 预检）
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return chain.filter(exchange);
        }

        // 检查是否在排除路径中
        if (isExcludePath(path)) {
            log.debug("【Gateway】放行排除路径: {} {}", method, path);
            return chain.filter(exchange);
        }

        // 提取 Authorization Header
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("【Gateway】未携带 Token: {} {}", method, path);
            return unauthorizedResponse(exchange, "未登录或登录已过期");
        }

        // 解析 JWT
        String token = authHeader.substring(7).trim();
        try {
            Claims claims = jwtConfig.parseToken(token);
            Long userId = jwtConfig.getUserId(claims);
            String username = jwtConfig.getUsername(claims);
            String role = jwtConfig.getRole(claims);

            // 检查管理员路径的角色要求
            if (path.startsWith("/api/admin/") && !"ADMIN".equals(role)) {
                log.warn("【Gateway】权限不足，需要 ADMIN 角色: userId={}, role={}", userId, role);
                return forbiddenResponse(exchange, "权限不足，需要 ADMIN 角色");
            }

            // 将用户信息注入请求 Header，传递给下游服务
            ServerHttpRequest mutatedRequest = request.mutate()
                    .header("X-Auth-UserId", String.valueOf(userId))
                    .header("X-Auth-Username", username)
                    .header("X-Auth-Role", role)
                    .build();

            log.debug("【Gateway】鉴权通过: userId={}, role={}, path={}", userId, role, path);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            log.warn("【Gateway】Token 解析失败: {} - {}", path, e.getMessage());
            return unauthorizedResponse(exchange, "登录状态无效，请重新登录");
        }
    }

    /**
     * 检查路径是否在排除列表中
     */
    private boolean isExcludePath(String path) {
        List<String> excludePaths = jwtConfig.getExcludePaths();
        if (excludePaths == null || excludePaths.isEmpty()) return false;
        return excludePaths.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 返回 401 未授权响应
     */
    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"error\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 返回 403 禁止访问响应
     */
    private Mono<Void> forbiddenResponse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"error\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
