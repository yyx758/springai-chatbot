package com.example.chatbot.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.example.chatbot.dto.AdminStatsResponse;
import com.example.chatbot.dto.UpdateEnabledRequest;
import com.example.chatbot.dto.UpdateRoleRequest;
import com.example.chatbot.dto.UserDto;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.security.AuthInterceptor;
import com.example.chatbot.security.RequireRole;
import com.example.chatbot.service.AdminService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@RequireRole("ADMIN")
public class AdminController {

    private final AdminService adminService;

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsResponse> getStats() {
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/users")
    public ResponseEntity<IPage<UserDto>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminService.listUsers(page, size));
    }

    @PutMapping("/users/{userId}/role")
    public ResponseEntity<UserDto> updateRole(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(adminService.updateRole(userId, request.getRole()));
    }

    @PutMapping("/users/{userId}/enabled")
    public ResponseEntity<UserDto> updateEnabled(
            @PathVariable Long userId,
            @Valid @RequestBody UpdateEnabledRequest request) {
        return ResponseEntity.ok(adminService.updateEnabled(userId, request.getEnabled()));
    }

    @DeleteMapping("/users/{userId}")
    public ResponseEntity<Map<String, Object>> deleteUser(
            @PathVariable Long userId,
            HttpServletRequest request) {
        Object adminId = request.getAttribute(AuthInterceptor.AUTH_USER_ID_ATTR);
        adminService.deleteUser(userId, Long.valueOf(String.valueOf(adminId)));
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/documents")
    public ResponseEntity<IPage<KnowledgeDocument>> listAllDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(adminService.listAllDocuments(page, size));
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteAnyDocument(
            @PathVariable Long documentId) {
        adminService.deleteAnyDocument(documentId);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
