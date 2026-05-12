package com.example.chatbot.controller;

import com.example.chatbot.dto.*;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.service.AuthService;
import com.example.chatbot.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;
    private final EmailService emailService;

    @PostMapping("/send-code")
    public ResponseEntity<Map<String, Object>> sendVerificationCode(@Valid @RequestBody SendCodeRequest request) {
        emailService.sendVerificationCode(request.getEmail().trim().toLowerCase());
        return ResponseEntity.ok(Map.of("success", true, "message", "验证码已发送"));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshAccessToken(request.getRefreshToken()));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.sendForgotPasswordCode(request.getEmail().trim().toLowerCase());
        return ResponseEntity.ok(Map.of("success", true, "message", "重置密码验证码已发送"));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("success", true, "message", "密码已重置，请重新登录"));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        return ResponseEntity.ok(authService.getProfile(Long.valueOf(String.valueOf(userId))));
    }
}
