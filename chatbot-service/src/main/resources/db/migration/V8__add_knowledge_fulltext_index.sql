ALTER TABLE knowledge_document
    ADD FULLTEXT INDEX ft_knowledge_title_content_tags (title, content, tags);
