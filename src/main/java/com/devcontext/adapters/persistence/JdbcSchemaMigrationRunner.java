package com.devcontext.adapters.persistence;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcSchemaMigrationRunner {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        addColumnIfMissing("agent_run", "provider", "TEXT");
        addColumnIfMissing("decision_card", "embedding_status", "TEXT NOT NULL DEFAULT 'pending'");
        addColumnIfMissing("decision_card", "embedding_updated_at", "TEXT");
        addColumnIfMissing("decision_reuse_record", "run_id", "INTEGER");
        addColumnIfMissing("decision_reuse_record", "status", "TEXT NOT NULL DEFAULT 'pending'");
        addColumnIfMissing("decision_reuse_record", "user_feedback", "TEXT");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_decision_reuse_record_run_id
                ON decision_reuse_record (run_id)
                """);
        createObservationTable();
        createProjectProfileTable();
    }

    private void createObservationTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS observation (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER,
                    source_type TEXT NOT NULL,
                    source_record_id TEXT NOT NULL,
                    source_key TEXT NOT NULL,
                    task_type TEXT NOT NULL,
                    lifecycle TEXT NOT NULL,
                    source_status TEXT,
                    title TEXT NOT NULL,
                    summary TEXT,
                    occurred_at TEXT NOT NULL,
                    provider TEXT,
                    model_name TEXT,
                    error_type TEXT,
                    error_message_summary TEXT,
                    run_id INTEGER,
                    event_id INTEGER,
                    retrieval_id INTEGER,
                    review_id INTEGER,
                    issue_id INTEGER,
                    decision_reuse_record_id INTEGER,
                    report_run_id TEXT,
                    report_path TEXT,
                    relation_json TEXT NOT NULL DEFAULT '{}',
                    metadata_json TEXT NOT NULL DEFAULT '{}',
                    privacy_level TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_observation_source_key
                ON observation (source_type, source_key)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_project_time
                ON observation (project_id, occurred_at)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_run_id
                ON observation (run_id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_review_id
                ON observation (review_id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_retrieval_id
                ON observation (retrieval_id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_observation_task_lifecycle_time
                ON observation (task_type, lifecycle, occurred_at)
                """);
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (hasColumn(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private void createProjectProfileTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS project_profile (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    facts_json TEXT NOT NULL,
                    warnings_json TEXT NOT NULL,
                    generated_at TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_project_profile_project_id
                ON project_profile (project_id)
                """);
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        return columns.stream()
                .map(column -> String.valueOf(column.get("name")))
                .anyMatch(columnName::equalsIgnoreCase);
    }
}
