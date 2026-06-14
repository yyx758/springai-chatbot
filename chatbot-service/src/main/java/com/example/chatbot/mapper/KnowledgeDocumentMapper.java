package com.example.chatbot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.chatbot.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    @Select("""
            SELECT *
            FROM knowledge_document
            WHERE user_id = #{userId}
              AND enabled = 1
              AND MATCH(title, content, tags) AGAINST (#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY updated_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<KnowledgeDocument> searchFulltextCandidates(@Param("userId") Long userId,
                                                     @Param("query") String query,
                                                     @Param("limit") int limit);
}
