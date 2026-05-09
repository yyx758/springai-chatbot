package com.example.chatbot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.chatbot.dto.AdminStatsResponse;
import com.example.chatbot.dto.UserDto;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.entity.UserAccount;
import com.example.chatbot.mapper.ChatRecordMapper;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import com.example.chatbot.mapper.UserAccountMapper;
import com.example.chatbot.security.RefreshTokenStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserAccountMapper userAccountMapper;
    private final ChatRecordMapper chatRecordMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final RefreshTokenStore refreshTokenStore;

    public IPage<UserDto> listUsers(int page, int size) {
        Page<UserAccount> pageParam = new Page<>(page, size);
        IPage<UserAccount> result = userAccountMapper.selectPage(pageParam,
                new LambdaQueryWrapper<UserAccount>().orderByDesc(UserAccount::getCreatedTime));
        return result.convert(this::toDto);
    }

    public UserDto updateRole(Long userId, String role) {
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("角色值无效，只能是 USER 或 ADMIN");
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setRole(role);
        userAccountMapper.updateById(user);
        return toDto(user);
    }

    public UserDto updateEnabled(Long userId, Boolean enabled) {
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        user.setEnabled(enabled);
        userAccountMapper.updateById(user);
        return toDto(user);
    }

    public void deleteUser(Long userId, Long currentAdminId) {
        if (userId.equals(currentAdminId)) {
            throw new IllegalArgumentException("不能删除自己的账户");
        }
        UserAccount user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        refreshTokenStore.revokeAllForUser(userId);
        userAccountMapper.deleteById(userId);
    }

    public AdminStatsResponse getStats() {
        long totalUsers = userAccountMapper.selectCount(null);
        long totalChats = chatRecordMapper.selectCount(null);
        long totalKnowledgeDocs = knowledgeDocumentMapper.selectCount(null);
        return AdminStatsResponse.builder()
                .totalUsers(totalUsers)
                .totalChats(totalChats)
                .totalKnowledgeDocs(totalKnowledgeDocs)
                .build();
    }

    public IPage<KnowledgeDocument> listAllDocuments(int page, int size) {
        Page<KnowledgeDocument> pageParam = new Page<>(page, size);
        return knowledgeDocumentMapper.selectPage(pageParam,
                new LambdaQueryWrapper<KnowledgeDocument>().orderByDesc(KnowledgeDocument::getCreatedTime));
    }

    public void deleteAnyDocument(Long documentId) {
        KnowledgeDocument doc = knowledgeDocumentMapper.selectById(documentId);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在");
        }
        knowledgeDocumentMapper.deleteById(documentId);
    }

    private UserDto toDto(UserAccount user) {
        return UserDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .role(user.getRole())
                .enabled(user.getEnabled())
                .createdTime(user.getCreatedTime())
                .build();
    }
}
