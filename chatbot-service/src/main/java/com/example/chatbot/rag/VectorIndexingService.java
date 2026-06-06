package com.example.chatbot.rag;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.chatbot.entity.KnowledgeDocument;
import com.example.chatbot.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorIndexingService {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final DocumentChunker documentChunker;
    private final EmbeddingClient embeddingClient;
    private final PgVectorClient pgVectorClient;
    private final RagProperties ragProperties;

    public boolean isEnabled() {
        return pgVectorClient.isEnabled();
    }

    public void indexDocument(Long userId, Long documentId) {
        if (!isEnabled()) {
            markIndexStatus(documentId, "VECTOR_DISABLED", null);
            return;
        }
        KnowledgeDocument document = knowledgeDocumentMapper.selectById(documentId);
        if (document == null || !userId.equals(document.getUserId())) {
            return;
        }
        if (Boolean.FALSE.equals(document.getEnabled())) {
            deleteDocument(userId, documentId);
            markIndexStatus(documentId, "INDEX_SKIPPED", null);
            return;
        }

        markIndexStatus(documentId, "INDEXING", null);
        try {
            List<DocumentChunk> chunks = documentChunker.chunk(document.getContent());
            List<List<Double>> embeddings = new ArrayList<>(chunks.size());
            for (DocumentChunk chunk : chunks) {
                List<Double> embedding = embeddingClient.embed(chunk.content());
                validateDimensions(embedding);
                embeddings.add(embedding);
            }
            pgVectorClient.indexDocument(document, chunks, embeddings);
            markIndexed(documentId);
        } catch (Exception e) {
            log.warn("Vector indexing failed, documentId={}, error={}", documentId, e.getMessage());
            markIndexStatus(documentId, "INDEX_FAILED", e.getMessage());
        }
    }

    public void deleteDocument(Long userId, Long documentId) {
        if (!isEnabled()) {
            return;
        }
        try {
            pgVectorClient.deleteDocument(userId, documentId);
        } catch (Exception e) {
            log.warn("Vector delete failed, documentId={}, error={}", documentId, e.getMessage());
        }
    }

    private void validateDimensions(List<Double> embedding) {
        int expected = ragProperties.getVector().getDimensions();
        if (embedding.size() != expected) {
            throw new IllegalStateException("embedding dimensions mismatch, expected "
                    + expected + " but got " + embedding.size());
        }
    }

    private void markIndexed(Long documentId) {
        knowledgeDocumentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getIndexStatus, "INDEXED")
                .set(KnowledgeDocument::getIndexError, null)
                .set(KnowledgeDocument::getIndexedTime, LocalDateTime.now()));
    }

    private void markIndexStatus(Long documentId, String status, String error) {
        knowledgeDocumentMapper.update(null, new LambdaUpdateWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getIndexStatus, status)
                .set(KnowledgeDocument::getIndexError, error));
    }
}
