package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.chatbot.dto.AuthResponse;
import com.example.chatbot.dto.LoginRequest;
import com.example.chatbot.dto.RegisterRequest;
import com.example.chatbot.entity.UserAccount;
import com.example.chatbot.mapper.UserAccountMapper;
import com.example.chatbot.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserAccountMapper userAccountMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.getUsername().trim();
        String password = request.getPassword();
        UserAccount existed = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username));
        if (existed != null) {
            throw new IllegalArgumentException("用户名已存在");
        }

        String displayName = request.getDisplayName();
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }

        UserAccount user = UserAccount.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(displayName.trim())
                .build();
        userAccountMapper.insert(user);

        return buildAuthResponse(user, jwtTokenProvider.createToken(user.getId(), user.getUsername()));
    }

    public AuthResponse login(LoginRequest request) {
        String username = request.getUsername().trim();
        String password = request.getPassword();
        UserAccount user = userAccountMapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, username));
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return buildAuthResponse(user, jwtTokenProvider.createToken(user.getId(), user.getUsername()));
    }

    public AuthResponse getProfile(Long userId) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("当前用户不存在");
        }
        return buildAuthResponse(user, null);
    }

    private AuthResponse buildAuthResponse(UserAccount user, String token) {
        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .token(token)
                .build();
    }
}
