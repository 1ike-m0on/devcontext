package com.devcontext.adapters.persistence;

import com.devcontext.domain.knowledge.RetrievalRecord;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRetrievalRecordRepository implements RetrievalRecordRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RetrievalRecord> rowMapper = (rs, rowNum) -> new RetrievalRecord(
            rs.getLong("id"),
            rs.getObject("run_id") == null ? null : rs.getLong("run_id"),
            rs.getString("query"),
            rs.getString("rewritten_query"),
            rs.getInt("top_k"),
            rs.getString("result_json"),
            Instant.parse(rs.getString("created_at"))
    );

    public JdbcRetrievalRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RetrievalRecord save(RetrievalRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO retrieval_record (run_id, query, rewritten_query, top_k, result_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            if (record.runId() == null) {
                statement.setNull(1, Types.BIGINT);
            } else {
                statement.setLong(1, record.runId());
            }
            statement.setString(2, record.query());
            statement.setString(3, record.rewrittenQuery());
            statement.setInt(4, record.topK());
            statement.setString(5, record.resultJson());
            statement.setString(6, record.createdAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new RetrievalRecord(
                key == null ? null : key.longValue(),
                record.runId(),
                record.query(),
                record.rewrittenQuery(),
                record.topK(),
                record.resultJson(),
                record.createdAt()
        );
    }

    @Override
    public Optional<RetrievalRecord> findById(Long recordId) {
        List<RetrievalRecord> records = jdbcTemplate.query("SELECT * FROM retrieval_record WHERE id = ?", rowMapper, recordId);
        return records.stream().findFirst();
    }

    @Override
    public List<RetrievalRecord> findByRunId(Long runId) {
        return jdbcTemplate.query("SELECT * FROM retrieval_record WHERE run_id = ? ORDER BY id", rowMapper, runId);
    }
}
