package com.example.chatbot.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${app.auth.refresh-token-expire-ms:604800000}")
    private long refreshTokenExpireMs;

    private static final String KEY_PREFIX = "refresh_token:";

    public void store(String token, Long userId) {
        String key = KEY_PREFIX + token;
        redisTemplate.opsForValue().set(key, userId, refreshTokenExpireMs, TimeUnit.MILLISECONDS);
    }

    public Long getUserIdAndInvalidate(String token) {
        String key = KEY_PREFIX + token;
        Object userId = redisTemplate.opsForValue().getAndDelete(key);
        if (userId == null) {
            return null;
        }
        return Long.valueOf(String.valueOf(userId));
    }

    public void revokeAllForUser(Long userId) {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                Object stored = redisTemplate.opsForValue().get(key);
                if (stored != null && String.valueOf(stored).equals(String.valueOf(userId))) {
                    redisTemplate.delete(key);
                }
            }
        }
    }
}
