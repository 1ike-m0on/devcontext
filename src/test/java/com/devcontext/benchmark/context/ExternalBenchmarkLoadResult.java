package com.devcontext.benchmark.context;

import java.util.List;

public record ExternalBenchmarkLoadResult(
        List<ContextBenchmarkCase> cases,
        boolean unavailable,
        String failureCategory,
        String unavailableReason,
        List<String> attemptedPaths
) {
    public ExternalBenchmarkLoadResult {
        cases = cases == null ? List.of() : List.copyOf(cases);
        failureCategory = failureCategory == null || failureCategory.isBlank()
                ? "EXTERNAL_ASSET_UNAVAILABLE"
                : failureCategory;
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
        attemptedPaths = attemptedPaths == null ? List.of() : List.copyOf(attemptedPaths);
    }

    static ExternalBenchmarkLoadResult available(List<ContextBenchmarkCase> cases) {
        return new ExternalBenchmarkLoadResult(cases, false, "", "", List.of());
    }

    static ExternalBenchmarkLoadResult unavailable(String reason, List<String> attemptedPaths) {
        return new ExternalBenchmarkLoadResult(List.of(), true, "EXTERNAL_ASSET_UNAVAILABLE", reason, attemptedPaths);
    }

    static ExternalBenchmarkLoadResult notImplemented(String suiteName) {
        return new ExternalBenchmarkLoadResult(
                List.of(),
                true,
                "NOT_IMPLEMENTED",
                suiteName + " adapter is reserved but not implemented in this slice",
                List.of()
        );
    }
}
