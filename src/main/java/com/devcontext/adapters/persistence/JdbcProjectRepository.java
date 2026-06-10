package com.devcontext.adapters.persistence;

import com.devcontext.domain.project.Project;
import com.devcontext.ports.project.ProjectRepository;
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
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProjectRepository implements ProjectRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Project> rowMapper = (rs, rowNum) -> new Project(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("root_path"),
            rs.getString("language"),
            rs.getString("framework"),
            rs.getString("default_branch"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
    );

    public JdbcProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Project save(Project project) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO project (name, root_path, language, framework, default_branch, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, project.name());
            statement.setString(2, project.rootPath());
            statement.setString(3, project.language());
            statement.setString(4, project.framework());
            statement.setString(5, project.defaultBranch());
            statement.setString(6, project.createdAt().toString());
            statement.setString(7, project.updatedAt().toString());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new Project(
                key == null ? null : key.longValue(),
                project.name(),
                project.rootPath(),
                project.language(),
                project.framework(),
                project.defaultBranch(),
                project.createdAt(),
                project.updatedAt()
        );
    }

    @Override
    public Project update(Project project) {
        jdbcTemplate.update("""
                UPDATE project
                SET name = ?, root_path = ?, language = ?, framework = ?, default_branch = ?, updated_at = ?
                WHERE id = ?
                """,
                project.name(),
                project.rootPath(),
                project.language(),
                project.framework(),
                project.defaultBranch(),
                project.updatedAt().toString(),
                project.id());
        return project;
    }

    @Override
    public Optional<Project> findById(Long id) {
        List<Project> projects = jdbcTemplate.query("SELECT * FROM project WHERE id = ?", rowMapper, id);
        return projects.stream().findFirst();
    }

    @Override
    public List<Project> findAll() {
        return jdbcTemplate.query("SELECT * FROM project ORDER BY id DESC", rowMapper);
    }

    @Override
    @Transactional
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM project_graph_edge WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM project_graph_node WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM project_profile WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM observation WHERE project_id = ?", id);
        jdbcTemplate.update("""
                DELETE FROM review_issue
                WHERE review_id IN (SELECT id FROM review_record WHERE project_id = ?)
                """, id);
        jdbcTemplate.update("DELETE FROM review_record WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM decision_reuse_record WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM decision_card WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM retrieval_record WHERE run_id IN (SELECT id FROM agent_run WHERE project_id = ?)", id);
        jdbcTemplate.update("DELETE FROM agent_event WHERE run_id IN (SELECT id FROM agent_run WHERE project_id = ?)", id);
        jdbcTemplate.update("DELETE FROM agent_run WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM context_item WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM context_document WHERE project_id = ?", id);
        jdbcTemplate.update("DELETE FROM project WHERE id = ?", id);
    }
}
