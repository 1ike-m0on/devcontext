package com.devcontext.adapters.persistence;

import com.devcontext.domain.context.ContextDocument;
import com.devcontext.ports.context.ContextDocumentRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcContextDocumentRepository implements ContextDocumentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ContextDocument> rowMapper = (rs, rowNum) -> new ContextDocument(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("doc_type"),
            rs.getString("file_path"),
            rs.getInt("generated") == 1,
            rs.getString("status"),
            rs.getString("source_commit"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcContextDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ContextDocument upsert(ContextDocument document) {
        Optional<ContextDocument> existing = findByProjectIdAndPath(document.projectId(), document.filePath());
        if (existing.isPresent()) {
            ContextDocument current = existing.get();
            jdbcTemplate.update("""
                    UPDATE context_document
                    SET doc_type = ?, generated = ?, status = ?, source_commit = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    document.type(),
                    document.generated() ? 1 : 0,
                    document.status(),
                    document.sourceCommit(),
                    document.updatedAt().toString(),
                    current.id());
            return new ContextDocument(
                    current.id(),
                    document.projectId(),
                    document.type(),
                    document.filePath(),
                    document.generated(),
                    document.status(),
                    document.sourceCommit(),
                    current.createdAt(),
                    document.updatedAt()
            );
        }
        return insert(document);
    }

    @Override
    public List<ContextDocument> findByProjectId(Long projectId) {
        return jdbcTemplate.query(
                "SELECT * FROM context_document WHERE project_id = ? ORDER BY file_path ASC",
                rowMapper,
                projectId
        );
    }

    @Override
    public Optional<ContextDocument> findByProjectIdAndPath(Long projectId, String filePath) {
        List<ContextDocument> documents = jdbcTemplate.query(
                "SELECT * FROM context_document WHERE project_id = ? AND file_path = ?",
                rowMapper,
                projectId,
                filePath
        );
        return documents.stream().findFirst();
    }

    private ContextDocument insert(ContextDocument document) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO context_document (
                        project_id, doc_type, file_path, generated, status,
                        source_commit, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, document.projectId());
            statement.setString(2, document.type());
            statement.setString(3, document.filePath());
            statement.setInt(4, document.generated() ? 1 : 0);
            statement.setString(5, document.status());
            statement.setString(6, document.sourceCommit());
            statement.setString(7, document.createdAt().toString());
            statement.setString(8, document.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new ContextDocument(
                key == null ? null : key.longValue(),
                document.projectId(),
                document.type(),
                document.filePath(),
                document.generated(),
                document.status(),
                document.sourceCommit(),
                document.createdAt(),
                document.updatedAt()
        );
    }
}
