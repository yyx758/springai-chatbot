package com.example.chatbot.agent.review;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewApplyPatchResult {
    private boolean success;
    private Long actionId;
    private String status;
    private String actionType;
    private String message;
    private LocalDateTime expireTime;
}
