package com.example.chatbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatbot.entity.ChatRecord;
import org.apache.ibatis.annotations.*;
import java.util.List;

/**
 * 聊天记录Mapper接口
 */
@Mapper
public interface ChatRecordMapper extends BaseMapper<ChatRecord> {

} 