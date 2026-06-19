package example.invoice;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices/context")
class InvoiceContextController {
    private final InvoiceContextService invoiceContextService;

    InvoiceContextController(InvoiceContextService invoiceContextService) {
        this.invoiceContextService = invoiceContextService;
    }

    @PostMapping("/generate")
    InvoiceContextResult generateInvoiceContext() {
        return invoiceContextService.generateInvoiceContext("invoice-42");
    }
}
