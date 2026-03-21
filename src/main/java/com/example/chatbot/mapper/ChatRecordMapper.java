package com.example.chatbot.mapper;

import com.example.chatbot.entity.ChatRecord;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 聊天记录Mapper接口
 * 
 * @author yyvb
 */
@Mapper
public interface ChatRecordMapper {
    
    /**
     * 插入聊天记录
     */
    @Insert("INSERT INTO chat_record (user_message, bot_response, created_time, session_id) " +
            "VALUES (#{userMessage}, #{botResponse}, #{createdTime}, #{sessionId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatRecord chatRecord);
    
    /**
     * 根据ID查询聊天记录
     */
    @Select("SELECT * FROM chat_record WHERE id = #{id}")
    ChatRecord selectById(Long id);
    
    /**
     * 根据会话ID查询聊天记录
     */
    @Select("SELECT * FROM chat_record WHERE session_id = #{sessionId} ORDER BY created_time ASC")
    List<ChatRecord> selectBySessionId(String sessionId);
    
    /**
     * 查询所有聊天记录
     */
    @Select("SELECT * FROM chat_record ORDER BY created_time DESC")
    List<ChatRecord> selectAll();
    
    /**
     * 分页查询聊天记录
     */
    @Select("SELECT * FROM chat_record ORDER BY created_time DESC LIMIT #{limit} OFFSET #{offset}")
    List<ChatRecord> selectByPage(@Param("offset") int offset, @Param("limit") int limit);
    
    /**
     * 统计聊天记录总数
     */
    @Select("SELECT COUNT(*) FROM chat_record")
    long count();
    
    /**
     * 根据会话ID统计聊天记录数
     */
    @Select("SELECT COUNT(*) FROM chat_record WHERE session_id = #{sessionId}")
    long countBySessionId(String sessionId);
} 