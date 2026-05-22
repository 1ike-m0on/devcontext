package com.devcontext.adapters.persistence;

import com.devcontext.domain.run.AgentEvent;
import com.devcontext.ports.run.AgentEventRepository;
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
public class JdbcAgentEventRepository implements AgentEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AgentEvent> rowMapper = (rs, rowNum) -> new AgentEvent(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getString("event_type"),
            rs.getString("input_summary"),
            rs.getString("output_summary"),
            rs.getString("status"),
            nullableLong(rs, "duration_ms"),
            rs.getString("error_message"),
            Instant.parse(rs.getString("created_at"))
    );

    public JdbcAgentEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public AgentEvent save(AgentEvent event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_event (
                        run_id, event_type, input_summary, output_summary, status,
                        duration_ms, error_message, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, event.runId());
            statement.setString(2, event.eventType());
            statement.setString(3, event.inputSummary());
            statement.setString(4, event.outputSummary());
            statement.setString(5, event.status());
            statement.setObject(6, event.durationMs());
            statement.setString(7, event.errorMessage());
            statement.setString(8, event.createdAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new AgentEvent(
                key == null ? null : key.longValue(),
                event.runId(),
                event.eventType(),
                event.inputSummary(),
                event.outputSummary(),
                event.status(),
                event.durationMs(),
                event.errorMessage(),
                event.createdAt()
        );
    }

    @Override
    public List<AgentEvent> findByRunId(Long runId) {
        return jdbcTemplate.query("SELECT * FROM agent_event WHERE run_id = ? ORDER BY id ASC", rowMapper, runId);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

