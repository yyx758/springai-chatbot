package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {

    private Long userId;
    private String username;
    private String displayName;
    private String role;
    private String token;
    private String refreshToken;
    private Long expiresIn;

    public AuthResponse withoutRefreshToken() {
        return AuthResponse.builder()
                .userId(this.userId)
                .username(this.username)
                .displayName(this.displayName)
                .role(this.role)
                .token(this.token)
                .expiresIn(this.expiresIn)
                .build();
    }
}
