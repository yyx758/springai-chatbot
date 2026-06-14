package com.example.chatbot.service;

import com.example.chatbot.dto.RagReference;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record ConversationContext(List<Message> messages, List<RagReference> references) {
}
