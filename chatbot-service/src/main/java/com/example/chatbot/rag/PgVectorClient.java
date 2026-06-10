package com.example.chatbot.rag;

import com.example.chatbot.dto.RagReference;
import com.example.chatbot.entity.KnowledgeDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PgVectorClient {

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initializeSchemaIfNeeded() {
        if (!isEnabled() || !ragProperties.getVector().isInitializeSchema()) {
            return;
        }
        try (Connection connection = newConnection();
             Statement statement = connection.createStatement()) {
            String table = tableName();
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS %s (
                        id TEXT PRIMARY KEY,
                        document_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        chunk_index INT NOT NULL,
                        content TEXT NOT NULL,
                        title TEXT NOT NULL,
                        metadata JSONB NOT NULL,
                        embedding VECTOR(%d) NOT NULL,
                        created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """.formatted(table, ragProperties.getVector().getDimensions()));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_%s_user_doc ON %s (user_id, document_id)"
                    .formatted(table, table));
            statement.execute("CREATE INDEX IF NOT EXISTS idx_%s_embedding ON %s USING hnsw (embedding vector_cosine_ops)"
                    .formatted(table, table));
            log.info("PGVector schema initialized, table={}", table);
        } catch (Exception e) {
            log.warn("PGVector schema initialization skipped/failed: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        RagProperties.Vector vector = ragProperties.getVector();
        return vector.isEnabled()
                && vector.getJdbcUrl() != null && !vector.getJdbcUrl().isBlank()
                && ragProperties.getEmbedding().getBaseUrl() != null && !ragProperties.getEmbedding().getBaseUrl().isBlank();
    }

    public void indexDocument(KnowledgeDocument document, List<DocumentChunk> chunks, List<List<Double>> embeddings) {
        if (!isEnabled() || chunks.isEmpty()) {
            return;
        }
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks and embeddings size mismatch");
        }
        deleteDocument(document.getUserId(), document.getId());

        String sql = """
                INSERT INTO %s (id, document_id, user_id, chunk_index, content, title, metadata, embedding)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::vector)
                ON CONFLICT (id) DO UPDATE SET
                    content = EXCLUDED.content,
                    title = EXCLUDED.title,
                    metadata = EXCLUDED.metadata,
                    embedding = EXCLUDED.embedding
                """.formatted(tableName());

        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                ps.setString(1, vectorId(document.getId(), chunk.index()));
                ps.setLong(2, document.getId());
                ps.setLong(3, document.getUserId());
                ps.setInt(4, chunk.index());
                ps.setString(5, chunk.content());
                ps.setString(6, document.getTitle());
                ps.setString(7, metadataJson(document, chunk));
                ps.setString(8, vectorLiteral(embeddings.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            throw new IllegalStateException("PGVector index failed: " + e.getMessage(), e);
        }
    }

    public void deleteDocument(Long userId, Long documentId) {
        if (!isEnabled()) {
            return;
        }
        String sql = "DELETE FROM %s WHERE user_id = ? AND document_id = ?".formatted(tableName());
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, userId);
            ps.setLong(2, documentId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("PGVector delete failed: " + e.getMessage(), e);
        }
    }

    public List<RagReference> search(Long userId, String queryVector, int topK, double threshold) {
        if (!isEnabled()) {
            return List.of();
        }

        // 先查无阈值过滤的原始分数，用于调试
        String debugSql = """
                SELECT document_id, title, round((1 - (embedding <=> ?::vector))::numeric, 4) AS score
                FROM %s WHERE user_id = ?
                ORDER BY embedding <=> ?::vector LIMIT 5
                """.formatted(tableName());
        try (Connection conn = newConnection();
             PreparedStatement dps = conn.prepareStatement(debugSql)) {
            dps.setString(1, queryVector);
            dps.setLong(2, userId);
            dps.setString(3, queryVector);
            try (ResultSet drs = dps.executeQuery()) {
                while (drs.next()) {
                    log.info("[VectorRAG-Debug] docId={}, title='{}', rawScore={}",
                            drs.getLong("document_id"),
                            drs.getString("title"),
                            drs.getDouble("score"));
                }
            }
        } catch (Exception e) {
            log.warn("[VectorRAG-Debug] query failed: {}", e.getMessage());
        }

        String sql = """
                SELECT id, document_id, title, content, 1 - (embedding <=> ?::vector) AS score
                FROM %s
                WHERE user_id = ?
                  AND (1 - (embedding <=> ?::vector)) >= ?
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(tableName());
        try (Connection connection = newConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, queryVector);
            ps.setLong(2, userId);
            ps.setString(3, queryVector);
            ps.setDouble(4, threshold);
            ps.setString(5, queryVector);
            ps.setInt(6, topK);
            try (ResultSet rs = ps.executeQuery()) {
                List<RagReference> results = new ArrayList<>();
                while (rs.next()) {
                    double score = rs.getDouble("score");
                    results.add(RagReference.builder()
                            .chunkId(rs.getString("id"))
                            .documentId(rs.getLong("document_id"))
                            .title(rs.getString("title"))
                            .snippet(rs.getString("content"))
                            .score(score)
                            .build());
                }
                return results;
            }
        } catch (Exception e) {
            throw new IllegalStateException("PGVector search failed: " + e.getMessage(), e);
        }
    }

    public String vectorLiteral(List<Double> vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector.get(i));
        }
        builder.append(']');
        return builder.toString();
    }

    private Connection newConnection() throws Exception {
        RagProperties.Vector vector = ragProperties.getVector();
        return DriverManager.getConnection(vector.getJdbcUrl(), vector.getUsername(), vector.getPassword());
    }

    private String tableName() {
        String table = ragProperties.getVector().getTableName();
        if (table == null || !table.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("invalid PGVector table name");
        }
        return table;
    }

    private String vectorId(Long documentId, int chunkIndex) {
        return documentId + "_" + chunkIndex;
    }

    private String metadataJson(KnowledgeDocument document, DocumentChunk chunk) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "userId", document.getUserId(),
                "documentId", document.getId(),
                "title", document.getTitle(),
                "fileKey", document.getFileKey() == null ? "" : document.getFileKey(),
                "tags", document.getTags() == null ? "" : document.getTags(),
                "enabled", document.getEnabled() == null || document.getEnabled(),
                "chunkIndex", chunk.index()
        ));
    }
}
