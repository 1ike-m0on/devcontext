package com.devcontext.adapters.persistence;

import com.devcontext.domain.knowledge.KnowledgeChunk;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeDocument;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<KnowledgeChunkView> viewRowMapper = (rs, rowNum) -> new KnowledgeChunkView(
            new KnowledgeChunk(
                    rs.getLong("chunk_id"),
                    rs.getLong("chunk_source_id"),
                    rs.getLong("document_id"),
                    rs.getInt("chunk_index"),
                    rs.getString("heading_path"),
                    rs.getString("content"),
                    rs.getString("chunk_content_hash"),
                    rs.getInt("token_estimate"),
                    rs.getString("vector_id"),
                    Instant.parse(rs.getString("chunk_created_at"))
            ),
            new KnowledgeDocument(
                    rs.getLong("document_id"),
                    rs.getLong("document_source_id"),
                    rs.getString("file_path"),
                    rs.getString("title"),
                    rs.getString("document_content_hash"),
                    rs.getString("document_status"),
                    rs.getString("indexed_at") == null ? null : Instant.parse(rs.getString("indexed_at")),
                    Instant.parse(rs.getString("document_created_at")),
                    Instant.parse(rs.getString("document_updated_at"))
            ),
            new KnowledgeSource(
                    rs.getLong("source_id"),
                    rs.getString("source_name"),
                    rs.getString("root_path"),
                    rs.getString("source_type"),
                    rs.getString("source_status"),
                    Instant.parse(rs.getString("source_created_at")),
                    Instant.parse(rs.getString("source_updated_at"))
            )
    );

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeChunk save(KnowledgeChunk chunk) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_chunk (
                        source_id, document_id, chunk_index, heading_path, content,
                        content_hash, token_estimate, vector_id, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, chunk.sourceId());
            statement.setLong(2, chunk.documentId());
            statement.setInt(3, chunk.chunkIndex());
            statement.setString(4, chunk.headingPath());
            statement.setString(5, chunk.content());
            statement.setString(6, chunk.contentHash());
            statement.setInt(7, chunk.tokenEstimate());
            statement.setString(8, chunk.vectorId());
            statement.setString(9, chunk.createdAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new KnowledgeChunk(
                key == null ? null : key.longValue(),
                chunk.sourceId(),
                chunk.documentId(),
                chunk.chunkIndex(),
                chunk.headingPath(),
                chunk.content(),
                chunk.contentHash(),
                chunk.tokenEstimate(),
                chunk.vectorId(),
                chunk.createdAt()
        );
    }

    @Override
    public List<KnowledgeChunkView> findAllViews() {
        return jdbcTemplate.query(baseViewSql() + " ORDER BY c.id DESC", viewRowMapper);
    }

    @Override
    public List<KnowledgeChunkView> findViewsBySourceId(Long sourceId) {
        return jdbcTemplate.query(baseViewSql() + " WHERE c.source_id = ? ORDER BY c.id DESC", viewRowMapper, sourceId);
    }

    @Override
    public Optional<KnowledgeChunkView> findViewByChunkId(Long chunkId) {
        List<KnowledgeChunkView> views = jdbcTemplate.query(baseViewSql() + " WHERE c.id = ?", viewRowMapper, chunkId);
        return views.stream().findFirst();
    }

    @Override
    public Map<Long, KnowledgeChunkView> findViewsByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", chunkIds.stream().map(id -> "?").toList());
        List<KnowledgeChunkView> views = jdbcTemplate.query(
                baseViewSql() + " WHERE c.id IN (" + placeholders + ")",
                viewRowMapper,
                chunkIds.toArray()
        );
        Map<Long, KnowledgeChunkView> byId = new LinkedHashMap<>();
        for (KnowledgeChunkView view : views) {
            byId.put(view.chunk().id(), view);
        }
        return byId;
    }

    @Override
    public Map<String, KnowledgeChunkView> findViewsByVectorIds(Collection<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", vectorIds.stream().map(id -> "?").toList());
        List<KnowledgeChunkView> views = jdbcTemplate.query(
                baseViewSql() + " WHERE c.vector_id IN (" + placeholders + ")",
                viewRowMapper,
                vectorIds.toArray()
        );
        Map<String, KnowledgeChunkView> byId = new LinkedHashMap<>();
        for (KnowledgeChunkView view : views) {
            byId.put(view.chunk().vectorId(), view);
        }
        return byId;
    }

    @Override
    public void deleteBySourceId(Long sourceId) {
        jdbcTemplate.update("DELETE FROM knowledge_chunk WHERE source_id = ?", sourceId);
    }

    private String baseViewSql() {
        return """
                SELECT
                    c.id AS chunk_id,
                    c.source_id AS chunk_source_id,
                    c.document_id AS document_id,
                    c.chunk_index AS chunk_index,
                    c.heading_path AS heading_path,
                    c.content AS content,
                    c.content_hash AS chunk_content_hash,
                    c.token_estimate AS token_estimate,
                    c.vector_id AS vector_id,
                    c.created_at AS chunk_created_at,
                    d.source_id AS document_source_id,
                    d.file_path AS file_path,
                    d.title AS title,
                    d.content_hash AS document_content_hash,
                    d.status AS document_status,
                    d.indexed_at AS indexed_at,
                    d.created_at AS document_created_at,
                    d.updated_at AS document_updated_at,
                    s.id AS source_id,
                    s.name AS source_name,
                    s.root_path AS root_path,
                    s.source_type AS source_type,
                    s.status AS source_status,
                    s.created_at AS source_created_at,
                    s.updated_at AS source_updated_at
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                JOIN knowledge_source s ON s.id = c.source_id
                """;
    }
}
