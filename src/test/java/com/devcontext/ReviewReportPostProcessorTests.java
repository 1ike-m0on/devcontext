package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.review.ReviewReportParser;
import com.devcontext.application.review.ReviewReportPostProcessor;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.review.ParsedReviewReport;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewIssueDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewReportPostProcessorTests {

    private final ReviewReportParser parser = new ReviewReportParser(new ObjectMapper());
    private final ReviewReportPostProcessor postProcessor = new ReviewReportPostProcessor(parser);

    @Test
    void downgradesContextInsufficientFrameworkAndNestedNullabilityIssues() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Missing Spring mapping annotation makes endpoint unreachable",
                        "The diff does not show @GetMapping or @PostMapping."),
                issue("warning", "Potential null return if User.getName() returns null",
                        "If getName returns null, the method returns null rather than a safe default."),
                issue("warning", "Missing focused tests for new displayName method",
                        "The diff adds new behavior without tests.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/user/UserService.java b/src/main/java/com/acme/user/UserService.java
                +    public String displayName(Long id) {
                +        Objects.requireNonNull(id, "id");
                +        return userRepository.findById(id)
                +            .map(User::getName)
                +            .orElse("Unknown user");
                +    }
                """, List.of("src/main/java/com/acme/user/UserService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(3);
        assertThat(processed.report().recommendations())
                .anyMatch(value -> value.contains("Missing Spring mapping annotation"))
                .anyMatch(value -> value.contains("Potential null return"))
                .anyMatch(value -> value.contains("Missing focused tests"));
    }

    @Test
    void downgradesRelatedTestGapWhenConcreteHighRiskIssueAlreadyCoversFailureMode() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Webhook handler lacks idempotency",
                        "Duplicate webhook deliveries can process the same refund twice."),
                issue("warning", "Missing focused tests for refund webhook handler",
                        "No redelivery or duplicate webhook tests are present."),
                issue("warning", "No tests for endpoint shape",
                        "The endpoint has no controller tests."),
                issue("warning", "Method parameter missing @RequestBody",
                        "The supplied diff does not show request body binding.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/payment/RefundWebhookController.java b/src/main/java/com/acme/payment/RefundWebhookController.java
                +    public ResponseEntity<Void> handle(RefundEvent event) {
                +        refundService.markRefunded(event.refundId());
                +        notificationService.sendRefundSuccess(event.orderId());
                +        return ResponseEntity.ok().build();
                +    }
                """, List.of("src/main/java/com/acme/payment/RefundWebhookController.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Webhook handler lacks idempotency");
        assertThat(processed.downgradedIssueCount()).isEqualTo(3);
    }

    @Test
    void upgradesAdminDeleteAuthorizationEvenWhenModelOnlyReportsTestGap() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Missing focused tests for admin user deletion endpoint",
                        "Missing authorization could lead to unintended user deletion.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/admin/AdminUserController.java b/src/main/java/com/acme/admin/AdminUserController.java
                --- a/src/main/java/com/acme/admin/AdminUserController.java
                +++ b/src/main/java/com/acme/admin/AdminUserController.java
                @@ -10,6 +10,14 @@ public class AdminUserController {
                     private final UserRepository userRepository;

                +    @DeleteMapping("/admin/users/{id}")
                +    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
                +        userRepository.deleteById(id);
                +        return ResponseEntity.noContent().build();
                +    }
                +
                """, List.of("src/main/java/com/acme/admin/AdminUserController.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Admin delete endpoint lacks visible authorization guard");
        assertThat(processed.report().issues().getFirst().lineNumber()).isEqualTo(12);
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void upgradesCustomerExportPrivacyAndDowngradesGenericFindAllPerformance() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Unbounded findAll risks large memory consumption and slow responses",
                        "The exportCustomers method calls customerRepository.findAll() without pagination.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/support/SupportExportController.java b/src/main/java/com/acme/support/SupportExportController.java
                --- a/src/main/java/com/acme/support/SupportExportController.java
                +++ b/src/main/java/com/acme/support/SupportExportController.java
                @@ -9,6 +9,15 @@ public class SupportExportController {
                     private final CustomerRepository customerRepository;

                +    @GetMapping("/support/customers/export")
                +    public List<Customer> exportCustomers() {
                +        return customerRepository.findAll();
                +    }
                +
                """, List.of("src/main/java/com/acme/support/SupportExportController.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Customer export lacks visible access-control and privacy boundary");
        assertThat(processed.report().issues().getFirst().impact()).contains("sensitive");
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesDefensiveIdempotencyGuardSpeculation() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Non-transactional multi-write operation breaks idempotency",
                        "The existsByEventId check followed by markPaid and save is a race."),
                issue("critical", "Missing transaction boundary on multi-write webhook handler",
                        "A failure during save will leave the order marked as paid but not recorded as processed."),
                issue("warning", "Missing null validation on webhook input",
                        "The method dereferences event.eventId() and event.orderId()."),
                issue("warning", "No focused tests for high-risk behavior change",
                        "The webhook handler changes payment state.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/payment/PaymentWebhookController.java b/src/main/java/com/acme/payment/PaymentWebhookController.java
                +    public ResponseEntity<Void> handle(PaymentEvent event) {
                +        if (processedEventRepository.existsByEventId(event.eventId())) {
                +            return ResponseEntity.ok().build();
                +        }
                +        paymentService.markPaid(event.orderId());
                +        processedEventRepository.save(new ProcessedEvent(event.eventId()));
                +        return ResponseEntity.ok().build();
                +    }
                """, List.of("src/main/java/com/acme/payment/PaymentWebhookController.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(4);
    }

    @Test
    void upgradesCacheInvalidationAndDowngradesGenericPriceUpdateNoise() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Missing transaction boundary for read-modify-write",
                        "The updatePrice method reads a product, modifies its price, and saves it without a transaction."),
                issue("warning", "Missing test for price update logic",
                        "No test added for the new updatePrice method.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/catalog/ProductService.java b/src/main/java/com/acme/catalog/ProductService.java
                +    private final ProductRepository productRepository;
                +    private final ProductCache productCache;
                +
                +    public Product updatePrice(Long productId, BigDecimal price) {
                +        Product product = productRepository.getById(productId);
                +        product.changePrice(price);
                +        productRepository.save(product);
                +        return product;
                +    }
                +
                +    public Product detail(Long productId) {
                +        return productCache.get(productId);
                +    }
                """, List.of("src/main/java/com/acme/catalog/ProductService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Cache invalidation missing after price update");
        assertThat(processed.downgradedIssueCount()).isEqualTo(2);
    }

    @Test
    void addsGuardrailForDirectFindByIdDereferenceWhenModelMissesIt() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/user/UserService.java b/src/main/java/com/acme/user/UserService.java
                +    public String displayName(Long id) {
                +        User user = userRepository.findById(id);
                +        return user.getName();
                +    }
                """, List.of("src/main/java/com/acme/user/UserService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Repository lookup result is dereferenced without absence handling");
        assertThat(processed.report().issues().getFirst().severity()).isEqualTo("critical");
        assertThat(processed.downgradedIssueCount()).isZero();
    }

    @Test
    void addsGuardrailForFindByStyleLookupChainDereferenceWhenModelMissesIt() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/profile/ProfileService.java b/src/main/java/com/acme/profile/ProfileService.java
                +    public String avatarUrl(Long userId) {
                +        Profile profile = profileRepository.findByUserId(userId);
                +        return profile.getAvatar().getUrl();
                +    }
                """, List.of("src/main/java/com/acme/profile/ProfileService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Repository lookup result is dereferenced without absence handling");
        assertThat(processed.report().issues().getFirst().description())
                .contains("findBy-style repository lookup");
    }

    @Test
    void doesNotAddLookupDereferenceGuardrailWhenNullGuardExists() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/profile/ProfileService.java b/src/main/java/com/acme/profile/ProfileService.java
                +    public String avatarUrl(Long userId) {
                +        Profile profile = profileRepository.findByUserId(userId);
                +        if (profile == null) {
                +            return "";
                +        }
                +        return profile.getAvatar().getUrl();
                +    }
                """, List.of("src/main/java/com/acme/profile/ProfileService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
    }

    @Test
    void addsGuardrailForRepositoryLookupInsideStreamWhenModelMissesIt() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/report/OrderReportService.java b/src/main/java/com/acme/report/OrderReportService.java
                +    public List<OrderReportRow> recentRows() {
                +        return orderRepository.findRecent().stream()
                +            .map(order -> new OrderReportRow(
                +                order.getId(),
                +                paymentRepository.findByOrderId(order.getId()).status()))
                +            .toList();
                +    }
                """, List.of("src/main/java/com/acme/report/OrderReportService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Repository lookup inside iteration can create N+1 queries");
        assertThat(processed.report().issues().getFirst().description())
                .contains("repository lookup inside the stream");
    }

    @Test
    void addsGuardrailForMissingFocusedTestsOnHighRiskBusinessChange() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Missing null checks for customer and price parameters",
                        "The method dereferences customer and price without validation.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/pricing/DiscountService.java b/src/main/java/com/acme/pricing/DiscountService.java
                +    public BigDecimal calculate(BigDecimal price, Customer customer) {
                +        if (customer.isVip()) {
                +            return price.multiply(new BigDecimal("0.85"));
                +        }
                +        if (price.compareTo(new BigDecimal("1000")) > 0) {
                +            return price.multiply(new BigDecimal("0.90"));
                +        }
                +        return price;
                +    }
                """, List.of("src/main/java/com/acme/pricing/DiscountService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly(
                        "Missing null checks for customer and price parameters",
                        "Missing focused tests for VIP and threshold discount rules"
                );
    }

    @Test
    void keepsDiscountTestGapWhenModelOnlyReportsInputValidationIssues() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Null price causes NullPointerException",
                        "The added price.compareTo(new BigDecimal(\"1000\")) will throw if price is null."),
                issue("warning", "Null customer parameter causes NullPointerException",
                        "The method calls customer.isVip() without validating customer.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/pricing/DiscountService.java b/src/main/java/com/acme/pricing/DiscountService.java
                +    public BigDecimal calculate(BigDecimal price, Customer customer) {
                +        if (customer.isVip()) {
                +            return price.multiply(new BigDecimal("0.85"));
                +        }
                +        if (price.compareTo(new BigDecimal("1000")) > 0) {
                +            return price.multiply(new BigDecimal("0.90"));
                +        }
                +        return price;
                +    }
                """, List.of("src/main/java/com/acme/pricing/DiscountService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .contains(
                        "Null price causes NullPointerException",
                        "Null customer parameter causes NullPointerException",
                        "Missing focused tests for VIP and threshold discount rules"
                );
    }

    @Test
    void downgradesDuplicateTestGapAfterConcreteSwallowedExceptionIssue() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Exceptions are silently swallowed with no error handling",
                        "The catch block ignores failures from emailClient.sendDigest."),
                issue("warning", "Missing focused tests for new external side-effect logic",
                        "The new method sends email digests but no tests were added for the external side effect.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/notification/NotificationJob.java b/src/main/java/com/acme/notification/NotificationJob.java
                +    public void sendDailyDigest(List<User> users) {
                +        for (User user : users) {
                +            try {
                +                emailClient.sendDigest(user.email());
                +            } catch (Exception ignored) {
                +            }
                +        }
                +    }
                """, List.of("src/main/java/com/acme/notification/NotificationJob.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Exceptions are silently swallowed with no error handling");
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void addsGuardrailForEmptyCatchWithoutVisibleHandlingAcrossDomains() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/billing/SettlementJob.java b/src/main/java/com/acme/billing/SettlementJob.java
                +    public void settlePendingInvoices(List<Invoice> invoices) {
                +        for (Invoice invoice : invoices) {
                +            try {
                +                paymentGateway.capture(invoice.id());
                +            } catch (IOException ex) {
                +            }
                +        }
                +    }
                """, List.of("src/main/java/com/acme/billing/SettlementJob.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Exception is swallowed without logging or recovery");
        assertThat(processed.report().issues().getFirst().description())
                .contains("catch block")
                .contains("ignores")
                .contains("exception");
    }

    @Test
    void doesNotAddSwallowedExceptionGuardrailWhenCatchHandlesFailure() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/billing/SettlementJob.java b/src/main/java/com/acme/billing/SettlementJob.java
                +    public void settlePendingInvoices(List<Invoice> invoices) {
                +        for (Invoice invoice : invoices) {
                +            try {
                +                paymentGateway.capture(invoice.id());
                +            } catch (IOException ex) {
                +                logger.warn("Failed to capture invoice {}", invoice.id(), ex);
                +                failureRepository.save(invoice.id(), ex.getMessage());
                +            }
                +        }
                +    }
                """, List.of("src/main/java/com/acme/billing/SettlementJob.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
    }

    @Test
    void downgradesUnprovenSaveReturnIdentitySpeculationForSafeTransactionAddition() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Potential null or stale order ID after save",
                        "The method calls orderRepository.save(order) but discards the returned instance. If the repository implementation returns a new instance with the generated ID, order.getId() may be stale.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/order/OrderService.java b/src/main/java/com/acme/order/OrderService.java
                +    @Transactional
                +    public Long placeOrder(CreateOrderRequest request) {
                +        Objects.requireNonNull(request, "request");
                +        Order order = Order.create(request.userId(), request.items());
                +        orderRepository.save(order);
                +        request.items().forEach(item -> inventoryRepository.decreaseStock(item.sku(), item.quantity()));
                +        return order.getId();
                +    }
                """, List.of("src/main/java/com/acme/order/OrderService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesUnprovenSaveReturnIdentitySpeculationEvenWhenTransactionIssueRemains() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Missing transaction boundary for multi-write order placement",
                        "The method saves an order and decreases inventory without @Transactional."),
                issue("critical", "Returning potentially null or stale order ID after save",
                        "Code calls orderRepository.save(order) but ignores the returned entity, then returns order.getId(). If the repository implementation returns a different instance, the ID may be stale.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/order/OrderService.java b/src/main/java/com/acme/order/OrderService.java
                +    public Long placeOrder(CreateOrderRequest request) {
                +        Order order = Order.create(request.userId(), request.items());
                +        orderRepository.save(order);
                +        for (OrderItem item : request.items()) {
                +            inventoryRepository.decreaseStock(item.sku(), item.quantity());
                +        }
                +        return order.getId();
                +    }
                """, List.of("src/main/java/com/acme/order/OrderService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Missing transaction boundary for multi-write order placement");
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesTheoreticalLoopPerformanceIssueForSafeTransactionAddition() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "N+1 database calls in inventory stock decrease loop",
                        "The method calls inventoryRepository.decreaseStock() for each item. Without batching, this results in N separate database updates and increased latency.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/order/OrderService.java b/src/main/java/com/acme/order/OrderService.java
                +    @Transactional
                +    public Long placeOrder(CreateOrderRequest request) {
                +        Objects.requireNonNull(request, "request");
                +        Order order = Order.create(request.userId(), request.items());
                +        orderRepository.save(order);
                +        request.items().forEach(item -> inventoryRepository.decreaseStock(item.sku(), item.quantity()));
                +        return order.getId();
                +    }
                """, List.of("src/main/java/com/acme/order/OrderService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesMoneyTestGapWhenConcretePrecisionIssueExists() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Precision loss from double-to-BigDecimal conversion",
                        "new BigDecimal(taxRate) inherits the inexact double representation and can produce incorrect monetary totals."),
                issue("warning", "Missing unit tests for high-risk money logic",
                        "The new money calculation has no focused tests.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/billing/InvoiceService.java b/src/main/java/com/acme/billing/InvoiceService.java
                +    public BigDecimal totalWithTax(BigDecimal subtotal, double taxRate) {
                +        BigDecimal tax = subtotal.multiply(new BigDecimal(taxRate));
                +        return subtotal.add(tax);
                +    }
                """, List.of("src/main/java/com/acme/billing/InvoiceService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Precision loss from double-to-BigDecimal conversion");
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesGenericGeneratedTestGapWhenConcreteMoneyIssueExists() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Precision loss from double-to-BigDecimal conversion",
                        "new BigDecimal(taxRate) inherits the inexact double representation and can produce incorrect monetary totals.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/billing/InvoiceService.java b/src/main/java/com/acme/billing/InvoiceService.java
                +    public BigDecimal totalWithTax(BigDecimal subtotal, double taxRate) {
                +        BigDecimal tax = subtotal.multiply(new BigDecimal(taxRate));
                +        return subtotal.add(tax);
                +    }
                """, List.of("src/main/java/com/acme/billing/InvoiceService.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Precision loss from double-to-BigDecimal conversion");
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
    }

    @Test
    void downgradesUnprovenCollectionAndRepositoryNullabilitySpeculation() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "NullPointerException risk from unguarded iteration over 'users' List",
                        "The method iterates over the 'users' parameter without checking for null. If the caller passes null, a NullPointerException will be thrown."),
                issue("critical", "NullPointerException when payment missing",
                        "paymentRepository.findByOrderId(order.getId()) may return null if no payment exists.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/notification/NotificationJob.java b/src/main/java/com/acme/notification/NotificationJob.java
                +    public void sendDailyDigest(List<User> users) {
                +        for (User user : users) {
                +            try {
                +                emailClient.sendDigest(user.email());
                +            } catch (Exception ignored) {
                +            }
                +        }
                +    }
                diff --git a/src/main/java/com/acme/report/OrderReportService.java b/src/main/java/com/acme/report/OrderReportService.java
                +    public List<OrderReportRow> recentRows() {
                +        return orderRepository.findRecent().stream()
                +            .map(order -> new OrderReportRow(
                +                order.getId(),
                +                paymentRepository.findByOrderId(order.getId()).status()))
                +            .toList();
                +    }
                """, List.of(
                "src/main/java/com/acme/notification/NotificationJob.java",
                "src/main/java/com/acme/report/OrderReportService.java"
        ), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Repository lookup inside iteration can create N+1 queries");
        assertThat(processed.downgradedIssueCount()).isEqualTo(2);
    }

    @Test
    void downgradesEndpointSpeculationForSafePathNormalizeDefensiveChange() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Missing access control on file download endpoint",
                        "The new download method exposes files without visible authorization."),
                issue("warning", "Missing resource existence/readability check",
                        "The response does not verify that the resource exists and is readable."),
                issue("warning", "No test coverage for security-sensitive download logic",
                        "No tests were added for path traversal protection.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/file/FileDownloadController.java b/src/main/java/com/acme/file/FileDownloadController.java
                +    public ResponseEntity<Resource> download(String fileName) {
                +        Path target = baseDir.resolve(fileName).normalize();
                +        if (!target.startsWith(baseDir)) {
                +            return ResponseEntity.badRequest().build();
                +        }
                +        FileSystemResource resource = new FileSystemResource(target);
                +        return ResponseEntity.ok(resource);
                +    }
                """, List.of("src/main/java/com/acme/file/FileDownloadController.java"), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(3);
    }

    @Test
    void downgradesIssueRepeatedFromFalsePositiveFeedback() {
        ParsedReviewReport report = report(List.of(
                issue("critical", "Possible null dereference",
                        "findById may return null before getName and cause a NullPointerException.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/Demo.java b/src/main/java/com/acme/Demo.java
                +    String userName(Long id) {
                +        User user = userRepository.findById(id);
                +        return user.getName();
                +    }
                """, List.of("src/main/java/com/acme/Demo.java"), "hash", false);
        ReviewIssue falsePositive = feedback(
                "false_positive",
                "Possible null dereference",
                "findById may return null before getName.",
                "Repository contract already guarantees a non-null user in this module."
        );

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(
                report,
                diff,
                List.of(falsePositive)
        );

        assertThat(processed.report().issues()).isEmpty();
        assertThat(processed.downgradedIssueCount()).isEqualTo(1);
        assertThat(processed.feedbackDowngradedIssueCount()).isEqualTo(1);
        assertThat(processed.report().recommendations())
                .anyMatch(value -> value.contains("Possible null dereference"));
    }

    @Test
    void keepsGuardrailIssueEvenWhenSimilarRejectedFeedbackExists() {
        ParsedReviewReport report = report(List.of());
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/admin/AdminUserController.java b/src/main/java/com/acme/admin/AdminUserController.java
                +    @DeleteMapping("/admin/users/{id}")
                +    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
                +        userRepository.deleteById(id);
                +        return ResponseEntity.noContent().build();
                +    }
                """, List.of("src/main/java/com/acme/admin/AdminUserController.java"), "hash", false);
        ReviewIssue rejected = feedback(
                "rejected",
                "Admin delete endpoint lacks visible authorization guard",
                "The diff adds an admin delete endpoint without an authorization guard.",
                "Rejected once during local-only fixture review."
        );

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(
                report,
                diff,
                List.of(rejected)
        );

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly("Admin delete endpoint lacks visible authorization guard");
        assertThat(processed.downgradedIssueCount()).isZero();
        assertThat(processed.feedbackDowngradedIssueCount()).isZero();
    }

    @Test
    void downgradesLowerPriorityAdminDeleteAndCustomerExportFollowUpsAfterGuardrails() {
        ParsedReviewReport report = report(List.of(
                issue("warning", "Unhandled deleteById exception causes HTTP 500",
                        "deleteById can throw EmptyResultDataAccessException for a non-existent user."),
                issue("warning", "Returning raw JPA entity exposes internal model and risks serialization issues",
                        "Returning List<Customer> directly can expose internal model and lazy-loaded relationships.")
        ));
        GitDiff diff = new GitDiff("""
                diff --git a/src/main/java/com/acme/support/SupportExportController.java b/src/main/java/com/acme/support/SupportExportController.java
                +    @GetMapping("/support/customers/export")
                +    public List<Customer> exportCustomers() {
                +        return customerRepository.findAll();
                +    }
                diff --git a/src/main/java/com/acme/admin/AdminUserController.java b/src/main/java/com/acme/admin/AdminUserController.java
                +    @DeleteMapping("/admin/users/{id}")
                +    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
                +        userRepository.deleteById(id);
                +        return ResponseEntity.noContent().build();
                +    }
                """, List.of(
                "src/main/java/com/acme/support/SupportExportController.java",
                "src/main/java/com/acme/admin/AdminUserController.java"
        ), "hash", false);

        ReviewReportPostProcessor.ProcessedReviewReport processed = postProcessor.process(report, diff);

        assertThat(processed.report().issues())
                .extracting(ReviewIssueDraft::title)
                .containsExactly(
                        "Admin delete endpoint lacks visible authorization guard",
                        "Customer export lacks visible access-control and privacy boundary"
                );
        assertThat(processed.downgradedIssueCount()).isEqualTo(2);
    }

    private ParsedReviewReport report(List<ReviewIssueDraft> issues) {
        return new ParsedReviewReport(
                3.0,
                "summary",
                "change",
                "scope",
                List.of(),
                List.of(),
                issues,
                "",
                "{}"
        );
    }

    private ReviewIssueDraft issue(String severity, String title, String description) {
        return new ReviewIssueDraft(
                severity,
                title,
                "src/main/java/com/acme/Demo.java",
                10,
                description,
                "impact",
                "suggestion",
                "high"
        );
    }

    private ReviewIssue feedback(String status, String title, String description, String note) {
        Instant now = Instant.now();
        return new ReviewIssue(
                1L,
                1L,
                "warning",
                title,
                "src/main/java/com/acme/Demo.java",
                10,
                description,
                "impact",
                "suggestion",
                "high",
                status,
                note,
                now,
                now
        );
    }
}
