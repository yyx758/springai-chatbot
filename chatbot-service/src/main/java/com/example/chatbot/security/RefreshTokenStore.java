package com.example.chatbot.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.auth.refresh-token-expire-ms:604800000}")
    private long refreshTokenExpireMs;

    private static final String KEY_PREFIX = "refresh_token:";
    private static final String USER_INDEX_PREFIX = "refresh_token_user:";

    public void store(String token, Long userId) {
        String key = KEY_PREFIX + token;
        redisTemplate.opsForValue().set(key, userId, refreshTokenExpireMs, TimeUnit.MILLISECONDS);
        String userIndexKey = USER_INDEX_PREFIX + userId;
        redisTemplate.opsForSet().add(userIndexKey, token);
        redisTemplate.expire(userIndexKey, refreshTokenExpireMs, TimeUnit.MILLISECONDS);
    }

    public Long getUserIdAndInvalidate(String token) {
        String key = KEY_PREFIX + token;
        Object userId = redisTemplate.opsForValue().getAndDelete(key);
        if (userId == null) {
            return null;
        }
        redisTemplate.opsForSet().remove(USER_INDEX_PREFIX + userId, token);
        return Long.valueOf(String.valueOf(userId));
    }

    public void revokeAllForUser(Long userId) {
        String userIndexKey = USER_INDEX_PREFIX + userId;
        java.util.Set<Object> tokens = redisTemplate.opsForSet().members(userIndexKey);
        if (tokens != null) {
            tokens.forEach(token -> redisTemplate.delete(KEY_PREFIX + token));
        }
        redisTemplate.delete(userIndexKey);
    }
}
