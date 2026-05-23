package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.dto.AuthResponse;
import com.example.chatbot.dto.LoginRequest;
import com.example.chatbot.dto.RegisterRequest;
import com.example.chatbot.entity.UserAccount;
import com.example.chatbot.kafka.NotificationEvent;
import com.example.chatbot.kafka.NotificationEventProducer;
import com.example.chatbot.mapper.UserAccountMapper;
import com.example.chatbot.security.JwtTokenProvider;
import com.example.chatbot.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;

    private final EmailService emailService;
    private final NotificationEventProducer notificationEventProducer;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim().toLowerCase();
        String password = request.getPassword();

        UserAccount existed = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username));
        if (existed != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        UserAccount emailExisted = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, email));
        if (emailExisted != null) {
            throw new IllegalArgumentException("该邮箱已被注册");
        }

        if (!emailService.verifyCode(email, request.getCode())) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }

        UserAccount user = UserAccount.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(displayName.trim())
                .role("USER")
                .enabled(true)
                .build();
        userAccountMapper.insert(user);

        return buildAuthResponse(user, createTokenPair(user));
    }

    public AuthResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        String password = request.getPassword();
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        if (!Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalStateException("账号已被禁用，请联系管理员");
        }
        return buildAuthResponse(user, createTokenPair(user));
    }

    public void sendForgotPasswordCode(String email) {
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, email.toLowerCase()));
        if (user == null) {
            throw new IllegalArgumentException("该邮箱未注册");
        }
        // 通过 Kafka 异步发送重置密码验证码
        notificationEventProducer.sendNotificationEvent(NotificationEvent.builder()
                .eventType("SEND_RESET_CODE")
                .toEmail(email)
                .eventTime(LocalDateTime.now())
                .build());
    }

    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        String lowerEmail = email.trim().toLowerCase();
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getEmail, lowerEmail));
        if (user == null) {
            throw new IllegalArgumentException("该邮箱未注册");
        }
        if (!emailService.verifyResetCode(lowerEmail, code)) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userAccountMapper.updateById(user);
        refreshTokenStore.revokeAllForUser(user.getId());
    }

    public AuthResponse getProfile(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("当前用户不存在");
        }
        return buildAuthResponse(user, null);
    }

    public AuthResponse refreshAccessToken(String refreshTokenValue) {
        if (refreshTokenValue == null || refreshTokenValue.isBlank()) {
            throw new IllegalArgumentException("刷新令牌不能为空");
        }
        Long userId = refreshTokenStore.getUserIdAndInvalidate(refreshTokenValue);
        if (userId == null) {
            throw new IllegalStateException("刷新令牌无效或已过期");
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            throw new IllegalStateException("用户不存在或已禁用");
        }
        return buildAuthResponse(user, createTokenPair(user));
    }

    private TokenPair createTokenPair(UserAccount user) {
        String accessToken = jwtTokenProvider.createAccessToken(
                user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken();
        refreshTokenStore.store(refreshToken, user.getId());
        return new TokenPair(accessToken, refreshToken, jwtTokenProvider.getTokenExpireMs() / 1000);
    }

    private record TokenPair(String accessToken, String refreshToken, long expiresIn) {}

    private AuthResponse buildAuthResponse(UserAccount user, TokenPair tokenPair) {
        AuthResponse.AuthResponseBuilder builder = AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .role(user.getRole());
        if (tokenPair != null) {
            builder.token(tokenPair.accessToken())
                    .refreshToken(tokenPair.refreshToken())
                    .expiresIn(tokenPair.expiresIn());
        }
        return builder.build();
    }
}
