package example.invoice;

import example.invoice.model.Invoice;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInvoiceRepository {
    private final JdbcTemplate jdbcTemplate;

    JdbcInvoiceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    void save(Invoice invoice) {
        jdbcTemplate.update(
                "INSERT INTO invoice (id, customer_name, total_cents) VALUES (?, ?, ?)",
                invoice.id(),
                invoice.customerName(),
                invoice.totalCents()
        );
    }

    List<Invoice> findAll() {
        return jdbcTemplate.query(
                "SELECT id, customer_name, total_cents FROM invoice",
                (rs, rowNum) -> new Invoice(
                        rs.getString("id"),
                        rs.getString("customer_name"),
                        rs.getLong("total_cents")
                )
        );
    }
}
