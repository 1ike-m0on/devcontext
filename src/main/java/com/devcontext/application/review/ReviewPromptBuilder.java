package com.devcontext.application.review;

import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.project.Project;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ReviewPromptBuilder {

    public String build(Project project, GitDiff diff, List<ContextItem> contextItems, String mode) {
        return """
                You are a senior Java backend code reviewer.

                Task:
                Review the current Git diff using the project context, review rules, coding preferences, and pitfalls.

                Project:
                - name: %s
                - path: %s
                - language: %s
                - framework: %s
                - review mode: %s

                Changed files:
                %s
                - diff truncated by DevContext: %s

                Context:
                %s

                Output strict JSON only, without Markdown fences.
                Use this schema:
                {
                  "score": 4.2,
                  "summary": "short review summary",
                  "changeIntent": "what this diff appears to change",
                  "impactScope": "affected modules and behavior",
                  "testGaps": ["missing test or validation"],
                  "recommendations": ["next action"],
                  "issues": [
                    {
                      "severity": "critical|warning|info",
                      "title": "short issue title",
                      "filePath": "path/from/repo",
                      "lineNumber": 12,
                      "description": "evidence-based issue",
                      "impact": "why it matters",
                      "suggestion": "specific fix suggestion",
                      "confidence": "high|medium|low"
                    }
                  ]
                }

                Rules:
                - Write all JSON string values in English.
                - Focus on the diff.
                - First decide whether the diff is a safe defensive change. If it mainly adds an existing guard, null handling, transaction boundary, idempotency check, or path normalization and no concrete remaining risk is visible, return an empty issues array.
                - Do not invent problems without evidence.
                - Evidence gate: every issue must be backed by at least one changed diff line, project context/rule, coding preference, or visible API/framework contract in the supplied context.
                - Use REVIEW_FEEDBACK_MEMORY signals to calibrate precision. confirmed_issue_pattern entries are useful precedent only when the current diff has matching evidence.
                - Do not repeat false_positive_pattern entries unless the current diff provides stronger direct evidence than the historical rejected finding.
                - Human feedback does not override the evidence gate; it only helps decide what to prioritize or suppress.
                - Use confidence low only for evidence-backed issues whose severity is uncertain; pure speculation belongs in recommendations, not issues.
                - Do not report ORM lazy-loading, transaction-proxy, distributed-lock, cache-staleness, or concurrency assumptions unless the diff or project context explicitly shows that technology, constraint, or shared mutable state.
                - If a risk depends on words like "if this is lazy", "assuming", or another unproven implementation detail, do not include it in issues.
                - Do not report missing framework annotations such as @GetMapping, @PostMapping, @RequestBody, or route binding unless the supplied diff/context shows the full controller contract.
                - Do not report nested DTO/entity field nullability unless the diff shows an explicit dereference of a known nullable value or a project rule says the field can be null.
                - Do not report repository or collection null-return assumptions unless the method signature, annotation, project rule, or supplied context proves nullable behavior.
                - Do not report repository save-return identity or generated-id assumptions unless the supplied repository contract proves the saved entity is different from the input entity.
                - For newly added admin, support, customer export, or destructive endpoints, check visible authorization, permission, privacy, and data-scope boundaries before performance or test-gap observations.
                - For newly added webhook or callback handlers that perform side effects, check idempotency/deduplication before secondary validation, transaction, or test observations. Repeated delivery, retry, replay, duplicate event, and redelivery are the same risk family.
                - If the diff adds a safe transaction boundary/null guard around an existing workflow, do not report theoretical per-item database calls, N+1, batching, or latency risks unless the diff itself adds the loop/query pattern or the supplied context proves this path is performance-sensitive.
                - When the diff mainly adds a defensive guard, do not turn missing surrounding invariants into issues unless the changed lines still expose a concrete runtime or security failure.
                - Only include issues with concrete runtime, data consistency, security, idempotency, performance, or test coverage impact.
                - Run a mandatory test-coverage pass before returning JSON.
                - Report a missing focused-test issue only for high-risk behavior changes: money/discount/tax calculation, permission/security/privacy, state transitions, multi-write consistency, cache invalidation, webhook/retry semantics, or external side effects. For simple defensive changes, put test suggestions in testGaps or recommendations, not issues.
                - If a high-risk money/discount branch, state transition, cache invalidation path, webhook handler, or external side effect is changed and no test file is present in the diff, include one focused missing-test issue unless a concrete issue already covers exactly the same failure mode.
                - Do not add a separate missing-test issue when a concrete issue already covers the same failure mode. Keep the concrete issue and move the test note to testGaps or recommendations.
                - A missing focused-test issue is first-class only when the high-risk behavior rule above is met. Do not use it as a generic issue for every production code change.
                - Do not replace a concrete missing-test issue with a low-confidence business-requirement question. If the business intent is unclear, mention it only as low-confidence context after reporting the test gap.
                - Do not include speculative business-intent questions as issues when the diff has no explicit requirement or project rule proving the expected behavior. Put them in recommendations only if they materially affect follow-up work; otherwise omit them.
                - Do not report generic documentation, style, or maintainability advice as an issue unless the diff clearly changes a public API contract or creates a concrete engineering risk.
                - Return at most 4 issues. Prefer critical correctness/security/data-loss findings first, then one high-risk test-gap issue if applicable. Move the rest to recommendations.
                - Prefer fewer high-signal issues over many low-value observations.
                - If there are no issues, return an empty issues array and a useful summary.
                - Keep suggestions concise.
                """.formatted(
                project.name(),
                project.rootPath(),
                project.language(),
                project.framework(),
                mode == null || mode.isBlank() ? "DEFAULT" : mode,
                diff.changedFiles().isEmpty() ? "No changed files listed." : String.join(System.lineSeparator(), diff.changedFiles()),
                diff.truncated() ? "yes" : "no",
                renderContext(contextItems)
        );
    }

    private String renderContext(List<ContextItem> contextItems) {
        if (contextItems.isEmpty()) {
            return "Project context is unavailable.";
        }
        return contextItems.stream()
                .map(item -> """
                        --- %s | %s | priority=%d | source=%s ---
                        %s
                        """.formatted(item.type(), item.title(), item.priority(), item.source(), item.content()))
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
