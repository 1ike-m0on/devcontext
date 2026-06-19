package com.devcontext.benchmark.context;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ContextBenchmarkActual(
        String actualIntent,
        List<String> actualRequiredEvidenceTypes,
        List<String> actualPlanningReasons,
        List<String> actualTargetTerms,
        List<String> productSelectedSourcePaths,
        List<String> diagnosticLocatorPaths,
        List<String> selectedSourcePathsForGate,
        Map<String, List<String>> winnerPathsByGroup,
        List<String> selectedEvidenceGroups,
        List<String> forbiddenSourceHits,
        boolean docsLeak,
        boolean generatedDocLeak,
        boolean legacyFallbackUsed,
        boolean evidenceChainMatched,
        List<String> missingEvidenceGroups,
        List<String> diagnostics
) {
    public ContextBenchmarkActual {
        actualIntent = actualIntent == null ? "" : actualIntent;
        actualRequiredEvidenceTypes = copy(actualRequiredEvidenceTypes);
        actualPlanningReasons = copy(actualPlanningReasons);
        actualTargetTerms = copy(actualTargetTerms);
        productSelectedSourcePaths = copy(productSelectedSourcePaths);
        diagnosticLocatorPaths = copy(diagnosticLocatorPaths);
        selectedSourcePathsForGate = copy(selectedSourcePathsForGate);
        winnerPathsByGroup = copyMap(winnerPathsByGroup);
        selectedEvidenceGroups = copy(selectedEvidenceGroups);
        forbiddenSourceHits = copy(forbiddenSourceHits);
        missingEvidenceGroups = copy(missingEvidenceGroups);
        diagnostics = copy(diagnostics);
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
