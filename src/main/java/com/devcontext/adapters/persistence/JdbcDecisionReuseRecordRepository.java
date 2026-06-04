package com.devcontext.adapters.persistence;

import com.devcontext.domain.decision.DecisionReuseRecord;
import com.devcontext.ports.decision.DecisionReuseRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class JdbcDecisionReuseRecordRepository implements DecisionReuseRecordRepository {

    private static final TypeReference<List<Long>> LONG_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<DecisionReuseRecord> rowMapper = (rs, rowNum) -> new DecisionReuseRecord(
            rs.getLong("id"),
            nullableLong(rs.getObject("run_id"), rs.getLong("run_id")),
            rs.getString("query"),
            nullableLong(rs.getObject("project_id"), rs.getLong("project_id")),
            readJson(rs.getString("matched_decision_ids_json")),
            rs.getString("advice"),
            rs.getString("status"),
            nullableBoolean(rs.getObject("accepted"), rs.getInt("accepted")),
            rs.getString("user_feedback"),
            Instant.parse(rs.getString("created_at"))
    );

    public JdbcDecisionReuseRecordRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public DecisionReuseRecord save(DecisionReuseRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO decision_reuse_record (
                        run_id, query, project_id, matched_decision_ids_json, advice, status, accepted, user_feedback, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            setNullableLong(statement, 1, record.runId());
            statement.setString(2, record.query());
            setNullableLong(statement, 3, record.projectId());
            statement.setString(4, writeJson(record.matchedDecisionIds()));
            statement.setString(5, record.advice());
            statement.setString(6, record.status());
            setNullableBoolean(statement, 7, record.accepted());
            statement.setString(8, record.userFeedback());
            statement.setString(9, record.createdAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new DecisionReuseRecord(
                key == null ? null : key.longValue(),
                record.runId(),
                record.query(),
                record.projectId(),
                record.matchedDecisionIds(),
                record.advice(),
                record.status(),
                record.accepted(),
                record.userFeedback(),
                record.createdAt()
        );
    }

    @Override
    public Optional<DecisionReuseRecord> findById(Long recordId) {
        List<DecisionReuseRecord> records = jdbcTemplate.query("SELECT * FROM decision_reuse_record WHERE id = ?", rowMapper, recordId);
        return records.stream().findFirst();
    }

    @Override
    public DecisionReuseRecord updateFeedback(Long recordId, String status, Boolean accepted, String userFeedback) {
        jdbcTemplate.update("""
                UPDATE decision_reuse_record
                SET status = ?, accepted = ?, user_feedback = ?
                WHERE id = ?
                """, status, accepted == null ? null : (accepted ? 1 : 0), userFeedback, recordId);
        return findById(recordId)
                .orElseThrow(() -> new IllegalStateException("Decision reuse record not found after feedback update"));
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private void setNullableBoolean(PreparedStatement statement, int index, Boolean value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value ? 1 : 0);
        }
    }

    private Long nullableLong(Object rawValue, long longValue) {
        return rawValue == null ? null : longValue;
    }

    private Boolean nullableBoolean(Object rawValue, int intValue) {
        return rawValue == null ? null : intValue == 1;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize decision reuse JSON field", e);
        }
    }

    private List<Long> readJson(String json) {
        try {
            return objectMapper.readValue(json, LONG_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse decision reuse JSON field", e);
        }
    }
}
