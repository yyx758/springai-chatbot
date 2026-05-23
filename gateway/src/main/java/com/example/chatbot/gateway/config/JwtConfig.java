package com.example.chatbot.gateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * JWT 配置和工具类
 * Gateway 层只需要解析 Token，不需要创建 Token
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
@Slf4j
public class JwtConfig {

    @Getter
    @Setter
    private String jwtSecret;

    @Getter
    @Setter
    private List<String> excludePaths = List.of();

    private SecretKey key;

    @PostConstruct
    public void init() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT 密钥长度至少 32 位");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("【Gateway】JWT 鉴权初始化完成");
        log.info("【Gateway】排除路径列表: {}", excludePaths);
    }

    /**
     * 解析 JWT Token，返回 Claims
     * 如果 Token 无效或过期，抛出异常
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Claims 中提取用户 ID
     */
    public Long getUserId(Claims claims) {
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 从 Claims 中提取用户名
     */
    public String getUsername(Claims claims) {
        return claims.get("username", String.class);
    }

    /**
     * 从 Claims 中提取角色
     */
    public String getRole(Claims claims) {
        return claims.get("role", String.class);
    }
}
