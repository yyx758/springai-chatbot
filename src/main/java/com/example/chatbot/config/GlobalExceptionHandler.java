package com.example.chatbot.config;

import com.example.chatbot.dto.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ChatResponse> handleAll(Exception e) {
        log.error("捕获到全局异常: ", e); // 这里依然会打印详细日志，满足你的需求
        return ResponseEntity.ok(ChatResponse.builder()
                .success(false)
                .error("系统故障: " + e.getMessage())
                .build());
    }
}