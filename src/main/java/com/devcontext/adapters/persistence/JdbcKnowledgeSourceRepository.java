package com.devcontext.adapters.persistence;

import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.ports.knowledge.KnowledgeSourceRepository;
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
public class JdbcKnowledgeSourceRepository implements KnowledgeSourceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<KnowledgeSource> rowMapper = (rs, rowNum) -> new KnowledgeSource(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("root_path"),
            rs.getString("source_type"),
            rs.getString("status"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcKnowledgeSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeSource save(KnowledgeSource source) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO knowledge_source (name, root_path, source_type, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, source.name());
            statement.setString(2, source.rootPath());
            statement.setString(3, source.sourceType());
            statement.setString(4, source.status());
            statement.setString(5, source.createdAt().toString());
            statement.setString(6, source.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new KnowledgeSource(
                key == null ? null : key.longValue(),
                source.name(),
                source.rootPath(),
                source.sourceType(),
                source.status(),
                source.createdAt(),
                source.updatedAt()
        );
    }

    @Override
    public KnowledgeSource update(KnowledgeSource source) {
        jdbcTemplate.update("""
                UPDATE knowledge_source
                SET name = ?, root_path = ?, source_type = ?, status = ?, updated_at = ?
                WHERE id = ?
                """, source.name(), source.rootPath(), source.sourceType(), source.status(), source.updatedAt().toString(), source.id());
        return findById(source.id()).orElseThrow();
    }

    @Override
    public Optional<KnowledgeSource> findById(Long sourceId) {
        List<KnowledgeSource> sources = jdbcTemplate.query("SELECT * FROM knowledge_source WHERE id = ?", rowMapper, sourceId);
        return sources.stream().findFirst();
    }

    @Override
    public List<KnowledgeSource> findAll() {
        return jdbcTemplate.query("SELECT * FROM knowledge_source ORDER BY id DESC", rowMapper);
    }
}
