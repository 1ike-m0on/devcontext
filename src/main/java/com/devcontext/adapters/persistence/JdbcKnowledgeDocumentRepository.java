package com.devcontext.adapters.persistence;

import com.devcontext.domain.knowledge.KnowledgeDocument;
import com.devcontext.ports.knowledge.KnowledgeDocumentRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<KnowledgeDocument> rowMapper = (rs, rowNum) -> new KnowledgeDocument(
            rs.getLong("id"),
            rs.getLong("source_id"),
            rs.getString("file_path"),
            rs.getString("title"),
            rs.getString("content_hash"),
            rs.getString("status"),
            rs.getString("indexed_at") == null ? null : Instant.parse(rs.getString("indexed_at")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_document (
                        source_id, file_path, title, content_hash, status, indexed_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, document.sourceId());
            statement.setString(2, document.filePath());
            statement.setString(3, document.title());
            statement.setString(4, document.contentHash());
            statement.setString(5, document.status());
            statement.setString(6, document.indexedAt() == null ? null : document.indexedAt().toString());
            statement.setString(7, document.createdAt().toString());
            statement.setString(8, document.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new KnowledgeDocument(
                key == null ? null : key.longValue(),
                document.sourceId(),
                document.filePath(),
                document.title(),
                document.contentHash(),
                document.status(),
                document.indexedAt(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    @Override
    public List<KnowledgeDocument> findBySourceId(Long sourceId) {
        return jdbcTemplate.query("SELECT * FROM knowledge_document WHERE source_id = ? ORDER BY file_path", rowMapper, sourceId);
    }

    @Override
    public void deleteBySourceId(Long sourceId) {
        jdbcTemplate.update("DELETE FROM knowledge_document WHERE source_id = ?", sourceId);
    }
}
