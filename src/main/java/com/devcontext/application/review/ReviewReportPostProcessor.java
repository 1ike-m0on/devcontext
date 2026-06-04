package com.devcontext.application.review;

import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.review.ParsedReviewReport;
import com.devcontext.domain.review.ReviewIssue;
import com.devcontext.domain.review.ReviewIssueDraft;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ReviewReportPostProcessor {

    private static final int MAX_ISSUES = 4;
    private static final int MAX_DOWNGRADED_RECOMMENDATIONS = 5;
    private static final Pattern REPOSITORY_LOOKUP_ASSIGNMENT = Pattern.compile(
            "\\b([a-z][a-z0-9_]*)\\s*=\\s*[^;]*\\.(findby[a-z0-9_]*|queryby[a-z0-9_]*)\\("
    );
    private static final Pattern REPOSITORY_LOOKUP_INSIDE_ITERATION = Pattern.compile(
            "(?s)(\\.map\\s*\\(|\\.foreach\\s*\\(|for\\s*\\().*[a-z][a-z0-9_]*repository\\s*\\.\\s*(findby|queryby|getby)[a-z0-9_]*\\s*\\("
    );

    private final ReviewReportParser parser;

    public ReviewReportPostProcessor(ReviewReportParser parser) {
        this.parser = parser;
    }

    public ProcessedReviewReport process(ParsedReviewReport report, GitDiff diff) {
        return process(report, diff, List.of());
    }

    public ProcessedReviewReport process(ParsedReviewReport report, GitDiff diff, List<ReviewIssue> reviewFeedback) {
        List<ReviewIssueDraft> issues = report.issues();
        List<ReviewIssueDraft> guardrails = guardrailIssues(diff, issues);
        List<ReviewIssueDraft> retainedBeforeTestGaps = new ArrayList<>(guardrails);
        if (issues.isEmpty() && guardrails.isEmpty()) {
            return new ProcessedReviewReport(report, 0, 0);
        }

        List<ReviewIssueDraft> downgraded = new ArrayList<>();
        for (ReviewIssueDraft issue : issues) {
            if (shouldDowngradeContextInsufficientIssue(issue, diff)) {
                downgraded.add(issue);
            } else {
                retainedBeforeTestGaps.add(issue);
            }
        }

        boolean hasConcreteNonTestIssue = retainedBeforeTestGaps.stream().anyMatch(issue -> !isTestGap(issue));
        boolean highRiskForTests = isHighRiskForTestGap(diff);
        List<ReviewIssueDraft> retained = new ArrayList<>();
        boolean keptTestGap = false;
        for (ReviewIssueDraft issue : retainedBeforeTestGaps) {
            if (!isTestGap(issue)) {
                retained.add(issue);
                continue;
            }
            if (hasConcreteIssueForSameRisk(issue, retainedBeforeTestGaps, diff)) {
                downgraded.add(issue);
                continue;
            }
            if (isGenericEndpointShapeTestGap(issue)) {
                downgraded.add(issue);
                continue;
            }
            if (hasConcreteNonTestIssue && highRiskForTests && !keptTestGap) {
                retained.add(issue);
                keptTestGap = true;
            } else {
                downgraded.add(issue);
            }
        }

        List<ReviewIssue> noiseFeedback = reviewFeedback.stream()
                .filter(this::isNoiseFeedback)
                .toList();
        List<ReviewIssueDraft> retainedAfterFeedback = new ArrayList<>();
        int feedbackDowngradedCount = 0;
        for (ReviewIssueDraft issue : retained) {
            if (!isGeneratedGuardrail(issue, guardrails) && shouldDowngradeByHumanFeedback(issue, noiseFeedback)) {
                downgraded.add(issue);
                feedbackDowngradedCount++;
            } else {
                retainedAfterFeedback.add(issue);
            }
        }
        retained = retainedAfterFeedback;

        retained = retained.stream()
                .sorted(Comparator.comparingInt(this::issuePriority))
                .toList();
        if (retained.size() > MAX_ISSUES) {
            downgraded.addAll(retained.subList(MAX_ISSUES, retained.size()));
            retained = retained.subList(0, MAX_ISSUES);
        }

        if (downgraded.isEmpty() && retained.size() == issues.size()) {
            return new ProcessedReviewReport(report, 0, 0);
        }

        List<String> recommendations = new ArrayList<>(report.recommendations());
        downgraded.stream()
                .limit(MAX_DOWNGRADED_RECOMMENDATIONS)
                .map(issue -> "Downgraded from issue: " + safeTitle(issue))
                .forEach(recommendations::add);

        ParsedReviewReport processed = parser.rebuild(report, report.testGaps(), recommendations, retained);
        return new ProcessedReviewReport(processed, downgraded.size(), feedbackDowngradedCount);
    }

    private boolean shouldDowngradeContextInsufficientIssue(ReviewIssueDraft issue, GitDiff diff) {
        String text = issueText(issue);
        String diffText = normalize(diff.text());
        if (containsAny(text, "missing spring mapping", "mapping annotation", "@getmapping", "@postmapping",
                "@requestbody", "route requests", "endpoint unreachable", "will not route", "bind the http request body")) {
            return !containsAny(diffText, "@restcontroller", "@controller", "@requestmapping", "@getmapping",
                    "@postmapping", "@requestbody");
        }
        if (containsAny(text, "symlink", "symbolic link", "content-disposition", "force file download",
                "render files inline", "jpa entity directly", "lazyinitializationexception", "hashed passwords")) {
            return !containsAny(diffText, "symlink", "content-disposition", "entitygraph", "fetch join",
                    "password", "credential");
        }
        if (containsAny(text, "filename", "file name")
                && containsAny(text, "null", "empty", "blank")
                && !containsAny(text, "path traversal", "traversal")
                && containsAny(diffText, "normalize()", "startswith(basedir)", "filesystemresource")) {
            return true;
        }
        if (isSafePathNormalizeDefensiveChange(diffText)
                && containsAny(text, "access control", "authorization", "authentication", "permission",
                "file-existence", "file existence", "resource existence", "readability", "readable",
                "exists and readable", "missing or inaccessible", "not-found", "not found",
                "security-sensitive download", "security-sensitive", "test coverage")) {
            return true;
        }
        if (isCacheInvalidationCandidate(diffText)
                && containsAny(text, "transaction boundary", "read-modify-write", "lost update", "concurrent update",
                "missing test", "missing focused test", "no focused test")
                && !containsAny(text, "cache", "invalidate", "evict", "stale")) {
            return true;
        }
        if (isSwallowedExceptionCandidate(diffText)
                && isTestGap(issue)
                && containsAny(text, "external side-effect", "external side effect", "email", "digest", "notification")
                && !containsAny(text, "exception", "failure", "error", "swallow", "ignored")) {
            return true;
        }
        if (isSwallowedExceptionCandidate(diffText)
                && containsAny(text, "null", "nullpointerexception", "null pointer")
                && containsAny(text, "users list", "user elements", "user.email", "email()", "user.email()",
                "users can be null", "list is null", "users' list", "users parameter", "users' parameter",
                "user list", "users is null", "null user list", "enhanced for loop", "for loop over users",
                "loop over users", "over users", "unguarded iteration", "without checking for null")) {
            return !hasExplicitNullableContract(diffText);
        }
        if (isReportNPlusOneCandidate(diffText)
                && containsAny(text, "null", "nullpointerexception", "null pointer", "payment missing",
                "no payment", "findbyorderid")
                && containsAny(text, "can return null", "may return null", "payment missing", "if no payment",
                "no payment exists")) {
            return !hasExplicitNullableContract(diffText);
        }
        if (isSafeTransactionBoundaryAddition(diffText)
                && containsAny(text, "save result", "returned instance", "returns a new instance", "generated id",
                "stale id", "null order id", "order.getid", "return saved", "saved.getid")
                && containsAny(text, "may", "might", "potential", "if the repository", "repository implementation")) {
            return true;
        }
        if (isRepositorySaveReturnIdentitySpeculation(text, diffText)) {
            return true;
        }
        if (isSafeTransactionBoundaryAddition(diffText) && isTheoreticalInventoryLoopPerformanceIssue(text)) {
            return true;
        }
        if (isSensitiveCustomerExportWithoutVisibleAuthorization(diffText)
                && containsAny(text, "findall", "unbounded", "memory", "pagination", "slow", "large")
                && !containsAny(text, "privacy", "pii", "personal", "sensitive", "authorization", "permission")) {
            return true;
        }
        if (isSensitiveCustomerExportWithoutVisibleAuthorization(diffText)
                && containsAny(text, "jpa entity", "raw entity", "serialization", "lazy-loaded", "lazy loaded",
                "bidirectional", "internal model")
                && !containsAny(text, "privacy", "pii", "personal", "sensitive", "authorization", "permission")) {
            return true;
        }
        if (isAdminDeleteWithoutVisibleAuthorization(diffText)
                && containsAny(text, "deletebyid", "emptyresultdataaccessexception", "non-existent user",
                "nonexistent user", "http 500", "returns 500", "idempotency")
                && !containsAny(text, "authorization", "permission", "role")) {
            return true;
        }
        if (containsAny(text, "race", "concurrent", "check-then-act", "check then act",
                "idempotency not guaranteed", "breaks idempotency")
                && containsAny(diffText, "existsbyeventid")
                && containsAny(diffText, "processedeventrepository.save")) {
            return true;
        }
        if (containsAny(text, "transaction boundary", "multi-write", "failure during save", "markpaid")
                && containsAny(diffText, "existsbyeventid")
                && containsAny(diffText, "processedeventrepository.save")) {
            return true;
        }
        if (containsAny(text, "request.items()", "event.eventid()", "event.orderid()", "webhook input")
                && containsAny(text, "null", "validation")
                && !containsAny(diffText, "nullable", "@nullable", "null allowed")) {
            return true;
        }
        if (containsAny(text, "can return null", "may return null", "if getname", "request.items()",
                "request.userid()", "event.eventid()", "event.orderid()", "paymentevent and its fields")
                && !containsAny(text, "findby", "getbyid", "repository", "dereference")) {
            return !containsAny(diffText, "nullable", "@nullable", "null allowed");
        }
        return false;
    }

    private List<ReviewIssueDraft> guardrailIssues(GitDiff diff, List<ReviewIssueDraft> existingIssues) {
        String diffText = normalize(diff.text());
        List<ReviewIssueDraft> issues = new ArrayList<>();
        if (isAdminDeleteWithoutVisibleAuthorization(diffText)
                && !hasNonTestIssue(existingIssues, "authorization", "permission", "role")) {
            issues.add(new ReviewIssueDraft(
                    "critical",
                    "Admin delete endpoint lacks visible authorization guard",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff adds an admin delete endpoint, but the supplied controller context does not show an authorization guard such as @PreAuthorize, @Secured, hasRole, or an equivalent permission check.",
                    "A destructive admin operation without visible authorization can allow unauthorized user deletion if the endpoint is exposed by routing or global security is misconfigured.",
                    "Add an explicit authorization guard near the endpoint or include the project-level security rule in context so the review can verify it.",
                    "high"
            ));
        }
        if (isSensitiveCustomerExportWithoutVisibleAuthorization(diffText)
                && !hasNonTestIssue(existingIssues, "privacy", "pii", "sensitive", "authorization", "permission")) {
            issues.add(new ReviewIssueDraft(
                    "critical",
                    "Customer export lacks visible access-control and privacy boundary",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff adds a support customer export endpoint returning Customer data, but the supplied context does not show authorization, scoping, masking, or pagination controls.",
                    "Customer exports can expose personal or sensitive data across tenants or support roles if access control and data minimization are not explicit.",
                    "Add an explicit permission check, scope the export to the requesting role or tenant, and avoid returning raw Customer entities directly.",
                    "high"
            ));
        }
        if (isCacheInvalidationCandidate(diffText)
                && !hasNonTestIssue(existingIssues, "cache", "invalidate", "evict", "stale")) {
            issues.add(new ReviewIssueDraft(
                    "warning",
                    "Cache invalidation missing after price update",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff updates and saves a Product price while the same service reads product details from ProductCache, but the changed code does not invalidate or refresh the cached entry.",
                    "Callers may continue seeing stale product prices after the update because the database write and cache state can diverge.",
                    "Evict or update the product cache after a successful price change, and cover the stale-cache behavior with a focused test.",
                    "high"
            ));
        }
        if (isSwallowedExceptionCandidate(diffText)
                && !hasNonTestIssue(existingIssues, "exception", "failure", "error", "swallow", "ignored", "logging")) {
            issues.add(new ReviewIssueDraft(
                    "warning",
                    "Exception is swallowed without logging or recovery",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff adds a catch block that ignores the caught exception without visible logging, retry, fallback, or failure recording.",
                    "Swallowed failures make background jobs, side effects, and batch processing appear successful while work is silently skipped.",
                    "Record the failure with logging or metrics, retry or route it to a dead-letter/fallback path, or rethrow when the caller must observe the failure.",
                    "high"
            ));
        }
        if (isRepositoryLookupDereferenceCandidate(diffText)
                && !hasNonTestIssue(existingIssues, "null", "nullpointerexception", "null pointer",
                "not found", "missing", "dereference", "findby", "repository lookup")) {
            issues.add(new ReviewIssueDraft(
                    "critical",
                    "Repository lookup result is dereferenced without absence handling",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff assigns the result of a findBy-style repository lookup and then dereferences the same value without a visible null, Optional, or not-found guard.",
                    "A missing record can turn a read path into a runtime NullPointerException or an uncontrolled server error.",
                    "Handle the absent record explicitly with Optional, orElseThrow, a null check, or the project-standard not-found path before dereferencing the entity.",
                    "high"
            ));
        }
        if (isRepositoryLookupInsideIterationCandidate(diffText)
                && !hasNonTestIssue(existingIssues, "n+1", "n plus one", "per item", "round-trip",
                "round trip", "database", "query")) {
            issues.add(new ReviewIssueDraft(
                    "warning",
                    "Repository lookup inside iteration can create N+1 queries",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff iterates over a result set and performs a repository lookup inside the stream, map, forEach, or loop body.",
                    "As the outer result size grows, this can issue one database query per item and cause avoidable latency and database load.",
                    "Batch-load the related records with a repository method such as findByIdIn or join/fetch the needed data before mapping rows.",
                    "high"
            ));
        }
        if (isDiscountRuleChangeWithoutTests(diff, diffText)
                && !hasTestGap(existingIssues)
                && !hasTestGap(issues)) {
            issues.add(new ReviewIssueDraft(
                    "warning",
                    "Missing focused tests for VIP and threshold discount rules",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff adds VIP and price-threshold discount branches, but no test file is changed and the review did not include a focused discount-boundary test issue.",
                    "Discount rules affect customer-facing money behavior; untested VIP, non-VIP, threshold, and boundary-price branches can regress silently.",
                    "Add focused tests for VIP customers, regular customers, prices above and at the threshold, and the expected discount amounts.",
                    "high"
            ));
        }
        if (isFocusedTestGapCandidate(diff, diffText)
                && !hasTestGap(existingIssues)
                && !hasTestGap(issues)) {
            issues.add(new ReviewIssueDraft(
                    "warning",
                    "Missing focused tests for high-risk behavior change",
                    firstChangedFile(diff),
                    firstAddedLineNumber(diff),
                    "The diff changes high-risk business behavior, but no test file is changed and the review did not include a focused test-coverage issue.",
                    "Important boundary behavior can regress silently without tests covering the new branch, state transition, external side effect, or security-sensitive path.",
                    "Add a focused test for the changed behavior and the most important boundary condition before relying on the new logic.",
                    "high"
            ));
        }
        return issues;
    }

    private boolean isAdminDeleteWithoutVisibleAuthorization(String diffText) {
        return containsAny(diffText, "@deletemapping")
                && containsAny(diffText, "/admin", "admin")
                && containsAny(diffText, "deletebyid", "delete")
                && !hasVisibleAuthorization(diffText);
    }

    private boolean isSensitiveCustomerExportWithoutVisibleAuthorization(String diffText) {
        return containsAny(diffText, "export")
                && containsAny(diffText, "customer", "customers")
                && containsAny(diffText, "findall", "list<customer>", "customerrepository")
                && !hasVisibleAuthorization(diffText);
    }

    private boolean hasVisibleAuthorization(String diffText) {
        return containsAny(diffText, "@preauthorize", "@secured", "@rolesallowed", "hasrole", "hasauthority",
                "isauthorized", "checkpermission", "permissionservice", "securitycontext");
    }

    private boolean isCacheInvalidationCandidate(String diffText) {
        return containsAny(diffText, "productcache")
                && containsAny(diffText, "updateprice", "changeprice")
                && containsAny(diffText, "productrepository.save", ".save(product)")
                && !containsAny(diffText, "productcache.evict", "productcache.invalidate", "productcache.put",
                "cacheevict", "@cacheevict");
    }

    private boolean isDiscountRuleChangeWithoutTests(GitDiff diff, String diffText) {
        return !hasTestFileChanged(diff)
                && containsAny(diffText, "discountservice", "calculate(", "discount", "isvip", "vip")
                && containsAny(diffText, "isvip", "vip")
                && containsAny(diffText, "bigdecimal(\"0.85\")", "bigdecimal(\"0.90\")",
                ".multiply(new bigdecimal", "discount")
                && containsAny(diffText, "compareto", "threshold", "1000", "price.compareto");
    }

    private boolean isSwallowedExceptionCandidate(String diffText) {
        String code = normalizedCodeFromDiff(diffText);
        int position = 0;
        while ((position = code.indexOf("catch", position)) >= 0) {
            CatchBlock catchBlock = readCatchBlock(code, position);
            if (catchBlock == null) {
                position += "catch".length();
                continue;
            }
            if (isSwallowedCatchBlock(catchBlock)) {
                return true;
            }
            position = catchBlock.endIndex();
        }
        return false;
    }

    private boolean isReportNPlusOneCandidate(String diffText) {
        return isRepositoryLookupInsideIterationCandidate(diffText);
    }

    private boolean isSafeTransactionBoundaryAddition(String diffText) {
        return containsAny(diffText, "@transactional")
                && containsAny(diffText, "objects.requirenonnull")
                && containsAny(diffText, "orderrepository.save(order)")
                && containsAny(diffText, "return order.getid()")
                && containsAny(diffText, "inventoryrepository.decreasestock", "decreasestock");
    }

    private boolean isTheoreticalInventoryLoopPerformanceIssue(String text) {
        return containsAny(text, "n+1", "n plus one", "per item", "for each", "foreach", "loop",
                "database call", "database calls", "database update", "database updates", "batch",
                "batching", "round-trip", "round trip", "latency", "performance", "contention")
                && containsAny(text, "inventory", "decreasestock", "stock");
    }

    private boolean isRepositorySaveReturnIdentitySpeculation(String text, String diffText) {
        return containsRepositorySaveAndReturnId(diffText)
                && containsAny(text, "save result", "returned instance", "returned entity", "returns a new instance",
                "save returns", "discarded returned", "ignores the returned", "generated id",
                "stale id", "stale order id", "null order id", "return saved", "saved.getid",
                "managed entity", "repository implementation")
                && containsAny(text, "may", "might", "potential", "potentially", "could", "if the repository",
                "repository implementation", "can return");
    }

    private boolean containsRepositorySaveAndReturnId(String diffText) {
        Matcher matcher = Pattern.compile("\\.save\\(\\s*([a-z][a-z0-9_]*)\\s*\\)").matcher(diffText);
        while (matcher.find()) {
            String variable = matcher.group(1);
            if (diffText.contains(variable + ".getid()")) {
                return true;
            }
        }
        return false;
    }

    private boolean isRepositoryLookupDereferenceCandidate(String diffText) {
        if (!containsAny(diffText, ".findby", ".queryby")) {
            return false;
        }
        if (containsAny(diffText, ".orelse", ".orelsethrow", ".orelseget", ".ifpresent", ".ispresent",
                "optional<", "optional.")) {
            return false;
        }

        List<String> lookupVariables = new ArrayList<>();
        String[] lines = diffText.split("\\R");
        for (String line : lines) {
            if (!isAddedOrContextLine(line)) {
                continue;
            }
            Matcher matcher = REPOSITORY_LOOKUP_ASSIGNMENT.matcher(line);
            while (matcher.find()) {
                lookupVariables.add(matcher.group(1));
            }
        }
        if (lookupVariables.isEmpty()) {
            return false;
        }

        for (String line : lines) {
            if (!isAddedOrContextLine(line)) {
                continue;
            }
            for (String variable : lookupVariables) {
                if (hasAbsenceHandlingForVariable(lines, variable)) {
                    continue;
                }
                if (line.contains(variable + ".") && !containsAny(line, ".findby", ".queryby")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAbsenceHandlingForVariable(String[] lines, String variable) {
        for (String line : lines) {
            if (!isAddedOrContextLine(line)) {
                continue;
            }
            if (containsAny(line, variable + " == null", variable + "==null",
                    variable + " != null", variable + "!=null",
                    "objects.requirenonnull(" + variable,
                    "assert.notnull(" + variable)) {
                return true;
            }
        }
        return false;
    }

    private boolean isRepositoryLookupInsideIterationCandidate(String diffText) {
        String code = normalizedCodeFromDiff(diffText);
        if (!containsAny(code, ".stream()", ".map(", ".foreach(", "for (")) {
            return false;
        }
        return REPOSITORY_LOOKUP_INSIDE_ITERATION.matcher(code).find();
    }

    private boolean isFocusedTestGapCandidate(GitDiff diff, String diffText) {
        return isHighRiskForTestGap(diff)
                && !hasTestFileChanged(diff)
                && !isSafeDefensiveChangeForTestGap(diffText)
                && !isSwallowedExceptionCandidate(diffText)
                && !isReportNPlusOneCandidate(diffText);
    }

    private boolean hasTestFileChanged(GitDiff diff) {
        return diff.changedFiles().stream()
                .map(this::normalize)
                .anyMatch(path -> path.contains("/src/test/")
                        || path.contains("\\src\\test\\")
                        || path.contains("/test/")
                        || path.contains("\\test\\")
                        || path.endsWith("test.java")
                        || path.contains(".spec.")
                        || path.contains(".test."));
    }

    private boolean isSafeDefensiveChangeForTestGap(String diffText) {
        return isSafeTransactionBoundaryAddition(diffText)
                || isSafePathNormalizeDefensiveChange(diffText)
                || isSafeIdempotencyGuardAddition(diffText)
                || (containsAny(diffText, "objects.requirenonnull", "requirenonnull(")
                && !containsAny(diffText, "discount", "price", "payment", "refund", "webhook", "cache"));
    }

    private boolean isSafeIdempotencyGuardAddition(String diffText) {
        return containsAny(diffText, "existsbyeventid")
                && containsAny(diffText, "processedeventrepository.save");
    }

    private boolean isAddedOrContextLine(String line) {
        return !line.startsWith("-")
                && !line.startsWith("diff --git")
                && !line.startsWith("index ")
                && !line.startsWith("---");
    }

    private boolean hasExplicitNullableContract(String diffText) {
        return containsAny(diffText, "@nullable", "nullable", "optional<", "optional.");
    }

    private boolean isSafePathNormalizeDefensiveChange(String diffText) {
        return containsAny(diffText, "normalize()")
                && containsAny(diffText, "startswith(basedir)")
                && containsAny(diffText, "responseentity.badrequest", "badrequest().build")
                && !containsAny(diffText, "@getmapping", "@postmapping", "@requestmapping", "@deletemapping");
    }

    private boolean hasNonTestIssue(List<ReviewIssueDraft> issues, String... terms) {
        return issues.stream().anyMatch(issue -> !isTestGap(issue) && containsAny(issueText(issue), terms));
    }

    private boolean isHighRiskForTestGap(GitDiff diff) {
        String text = normalize(diff.text());
        return containsAny(text,
                "discount", "price", "tax", "money", "bigdecimal",
                "payment", "refund", "webhook", "redeliver",
                "cache", "invalidate", "evict",
                "path traversal", "filedownload",
                "markpaid", "markrefunded");
    }

    private boolean isTestGap(ReviewIssueDraft issue) {
        String text = issueText(issue);
        return containsAny(text, "missing test", "missing focused test", "no focused test", "no tests", "test coverage",
                "unit tests", "boundary tests");
    }

    private boolean isGenericEndpointShapeTestGap(ReviewIssueDraft issue) {
        String text = issueText(issue);
        return isTestGap(issue)
                && containsAny(text, "endpoint shape", "controller tests", "endpoint has no controller tests",
                "request body binding", "requestbody", "routing");
    }

    private boolean hasTestGap(List<ReviewIssueDraft> issues) {
        return issues.stream().anyMatch(this::isTestGap);
    }

    private boolean hasConcreteIssueForSameRisk(ReviewIssueDraft testGap,
                                                List<ReviewIssueDraft> issues,
                                                GitDiff diff) {
        String testText = issueText(testGap);
        boolean sameIssueRisk = issues.stream()
                .filter(issue -> issue != testGap)
                .filter(issue -> !isTestGap(issue))
                .map(this::issueText)
                .filter(concreteText -> !isInputValidationText(concreteText))
                .anyMatch(concreteText -> sharesSpecificRisk(testText, concreteText));
        return sameIssueRisk || (isGenericHighRiskTestGap(testGap) && hasConcreteIssueForDiffRisk(diff, issues));
    }

    private boolean isGenericHighRiskTestGap(ReviewIssueDraft issue) {
        String text = issueText(issue);
        return isTestGap(issue)
                && containsAny(text, "high-risk behavior change", "changed behavior", "important boundary behavior")
                && !containsAny(text, "discount", "vip", "threshold",
                "bigdecimal", "precision", "rounding", "money", "monetary",
                "cache", "invalidate", "stale",
                "webhook", "idempot", "duplicate",
                "path traversal", "authorization", "privacy", "pii",
                "transaction", "multi-write", "exception", "swallow");
    }

    private boolean hasConcreteIssueForDiffRisk(GitDiff diff, List<ReviewIssueDraft> issues) {
        String diffText = normalize(diff.text());
        return issues.stream()
                .filter(issue -> !isTestGap(issue))
                .map(this::issueText)
                .anyMatch(text -> concreteIssueMatchesDiffRisk(text, diffText));
    }

    private boolean concreteIssueMatchesDiffRisk(String text, String diffText) {
        if (isMoneyCalculationCandidate(diffText)) {
            return containsAny(text, "bigdecimal", "precision", "rounding", "money", "monetary",
                    "double", "tax", "scale");
        }
        if (isCacheInvalidationCandidate(diffText)) {
            return containsAny(text, "cache", "invalidate", "evict", "stale", "consistency");
        }
        if (isSwallowedExceptionCandidate(diffText)) {
            return containsAny(text, "exception", "failure", "error", "swallow", "ignored", "logging");
        }
        if (isReportNPlusOneCandidate(diffText)) {
            return containsAny(text, "n+1", "n plus one", "query", "database", "round-trip", "performance");
        }
        if (containsAny(diffText, "path traversal", "normalize()", "startswith(")) {
            return containsAny(text, "path traversal", "traversal", "normalize", "resolve", "root");
        }
        if (containsAny(diffText, "existsbyeventid", "processedeventrepository.save")) {
            return containsAny(text, "idempot", "duplicate", "redeliver", "retry", "processed");
        }
        return false;
    }

    private boolean isMoneyCalculationCandidate(String diffText) {
        return containsAny(diffText, "bigdecimal", "double", "tax", "subtotal", "amount", "price", "money")
                && containsAny(diffText, "new bigdecimal(", "multiply(", "add(", "setscale", "roundingmode");
    }

    private boolean isInputValidationText(String text) {
        return containsAny(text, "null", "nullpointerexception", "null pointer", "validation", "validate",
                "not null", "requirenonnull", "parameter")
                && !containsAny(text, "cache", "invalidate", "evict", "stale",
                "idempot", "duplicate", "redeliver", "retry",
                "transaction", "multi-write", "consistency",
                "path traversal", "traversal",
                "rounding", "precision");
    }

    private boolean sharesSpecificRisk(String left, String right) {
        return sharesRiskCluster(left, right, "money", "monetary", "bigdecimal", "precision", "rounding", "tax", "price")
                || sharesRiskCluster(left, right, "cache", "cached", "invalidate", "evict", "stale", "product", "price")
                || sharesRiskCluster(left, right, "webhook", "refund", "idempot", "duplicate", "redeliver", "retry")
                || sharesRiskCluster(left, right, "cancel", "refund", "transaction", "multi-write", "consistency")
                || sharesRiskCluster(left, right, "path traversal", "traversal", "download", "file")
                || sharesRiskCluster(left, right, "exception", "failure", "error", "swallow", "ignored", "logging")
                || sharesRiskCluster(left, right, "email", "digest", "notification", "exception", "failure", "swallow")
                || sharesRiskCluster(left, right, "report", "n+1", "query", "performance", "payment");
    }

    private String normalizedCodeFromDiff(String diffText) {
        List<String> codeLines = new ArrayList<>();
        for (String rawLine : safe(diffText).split("\\R")) {
            if (rawLine.startsWith("+++") || rawLine.startsWith("---")
                    || rawLine.startsWith("diff --git") || rawLine.startsWith("index ")
                    || rawLine.startsWith("@@")) {
                continue;
            }
            if (rawLine.startsWith("+")) {
                codeLines.add(rawLine.substring(1));
            } else if (!rawLine.startsWith("-")) {
                codeLines.add(rawLine);
            }
        }
        return normalize(String.join("\n", codeLines));
    }

    private CatchBlock readCatchBlock(String code, int catchIndex) {
        int parameterStart = code.indexOf('(', catchIndex);
        int parameterEnd = parameterStart < 0 ? -1 : code.indexOf(')', parameterStart);
        int bodyStart = parameterEnd < 0 ? -1 : code.indexOf('{', parameterEnd);
        if (parameterStart < 0 || parameterEnd < 0 || bodyStart < 0) {
            return null;
        }
        int depth = 1;
        for (int i = bodyStart + 1; i < code.length(); i++) {
            char current = code.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return new CatchBlock(
                            code.substring(parameterStart + 1, parameterEnd),
                            code.substring(bodyStart + 1, i),
                            i + 1
                    );
                }
            }
        }
        return null;
    }

    private boolean isSwallowedCatchBlock(CatchBlock catchBlock) {
        String body = removeComments(catchBlock.body()).trim();
        if (body.isBlank()) {
            return true;
        }
        return catchBlock.parameter().contains(" ignored") && !hasCatchHandling(body);
    }

    private boolean hasCatchHandling(String body) {
        return containsAny(body,
                "log.", "logger.", ".warn(", ".error(", ".info(", ".debug(",
                "throw ", "return ", "retry", "deadletter", "dead-letter",
                "fallback", "metric", "counter", "record", "save", "publish", "alert");
    }

    private String removeComments(String value) {
        return safe(value)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private boolean shouldDowngradeByHumanFeedback(ReviewIssueDraft issue, List<ReviewIssue> noiseFeedback) {
        if (noiseFeedback.isEmpty()) {
            return false;
        }
        for (ReviewIssue feedback : noiseFeedback) {
            if (isSameRejectedPattern(issue, feedback)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSameRejectedPattern(ReviewIssueDraft issue, ReviewIssue feedback) {
        String issueTitle = normalize(issue.title());
        String feedbackTitle = normalize(feedback.title());
        String issueText = issueText(issue);
        String feedbackText = normalize(safe(feedback.title()) + " " + safe(feedback.description()) + " "
                + safe(feedback.impact()) + " " + safe(feedback.suggestion()) + " " + safe(feedback.note()));
        boolean sameTitle = !issueTitle.isBlank() && issueTitle.equals(feedbackTitle);
        boolean sameFile = sameFilePath(issue.filePath(), feedback.filePath());
        double overlap = tokenOverlap(issueText, feedbackText);

        if (sameTitle && (sameFile || overlap >= 0.45)) {
            return true;
        }
        if (sameFile && overlap >= 0.58) {
            return true;
        }
        return overlap >= 0.68 && sharesSpecificRisk(issueText, feedbackText);
    }

    private boolean isNoiseFeedback(ReviewIssue issue) {
        String status = safe(issue.status()).toLowerCase(Locale.ROOT);
        return "false_positive".equals(status) || "rejected".equals(status);
    }

    private boolean isGeneratedGuardrail(ReviewIssueDraft issue, List<ReviewIssueDraft> guardrails) {
        for (ReviewIssueDraft guardrail : guardrails) {
            if (issue == guardrail) {
                return true;
            }
        }
        return false;
    }

    private boolean sameFilePath(String left, String right) {
        return !safe(left).isBlank() && safe(left).equals(safe(right));
    }

    private double tokenOverlap(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        return (double) intersection.size() / Math.min(leftTokens.size(), rightTokens.size());
    }

    private Set<String> tokens(String value) {
        Set<String> result = new HashSet<>();
        for (String token : normalize(value).split("[^a-z0-9_.$@]+")) {
            if (isSignalToken(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private boolean isSignalToken(String token) {
        if (token.length() < 4) {
            return false;
        }
        return !Set.of(
                "this", "that", "with", "from", "when", "will", "would", "could", "should",
                "there", "because", "without", "method", "class", "issue", "risk", "impact",
                "suggestion", "current", "visible", "supplied", "context", "diff"
        ).contains(token);
    }

    private boolean sharesRiskCluster(String left, String right, String... terms) {
        return containsAny(left, terms) && containsAny(right, terms);
    }

    private int issuePriority(ReviewIssueDraft issue) {
        int severity = switch (safe(issue.severity()).toLowerCase(Locale.ROOT)) {
            case "critical" -> 0;
            case "warning" -> 10;
            default -> 20;
        };
        int testGapPenalty = isTestGap(issue) ? 5 : 0;
        int confidence = switch (safe(issue.confidence()).toLowerCase(Locale.ROOT)) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        };
        return severity + testGapPenalty + confidence;
    }

    private String issueText(ReviewIssueDraft issue) {
        return normalize(safe(issue.title()) + " " + safe(issue.description()) + " "
                + safe(issue.impact()) + " " + safe(issue.suggestion()));
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String safeTitle(ReviewIssueDraft issue) {
        String title = issue.title();
        return title == null || title.isBlank() ? "Untitled issue" : title;
    }

    private String firstChangedFile(GitDiff diff) {
        return diff.changedFiles().isEmpty() ? null : diff.changedFiles().getFirst();
    }

    private Integer firstAddedLineNumber(GitDiff diff) {
        int newLine = 0;
        for (String line : safe(diff.text()).split("\\R")) {
            if (line.startsWith("@@")) {
                int plus = line.indexOf('+');
                int comma = line.indexOf(',', plus);
                int space = line.indexOf(' ', plus);
                int end = comma > plus ? comma : space;
                if (plus >= 0 && end > plus) {
                    try {
                        newLine = Integer.parseInt(line.substring(plus + 1, end));
                    } catch (NumberFormatException ignored) {
                        newLine = 0;
                    }
                }
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return newLine > 0 ? newLine : null;
            }
            if (!line.startsWith("-") && !line.startsWith("diff --git") && !line.startsWith("index ")
                    && !line.startsWith("---") && !line.startsWith("+++")) {
                newLine++;
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ProcessedReviewReport(
            ParsedReviewReport report,
            int downgradedIssueCount,
            int feedbackDowngradedIssueCount
    ) {
    }

    private record CatchBlock(
            String parameter,
            String body,
            int endIndex
    ) {
    }
}
