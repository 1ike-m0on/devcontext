package com.devcontext.adapters.persistence;

import com.devcontext.domain.memory.Observation;
import com.devcontext.ports.memory.ObservationRepository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcObservationRepository implements ObservationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Observation> rowMapper = (rs, rowNum) -> new Observation(
            rs.getLong("id"),
            nullableLong(rs, "project_id"),
            rs.getString("source_type"),
            rs.getString("source_record_id"),
            rs.getString("source_key"),
            rs.getString("task_type"),
            rs.getString("lifecycle"),
            rs.getString("source_status"),
            rs.getString("title"),
            rs.getString("summary"),
            Instant.parse(rs.getString("occurred_at")),
            rs.getString("provider"),
            rs.getString("model_name"),
            rs.getString("error_type"),
            rs.getString("error_message_summary"),
            nullableLong(rs, "run_id"),
            nullableLong(rs, "event_id"),
            nullableLong(rs, "retrieval_id"),
            nullableLong(rs, "review_id"),
            nullableLong(rs, "issue_id"),
            nullableLong(rs, "decision_reuse_record_id"),
            rs.getString("report_run_id"),
            rs.getString("report_path"),
            rs.getString("relation_json"),
            rs.getString("metadata_json"),
            rs.getString("privacy_level"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Observation save(Observation observation) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO observation (
                        project_id, source_type, source_record_id, source_key, task_type, lifecycle,
                        source_status, title, summary, occurred_at, provider, model_name, error_type,
                        error_message_summary, run_id, event_id, retrieval_id, review_id, issue_id,
                        decision_reuse_record_id, report_run_id, report_path, relation_json, metadata_json,
                        privacy_level, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            bind(statement, observation);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return withId(observation, key == null ? null : key.longValue(), observation.createdAt());
    }

    @Override
    public Observation upsertBySourceKey(Observation observation) {
        Optional<Observation> existing = findBySourceKey(observation.sourceType(), observation.sourceKey());
        if (existing.isEmpty()) {
            return save(observation);
        }
        Observation updated = withId(observation, existing.get().id(), existing.get().createdAt());
        jdbcTemplate.update("""
                UPDATE observation
                SET project_id = ?, source_type = ?, source_record_id = ?, source_key = ?, task_type = ?,
                    lifecycle = ?, source_status = ?, title = ?, summary = ?, occurred_at = ?, provider = ?,
                    model_name = ?, error_type = ?, error_message_summary = ?, run_id = ?, event_id = ?,
                    retrieval_id = ?, review_id = ?, issue_id = ?, decision_reuse_record_id = ?,
                    report_run_id = ?, report_path = ?, relation_json = ?, metadata_json = ?,
                    privacy_level = ?, created_at = ?, updated_at = ?
                WHERE id = ?
                """,
                updated.projectId(),
                updated.sourceType(),
                updated.sourceRecordId(),
                updated.sourceKey(),
                updated.taskType(),
                updated.lifecycle(),
                updated.sourceStatus(),
                updated.title(),
                updated.summary(),
                updated.occurredAt().toString(),
                updated.provider(),
                updated.modelName(),
                updated.errorType(),
                updated.errorMessageSummary(),
                updated.runId(),
                updated.eventId(),
                updated.retrievalId(),
                updated.reviewId(),
                updated.issueId(),
                updated.decisionReuseRecordId(),
                updated.reportRunId(),
                updated.reportPath(),
                updated.relationJson(),
                updated.metadataJson(),
                updated.privacyLevel(),
                updated.createdAt().toString(),
                updated.updatedAt().toString(),
                updated.id());
        return updated;
    }

    @Override
    public Optional<Observation> findById(Long id) {
        List<Observation> observations = jdbcTemplate.query("SELECT * FROM observation WHERE id = ?", rowMapper, id);
        return observations.stream().findFirst();
    }

    @Override
    public List<Observation> findRecent(Long projectId, String taskType, String lifecycle, int limit) {
        return findRecent(projectId, taskType, lifecycle, null, null, null, limit);
    }

    @Override
    public List<Observation> findRecent(Long projectId, String taskType, String lifecycle, Long runId, Long reviewId, Long retrievalId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM observation WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        appendFilter(sql, params, "project_id", projectId);
        appendFilter(sql, params, "task_type", blankToNull(taskType));
        appendFilter(sql, params, "lifecycle", blankToNull(lifecycle));
        appendFilter(sql, params, "run_id", runId);
        appendFilter(sql, params, "review_id", reviewId);
        appendFilter(sql, params, "retrieval_id", retrievalId);
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        params.add(safeLimit(limit));
        return jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());
    }

    @Override
    public List<Observation> findByRunId(Long runId) {
        return findRecent(null, null, null, runId, null, null, 100);
    }

    @Override
    public List<Observation> findByReviewId(Long reviewId) {
        return findRecent(null, null, null, null, reviewId, null, 100);
    }

    @Override
    public List<Observation> findByRetrievalId(Long retrievalId) {
        return findRecent(null, null, null, null, null, retrievalId, 100);
    }

    private Optional<Observation> findBySourceKey(String sourceType, String sourceKey) {
        List<Observation> observations = jdbcTemplate.query("""
                SELECT *
                FROM observation
                WHERE source_type = ? AND source_key = ?
                """, rowMapper, sourceType, sourceKey);
        return observations.stream().findFirst();
    }

    private void bind(PreparedStatement statement, Observation observation) throws java.sql.SQLException {
        statement.setObject(1, observation.projectId());
        statement.setString(2, observation.sourceType());
        statement.setString(3, observation.sourceRecordId());
        statement.setString(4, observation.sourceKey());
        statement.setString(5, observation.taskType());
        statement.setString(6, observation.lifecycle());
        statement.setString(7, observation.sourceStatus());
        statement.setString(8, observation.title());
        statement.setString(9, observation.summary());
        statement.setString(10, observation.occurredAt().toString());
        statement.setString(11, observation.provider());
        statement.setString(12, observation.modelName());
        statement.setString(13, observation.errorType());
        statement.setString(14, observation.errorMessageSummary());
        statement.setObject(15, observation.runId());
        statement.setObject(16, observation.eventId());
        statement.setObject(17, observation.retrievalId());
        statement.setObject(18, observation.reviewId());
        statement.setObject(19, observation.issueId());
        statement.setObject(20, observation.decisionReuseRecordId());
        statement.setString(21, observation.reportRunId());
        statement.setString(22, observation.reportPath());
        statement.setString(23, observation.relationJson());
        statement.setString(24, observation.metadataJson());
        statement.setString(25, observation.privacyLevel());
        statement.setString(26, observation.createdAt().toString());
        statement.setString(27, observation.updatedAt().toString());
    }

    private static void appendFilter(StringBuilder sql, List<Object> params, String column, Object value) {
        if (value == null) {
            return;
        }
        sql.append(" AND ").append(column).append(" = ?");
        params.add(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int safeLimit(int limit) {
        return Math.max(1, Math.min(limit, 100));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Observation withId(Observation observation, Long id, Instant createdAt) {
        return new Observation(
                id,
                observation.projectId(),
                observation.sourceType(),
                observation.sourceRecordId(),
                observation.sourceKey(),
                observation.taskType(),
                observation.lifecycle(),
                observation.sourceStatus(),
                observation.title(),
                observation.summary(),
                observation.occurredAt(),
                observation.provider(),
                observation.modelName(),
                observation.errorType(),
                observation.errorMessageSummary(),
                observation.runId(),
                observation.eventId(),
                observation.retrievalId(),
                observation.reviewId(),
                observation.issueId(),
                observation.decisionReuseRecordId(),
                observation.reportRunId(),
                observation.reportPath(),
                observation.relationJson(),
                observation.metadataJson(),
                observation.privacyLevel(),
                createdAt,
                observation.updatedAt()
        );
    }
}
