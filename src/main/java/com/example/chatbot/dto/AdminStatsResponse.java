package com.example.chatbot.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminStatsResponse {
    private long totalUsers;
    private long totalChats;
    private long totalKnowledgeDocs;
}
