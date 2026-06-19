package example.invoice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvoiceContextServiceTests {
    @Test
    void generatesInvoiceContextEvidence() {
        InvoiceContextService service = new InvoiceContextService();

        InvoiceContextResult result = service.generateInvoiceContext("invoice-42");

        assertThat(result.evidenceFiles()).contains("schema.sql", "JdbcInvoiceRepository");
    }
}
