package example.invoice;

import example.invoice.model.Invoice;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
class InvoiceContextService {
    InvoiceContextResult generateInvoiceContext(String invoiceId) {
        Invoice invoice = new Invoice(invoiceId, "Ada", 4200L);
        return new InvoiceContextResult(invoice.id(), List.of("schema.sql", "JdbcInvoiceRepository"));
    }
}
