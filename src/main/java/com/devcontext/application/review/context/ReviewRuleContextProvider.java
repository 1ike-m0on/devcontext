package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleContextProvider implements ReviewContextProvider {

    private static final String RULES = """
            Review focus:
            1. Functional correctness and edge cases.
            2. Null safety and parameter validation.
            3. Exception handling and transaction boundaries.
            4. Data consistency and concurrency.
            5. Performance risks such as N+1 queries or repeated IO.
            6. Security risks such as secrets, unsafe input, or permission gaps.
            7. Maintainability, module boundaries, and test gaps.

            Severity:
            - critical: must fix before merge.
            - warning: should fix or explicitly accept.
            - info: optional improvement.

            Constraints:
            - Write review output in English so reports and benchmark labels are stable.
            - Focus on this diff.
            - First classify the diff as risky change or safe defensive change. Defensive changes that add an obvious guard should be allowed to produce zero ReviewIssue records.
            - Do not invent issues without evidence.
            - Evidence gate: every ReviewIssue must be grounded in a changed line, project rule, coding preference, or visible API/framework contract.
            - Use low confidence only for evidence-backed issues whose severity is uncertain; pure speculation belongs in recommendations, not issues.
            - Do not report ORM lazy-loading, transaction-proxy, distributed-lock, cache-staleness, or concurrency assumptions unless the diff or project context explicitly shows that technology, constraint, or shared mutable state.
            - If a risk depends on unproven implementation assumptions, do not include it as an issue.
            - Do not report missing framework annotations such as @GetMapping, @PostMapping, @RequestBody, or route binding unless the supplied diff/context shows the full controller contract.
            - Do not report nested DTO/entity field nullability unless the diff shows an explicit dereference of a known nullable value or a project rule says the field can be null.
            - Do not report repository or collection null-return assumptions unless the method signature, annotation, project rule, or supplied context proves nullable behavior.
            - Do not report repository save-return identity or generated-id assumptions unless the supplied repository contract proves the saved entity is different from the input entity.
            - For newly added admin, support, customer export, or destructive endpoints, check visible authorization, permission, privacy, and data-scope boundaries before performance or test-gap observations.
            - For newly added webhook or callback handlers that perform side effects, check idempotency/deduplication before secondary validation, transaction, or test observations. Repeated delivery, retry, replay, duplicate event, and redelivery are the same risk family.
            - If the diff adds a safe transaction boundary/null guard around an existing workflow, do not report theoretical per-item database calls, N+1, batching, or latency risks unless the diff itself adds the loop/query pattern or the supplied context proves this path is performance-sensitive.
            - When the diff mainly adds a defensive guard, do not turn missing surrounding invariants into issues unless the changed lines still expose a concrete runtime or security failure.
            - Prefer high-signal findings with concrete runtime, data consistency, security, idempotency, performance, or test coverage impact.
            - Run a mandatory test-coverage pass before returning JSON.
            - Report a missing focused-test issue only for high-risk behavior changes: money/discount/tax calculation, permission/security/privacy, state transitions, multi-write consistency, cache invalidation, webhook/retry semantics, or external side effects. For simple defensive changes, put test suggestions in testGaps or recommendations, not issues.
            - If a high-risk money/discount branch, state transition, cache invalidation path, webhook handler, or external side effect is changed and no test file is present in the diff, include one focused missing-test issue unless a concrete issue already covers exactly the same failure mode.
            - Do not add a separate missing-test issue when a concrete issue already covers the same failure mode. Keep the concrete issue and move the test note to testGaps or recommendations.
            - Missing focused tests are first-class review issues only when the high-risk behavior rule above is met. Do not use them as generic issues for every production code change.
            - Do not replace a concrete missing-test issue with a low-confidence business-requirement question. Keep uncertain business intent as supporting context.
            - Do not include speculative business-intent questions as issues when no explicit requirement or project rule proves the expected behavior. Mention them in recommendations only when they materially affect follow-up work; otherwise omit them.
            - Do not report generic documentation, style, or maintainability advice unless the diff clearly changes a public API contract or creates a concrete engineering risk.
            - Return at most 4 ReviewIssue records. Prefer critical correctness/security/data-loss findings first, then one high-risk test-gap issue if applicable. Move the rest to recommendations.
            - Do not rewrite large blocks of code.
            """;

    @Override
    public boolean supports(ReviewContextRequest request) {
        return true;
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        return List.of(new ContextItem(
                null,
                null,
                request.project().id(),
                "REVIEW_RULES",
                "Review rules",
                RULES,
                "built-in:review-rules",
                700,
                estimateTokens(RULES),
                sha256(RULES),
                Instant.now()
        ));
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
