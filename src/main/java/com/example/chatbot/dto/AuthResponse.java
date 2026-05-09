package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthResponse {

    private Long userId;
    private String username;
    private String displayName;
    private String token;
}
