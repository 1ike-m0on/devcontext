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
        addColumnIfMissing("decision_card", "embedding_status", "TEXT NOT NULL DEFAULT 'pending'");
        addColumnIfMissing("decision_card", "embedding_updated_at", "TEXT");
        addColumnIfMissing("decision_reuse_record", "run_id", "INTEGER");
        addColumnIfMissing("decision_reuse_record", "status", "TEXT NOT NULL DEFAULT 'pending'");
        addColumnIfMissing("decision_reuse_record", "user_feedback", "TEXT");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_decision_reuse_record_run_id
                ON decision_reuse_record (run_id)
                """);
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        if (hasColumn(tableName, columnName)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
    }

    private boolean hasColumn(String tableName, String columnName) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        return columns.stream()
                .map(column -> String.valueOf(column.get("name")))
                .anyMatch(columnName::equalsIgnoreCase);
    }
}
