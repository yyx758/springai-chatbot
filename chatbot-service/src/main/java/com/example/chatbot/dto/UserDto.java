package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserDto {
    private Long id;
    private String username;
    private String email;
    private String displayName;
    private String role;
    private Boolean enabled;
    private LocalDateTime createdTime;
}
