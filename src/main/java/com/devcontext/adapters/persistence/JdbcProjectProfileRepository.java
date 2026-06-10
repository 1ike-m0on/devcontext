package com.devcontext.adapters.persistence;

import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.ports.profile.ProjectProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class JdbcProjectProfileRepository implements ProjectProfileRepository {

    private static final TypeReference<List<ProjectProfileFact>> FACT_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ProjectProfile> rowMapper = (rs, rowNum) -> new ProjectProfile(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("status"),
            rs.getString("summary"),
            readJson(rs.getString("facts_json"), FACT_LIST),
            readJson(rs.getString("warnings_json"), STRING_LIST),
            Instant.parse(rs.getString("generated_at")),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcProjectProfileRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProjectProfile upsertByProjectId(ProjectProfile profile) {
        Optional<ProjectProfile> existing = findByProjectId(profile.projectId());
        if (existing.isEmpty()) {
            return insert(profile);
        }
        ProjectProfile current = existing.get();
        jdbcTemplate.update("""
                UPDATE project_profile
                SET status = ?, summary = ?, facts_json = ?, warnings_json = ?,
                    generated_at = ?, updated_at = ?
                WHERE project_id = ?
                """,
                profile.status(),
                profile.summary(),
                writeJson(profile.facts()),
                writeJson(profile.warnings()),
                profile.generatedAt().toString(),
                profile.updatedAt().toString(),
                profile.projectId());
        return new ProjectProfile(
                current.id(),
                profile.projectId(),
                profile.status(),
                profile.summary(),
                profile.facts(),
                profile.warnings(),
                profile.generatedAt(),
                current.createdAt(),
                profile.updatedAt()
        );
    }

    @Override
    public Optional<ProjectProfile> findByProjectId(Long projectId) {
        List<ProjectProfile> profiles = jdbcTemplate.query(
                "SELECT * FROM project_profile WHERE project_id = ?",
                rowMapper,
                projectId
        );
        return profiles.stream().findFirst();
    }

    private ProjectProfile insert(ProjectProfile profile) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO project_profile (
                        project_id, status, summary, facts_json, warnings_json,
                        generated_at, created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, profile.projectId());
            statement.setString(2, profile.status());
            statement.setString(3, profile.summary());
            statement.setString(4, writeJson(profile.facts()));
            statement.setString(5, writeJson(profile.warnings()));
            statement.setString(6, profile.generatedAt().toString());
            statement.setString(7, profile.createdAt().toString());
            statement.setString(8, profile.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new ProjectProfile(
                key == null ? null : key.longValue(),
                profile.projectId(),
                profile.status(),
                profile.summary(),
                profile.facts(),
                profile.warnings(),
                profile.generatedAt(),
                profile.createdAt(),
                profile.updatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize project profile JSON field", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse project profile JSON field", e);
        }
    }
}
