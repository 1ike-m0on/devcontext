package example.invoice;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class InvoiceSchemaMigrationRunner {
    private final JdbcTemplate jdbcTemplate;

    InvoiceSchemaMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void migrateInvoiceSchema() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS invoice_audit (id VARCHAR(64) PRIMARY KEY)");
    }
}
