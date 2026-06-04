package com.devcontext.adapters.persistence;

import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.run.AgentRunRepository;
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
public class JdbcAgentRunRepository implements AgentRunRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AgentRun> rowMapper = (rs, rowNum) -> new AgentRun(
            rs.getLong("id"),
            nullableLong(rs, "project_id"),
            rs.getString("run_type"),
            rs.getString("status"),
            rs.getString("model_name"),
            rs.getString("prompt_version"),
            nullableInt(rs, "input_token_estimate"),
            nullableInt(rs, "output_token_estimate"),
            nullableLong(rs, "duration_ms"),
            rs.getString("error_message"),
            Instant.parse(rs.getString("created_at")),
            parseNullableInstant(rs.getString("finished_at"))
    );

    public JdbcAgentRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentRun save(AgentRun run) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_run (
                        project_id, run_type, status, model_name, prompt_version,
                        input_token_estimate, output_token_estimate, duration_ms,
                        error_message, created_at, finished_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setObject(1, run.projectId());
            statement.setString(2, run.runType());
            statement.setString(3, run.status());
            statement.setString(4, run.modelName());
            statement.setString(5, run.promptVersion());
            statement.setObject(6, run.inputTokenEstimate());
            statement.setObject(7, run.outputTokenEstimate());
            statement.setObject(8, run.durationMs());
            statement.setString(9, run.errorMessage());
            statement.setString(10, run.createdAt().toString());
            statement.setString(11, run.finishedAt() == null ? null : run.finishedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new AgentRun(
                key == null ? null : key.longValue(),
                run.projectId(),
                run.runType(),
                run.status(),
                run.modelName(),
                run.promptVersion(),
                run.inputTokenEstimate(),
                run.outputTokenEstimate(),
                run.durationMs(),
                run.errorMessage(),
                run.createdAt(),
                run.finishedAt()
        );
    }

    @Override
    public AgentRun update(AgentRun run) {
        jdbcTemplate.update("""
                UPDATE agent_run
                SET status = ?, input_token_estimate = ?, output_token_estimate = ?, duration_ms = ?,
                    error_message = ?, finished_at = ?
                WHERE id = ?
                """,
                run.status(),
                run.inputTokenEstimate(),
                run.outputTokenEstimate(),
                run.durationMs(),
                run.errorMessage(),
                run.finishedAt() == null ? null : run.finishedAt().toString(),
                run.id());
        return run;
    }

    @Override
    public Optional<AgentRun> findById(Long id) {
        List<AgentRun> runs = jdbcTemplate.query("SELECT * FROM agent_run WHERE id = ?", rowMapper, id);
        return runs.stream().findFirst();
    }

    @Override
    public List<AgentRun> findRecent(Long projectId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        if (projectId == null) {
            return jdbcTemplate.query("""
                    SELECT *
                    FROM agent_run
                    ORDER BY id DESC
                    LIMIT ?
                    """, rowMapper, safeLimit);
        }
        return jdbcTemplate.query("""
                SELECT *
                FROM agent_run
                WHERE project_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, rowMapper, projectId, safeLimit);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant parseNullableInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
