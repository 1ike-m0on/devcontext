package com.devcontext.benchmark.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextBenchmarkResult(
        String runId,
        String generatedAt,
        String gitBranch,
        String gitCommit,
        boolean gitDirty,
        String suite,
        String caseId,
        String question,
        String language,
        String projectKind,
        String expectedIntent,
        String actualIntent,
        List<String> expectedSourcePaths,
        List<String> productSelectedSourcePaths,
        List<String> diagnosticLocatorPaths,
        List<String> selectedSourcePathsForGate,
        int productEvidenceCount,
        List<String> unexpectedSourcePaths,
        int unexpectedSourceCount,
        boolean expectedSourceTopNHit,
        Map<String, List<String>> winnerPathsByGroup,
        int maxProductEvidenceCount,
        int maxUnexpectedSourceCount,
        List<String> forbiddenSourcePaths,
        boolean sourceHit,
        boolean forbiddenSourceLeak,
        boolean docsLeak,
        boolean generatedDocLeak,
        boolean legacyFallbackUsed,
        boolean evidenceChainMatched,
        List<String> missingEvidenceGroups,
        String failureCategory,
        boolean passed,
        List<String> diagnostics,
        List<String> expectedEvidenceGroups,
        List<String> selectedEvidenceGroups,
        List<String> actualRequiredEvidenceTypes,
        List<String> actualPlanningReasons
) {
    public ContextBenchmarkResult {
        expectedSourcePaths = copy(expectedSourcePaths);
        productSelectedSourcePaths = copy(productSelectedSourcePaths);
        diagnosticLocatorPaths = copy(diagnosticLocatorPaths);
        selectedSourcePathsForGate = copy(selectedSourcePathsForGate);
        unexpectedSourcePaths = copy(unexpectedSourcePaths);
        winnerPathsByGroup = copyMap(winnerPathsByGroup);
        forbiddenSourcePaths = copy(forbiddenSourcePaths);
        missingEvidenceGroups = copy(missingEvidenceGroups);
        diagnostics = copy(diagnostics);
        expectedEvidenceGroups = copy(expectedEvidenceGroups);
        selectedEvidenceGroups = copy(selectedEvidenceGroups);
        actualRequiredEvidenceTypes = copy(actualRequiredEvidenceTypes);
        actualPlanningReasons = copy(actualPlanningReasons);
    }

    boolean toleratedUnavailable() {
        return !passed && "EXTERNAL_ASSET_UNAVAILABLE".equals(failureCategory);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, List<String>> copyMap(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
        values.forEach((key, paths) -> copied.put(key, copy(paths)));
        return Collections.unmodifiableMap(copied);
    }
}
