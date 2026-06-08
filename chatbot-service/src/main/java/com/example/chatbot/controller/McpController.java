package com.example.chatbot.controller;

import com.example.chatbot.mcp.McpToolGateway;
import com.example.chatbot.mcp.McpToolInvocationRequest;
import com.example.chatbot.mcp.McpToolSpec;
import com.example.chatbot.security.AuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class McpController {

    private final McpToolGateway mcpToolGateway;

    @GetMapping("/tools")
    public Map<String, Object> listTools() {
        List<McpToolSpec> tools = mcpToolGateway.listTools();
        return Map.of("success", true, "tools", tools);
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestBody McpToolInvocationRequest request, HttpServletRequest httpRequest) {
        return mcpToolGateway.invoke(resolveUserId(httpRequest), request);
    }

    private Long resolveUserId(HttpServletRequest request) {
        Object userId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        if (userId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return Long.valueOf(String.valueOf(userId));
    }
}
