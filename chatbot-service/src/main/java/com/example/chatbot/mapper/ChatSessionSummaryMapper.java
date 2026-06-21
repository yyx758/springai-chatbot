package com.example.chatbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatbot.entity.ChatSessionSummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatSessionSummaryMapper extends BaseMapper<ChatSessionSummary> {

    @Select("SELECT id, session_id, user_id, summary, last_summarized_record_id, created_time, updated_time "
            + "FROM chat_session_summary WHERE session_id = #{sessionId} LIMIT 1")
    ChatSessionSummary selectBySessionId(@Param("sessionId") String sessionId);

    @Insert("INSERT INTO chat_session_summary (session_id, user_id, summary, last_summarized_record_id) "
            + "VALUES (#{sessionId}, #{userId}, #{summary}, #{lastSummarizedRecordId}) "
            + "ON DUPLICATE KEY UPDATE summary = VALUES(summary), "
            + "last_summarized_record_id = VALUES(last_summarized_record_id), "
            + "user_id = VALUES(user_id), updated_time = CURRENT_TIMESTAMP")
    int upsertSummary(ChatSessionSummary summary);

    @Delete("DELETE FROM chat_session_summary WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
