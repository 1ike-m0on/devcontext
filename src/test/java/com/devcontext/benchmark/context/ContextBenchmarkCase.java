package com.devcontext.benchmark.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextBenchmarkCase(
        String caseId,
        String suite,
        String question,
        String language,
        String projectKind,
        String projectRoot,
        String sourceCapability,
        List<String> tags,
        ContextBenchmarkExpected expected
) {
    public ContextBenchmarkCase {
        caseId = text(caseId);
        suite = text(suite);
        question = text(question);
        language = text(language);
        projectKind = text(projectKind);
        projectRoot = text(projectRoot);
        sourceCapability = text(sourceCapability);
        tags = tags == null ? List.of() : List.copyOf(tags);
        expected = expected == null
                ? new ContextBenchmarkExpected("", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), java.util.Map.of(), List.of(), null, null, "")
                : expected;
    }

    ContextBenchmarkCase withSuite(String value) {
        return new ContextBenchmarkCase(
                caseId,
                text(value),
                question,
                language,
                projectKind,
                projectRoot,
                sourceCapability,
                tags,
                expected
        );
    }

    boolean pendingByDefault() {
        return tags.stream().anyMatch(tag -> {
            String normalized = tag == null ? "" : tag.trim().toLowerCase(java.util.Locale.ROOT);
            return "pending".equals(normalized)
                    || "future-product-context".equals(normalized)
                    || "pending-product-context".equals(normalized);
        }) || sourceCapability.toLowerCase(java.util.Locale.ROOT).startsWith("pending");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
