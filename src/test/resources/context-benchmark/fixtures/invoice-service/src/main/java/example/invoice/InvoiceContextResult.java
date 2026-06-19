package example.invoice;

import java.util.List;

record InvoiceContextResult(String invoiceId, List<String> evidenceFiles) {
}
