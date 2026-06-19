package com.devcontext.benchmark.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ContextBenchmarkExpected(
        String intent,
        List<String> forbiddenIntents,
        List<String> evidenceGroups,
        List<String> sourcePaths,
        List<String> sourcePathPatterns,
        List<String> allowedExtraSourcePaths,
        List<String> allowedExtraSourcePathPatterns,
        List<String> forbiddenSourcePaths,
        List<String> forbiddenSourcePathPatterns,
        List<String> targetTerms,
        List<String> requiredEvidenceTypes,
        Map<String, String> evidenceChain,
        List<String> missingEvidenceGroups,
        Integer maxProductEvidenceCount,
        Integer maxUnexpectedSourceCount,
        String expectedFailureCategory
) {
    public ContextBenchmarkExpected {
        intent = text(intent);
        forbiddenIntents = list(forbiddenIntents);
        evidenceGroups = list(evidenceGroups);
        sourcePaths = list(sourcePaths);
        sourcePathPatterns = list(sourcePathPatterns);
        allowedExtraSourcePaths = list(allowedExtraSourcePaths);
        allowedExtraSourcePathPatterns = list(allowedExtraSourcePathPatterns);
        forbiddenSourcePaths = list(forbiddenSourcePaths);
        forbiddenSourcePathPatterns = list(forbiddenSourcePathPatterns);
        targetTerms = list(targetTerms);
        requiredEvidenceTypes = list(requiredEvidenceTypes);
        evidenceChain = evidenceChain == null ? Map.of() : Map.copyOf(evidenceChain);
        missingEvidenceGroups = list(missingEvidenceGroups);
        maxProductEvidenceCount = positiveThreshold(maxProductEvidenceCount);
        maxUnexpectedSourceCount = nonNegativeThreshold(maxUnexpectedSourceCount);
        expectedFailureCategory = text(expectedFailureCategory);
    }

    public boolean expectsMissingEvidence() {
        return !missingEvidenceGroups.isEmpty();
    }

    private static List<String> list(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static Integer positiveThreshold(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static Integer nonNegativeThreshold(Integer value) {
        return value == null || value < 0 ? null : value;
    }
}
