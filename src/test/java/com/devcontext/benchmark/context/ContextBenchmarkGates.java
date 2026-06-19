package com.devcontext.benchmark.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ContextBenchmarkGates {

    private final ContextBenchmarkReportNormalizer normalizer = new ContextBenchmarkReportNormalizer();

    public GateDecision evaluate(ContextBenchmarkCase benchmarkCase, ContextBenchmarkActual actual) {
        ContextBenchmarkExpected expected = benchmarkCase.expected();
        List<String> diagnostics = new ArrayList<>(actual.diagnostics());
        boolean intentMatched = expected.intent().isBlank() || expected.intent().equals(actual.actualIntent());
        boolean forbiddenIntent = expected.forbiddenIntents().stream().anyMatch(actual.actualIntent()::equals);
        if (!intentMatched) {
            diagnostics.add("expected intent " + expected.intent() + " but got " + actual.actualIntent());
        }
        if (forbiddenIntent) {
            diagnostics.add("actual intent matched forbidden intent " + actual.actualIntent());
        }

        boolean targetTermsMatched = containsAllTerms(actual.actualTargetTerms(), expected.targetTerms());
        if (!targetTermsMatched) {
            diagnostics.add("missing expected target terms " + expected.targetTerms());
        }

        boolean requiredEvidenceMatched = containsAllTerms(actual.actualRequiredEvidenceTypes(), expected.requiredEvidenceTypes());
        if (!requiredEvidenceMatched) {
            diagnostics.add("missing expected required evidence types " + expected.requiredEvidenceTypes());
        }

        List<String> expectedSourcePaths = expectedSourcePaths(expected);
        boolean sourceHit = expectedSourcePaths.isEmpty()
                || expectedSourcePaths.stream().allMatch(pattern -> anySelectedMatches(pattern, actual.selectedSourcePathsForGate()));
        if (!sourceHit) {
            diagnostics.add("selected sources did not cover expected paths " + expectedSourcePaths);
        }

        List<String> forbiddenSourcePaths = forbiddenSourcePaths(expected);
        List<String> forbiddenHits = new ArrayList<>(actual.forbiddenSourceHits());
        for (String forbidden : forbiddenSourcePaths) {
            for (String selected : actual.selectedSourcePathsForGate()) {
                if (pathMatches(forbidden, selected)) {
                    forbiddenHits.add(selected);
                }
            }
        }
        forbiddenHits = forbiddenHits.stream().distinct().toList();
        boolean forbiddenLeak = !forbiddenHits.isEmpty();
        if (forbiddenLeak) {
            diagnostics.add("forbidden source paths selected " + forbiddenHits);
        }

        boolean expectedGroupsMatched = expected.evidenceGroups().isEmpty()
                || actual.selectedEvidenceGroups().containsAll(expected.evidenceGroups())
                || expected.expectsMissingEvidence();
        if (!expectedGroupsMatched) {
            diagnostics.add("missing expected evidence groups " + expected.evidenceGroups());
        }

        boolean evidenceChainMatched = expected.evidenceChain().isEmpty()
                || evidenceChainMatched(expected.evidenceChain(), actual.selectedSourcePathsForGate());
        if (!evidenceChainMatched) {
            diagnostics.add("evidence chain did not match " + expected.evidenceChain());
        }

        boolean expectedMissingMatched = !expected.expectsMissingEvidence()
                || actual.missingEvidenceGroups().containsAll(expected.missingEvidenceGroups());
        if (!expectedMissingMatched) {
            diagnostics.add("expected missing evidence groups were not reported " + expected.missingEvidenceGroups());
        }

        int productEvidenceCount = actual.productSelectedSourcePaths().size();
        List<String> unexpectedSourcePaths = unexpectedSourcePaths(
                benchmarkCase,
                expectedSourcePaths,
                allowedExtraSourcePaths(expected),
                actual.productSelectedSourcePaths()
        );
        int unexpectedSourceCount = unexpectedSourcePaths.size();
        int maxProductEvidenceCount = expected.maxProductEvidenceCount() == null ? 0 : expected.maxProductEvidenceCount();
        int maxUnexpectedSourceCount = expected.maxUnexpectedSourceCount() == null ? -1 : expected.maxUnexpectedSourceCount();
        boolean precisionApplies = productPrecisionApplies(benchmarkCase, expectedSourcePaths);
        List<String> topNProductPaths = firstN(actual.productSelectedSourcePaths(), maxProductEvidenceCount);
        boolean expectedSourceTopNHit = !precisionApplies || maxProductEvidenceCount <= 0
                || expectedSourcePaths.stream().allMatch(pattern -> anySelectedMatches(pattern, topNProductPaths));
        boolean productEvidenceTooLarge = precisionApplies
                && maxProductEvidenceCount > 0
                && productEvidenceCount > maxProductEvidenceCount;
        boolean unexpectedSourceTooLarge = precisionApplies
                && maxUnexpectedSourceCount >= 0
                && unexpectedSourceCount > maxUnexpectedSourceCount;
        if (precisionApplies && maxProductEvidenceCount > 0 && !expectedSourceTopNHit) {
            diagnostics.add("expected source paths did not all appear in top "
                    + maxProductEvidenceCount + " product evidence paths");
        }
        if (productEvidenceTooLarge) {
            diagnostics.add("product evidence count " + productEvidenceCount
                    + " exceeded max " + maxProductEvidenceCount);
        }
        if (unexpectedSourceTooLarge) {
            diagnostics.add("unexpected product evidence paths " + unexpectedSourcePaths
                    + " exceeded max " + maxUnexpectedSourceCount);
        }

        String failureCategory = "";
        if (!intentMatched || forbiddenIntent || !targetTermsMatched) {
            failureCategory = "QUERY_UNDERSTANDING_FAILED";
        } else if (!requiredEvidenceMatched || !expectedGroupsMatched) {
            failureCategory = "EVIDENCE_PLAN_FAILED";
        } else if (actual.legacyFallbackUsed()) {
            failureCategory = "LEGACY_FALLBACK_USED";
        } else if (forbiddenLeak) {
            failureCategory = "FORBIDDEN_SOURCE_LEAKED";
        } else if (actual.docsLeak() || actual.generatedDocLeak()) {
            failureCategory = "PROMPT_EVIDENCE_LEAKED";
        } else if (!sourceHit && !expected.expectsMissingEvidence()) {
            failureCategory = "SOURCE_WINNER_FAILED";
        } else if (!evidenceChainMatched) {
            failureCategory = "EVIDENCE_CHAIN_FAILED";
        } else if (!expectedMissingMatched) {
            failureCategory = "ANSWER_MODE_FAILED";
        } else if (!expectedSourceTopNHit) {
            failureCategory = "EXPECTED_SOURCE_NOT_TOP_N";
        } else if (productEvidenceTooLarge) {
            failureCategory = "PRODUCT_EVIDENCE_TOO_LARGE";
        } else if (unexpectedSourceTooLarge) {
            failureCategory = "UNEXPECTED_SOURCE_NOISE";
        }

        boolean passed = failureCategory.isBlank();
        if (expected.expectsMissingEvidence() && expectedMissingMatched && sourceHitForNegativeCase(expected, sourceHit)) {
            failureCategory = "";
            passed = true;
        }
        return new GateDecision(
                passed,
                failureCategory,
                sourceHit,
                forbiddenLeak,
                actual.docsLeak(),
                actual.generatedDocLeak(),
                actual.legacyFallbackUsed(),
                evidenceChainMatched,
                productEvidenceCount,
                unexpectedSourcePaths,
                unexpectedSourceCount,
                expectedSourceTopNHit,
                actual.winnerPathsByGroup(),
                maxProductEvidenceCount,
                maxUnexpectedSourceCount,
                actual.missingEvidenceGroups(),
                forbiddenHits,
                diagnostics
        );
    }

    private boolean sourceHitForNegativeCase(ContextBenchmarkExpected expected, boolean sourceHit) {
        return expected.sourcePaths().isEmpty() && expected.sourcePathPatterns().isEmpty() || sourceHit;
    }

    private boolean containsAllTerms(List<String> actualValues, List<String> expectedValues) {
        if (expectedValues.isEmpty()) {
            return true;
        }
        Set<String> actual = new LinkedHashSet<>();
        for (String value : actualValues) {
            actual.add(normalize(value));
        }
        for (String expected : expectedValues) {
            String normalized = normalize(expected);
            boolean matched = actual.stream().anyMatch(value -> value.equals(normalized) || value.contains(normalized));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean evidenceChainMatched(Map<String, String> evidenceChain, List<String> selectedSourcePaths) {
        for (Map.Entry<String, String> entry : evidenceChain.entrySet()) {
            if ("name".equals(entry.getKey())) {
                continue;
            }
            if (!anySelectedMatches(entry.getValue(), selectedSourcePaths)) {
                return false;
            }
        }
        return true;
    }

    private boolean productPrecisionApplies(ContextBenchmarkCase benchmarkCase, List<String> expectedSourcePaths) {
        ContextBenchmarkExpected expected = benchmarkCase.expected();
        return productSuite(benchmarkCase)
                && !expected.expectsMissingEvidence()
                && !expectedSourcePaths.isEmpty()
                && (expected.maxProductEvidenceCount() != null || expected.maxUnexpectedSourceCount() != null);
    }

    private boolean productSuite(ContextBenchmarkCase benchmarkCase) {
        String suite = benchmarkCase.suite();
        return !"cross-language".equals(suite) && !suite.startsWith("external-");
    }

    private List<String> unexpectedSourcePaths(
            ContextBenchmarkCase benchmarkCase,
            List<String> expectedSourcePaths,
            List<String> allowedExtraSourcePaths,
            List<String> productSelectedSourcePaths
    ) {
        if (!productSuite(benchmarkCase) || benchmarkCase.expected().expectsMissingEvidence() || expectedSourcePaths.isEmpty()) {
            return List.of();
        }
        return productSelectedSourcePaths.stream()
                .filter(selected -> expectedSourcePaths.stream().noneMatch(pattern -> pathMatches(pattern, selected)))
                .filter(selected -> allowedExtraSourcePaths.stream().noneMatch(pattern -> pathMatches(pattern, selected)))
                .distinct()
                .toList();
    }

    private List<String> firstN(List<String> paths, int count) {
        if (count <= 0 || paths.size() <= count) {
            return paths;
        }
        return paths.subList(0, count);
    }

    private boolean anySelectedMatches(String pattern, List<String> selectedSourcePaths) {
        for (String selected : selectedSourcePaths) {
            if (pathMatches(pattern, selected)) {
                return true;
            }
        }
        return false;
    }

    private boolean pathMatches(String pattern, String selected) {
        String normalizedPattern = normalizer.lowerPath(pattern);
        String normalizedSelected = normalizer.lowerPath(selected);
        if (normalizedPattern.equals(normalizedSelected)) {
            return true;
        }
        if (normalizedPattern.contains("*") || normalizedPattern.contains("?")) {
            return normalizer.matchesGlob(normalizedPattern, normalizedSelected);
        }
        return normalizedSelected.endsWith(normalizedPattern);
    }

    private List<String> expectedSourcePaths(ContextBenchmarkExpected expected) {
        List<String> result = new ArrayList<>(expected.sourcePaths());
        result.addAll(expected.sourcePathPatterns());
        expected.evidenceChain().forEach((key, value) -> {
            if (!"name".equals(key)) {
                result.add(value);
            }
        });
        return result.stream().distinct().toList();
    }

    private List<String> forbiddenSourcePaths(ContextBenchmarkExpected expected) {
        List<String> result = new ArrayList<>(expected.forbiddenSourcePaths());
        result.addAll(expected.forbiddenSourcePathPatterns());
        return result.stream().distinct().toList();
    }

    private List<String> allowedExtraSourcePaths(ContextBenchmarkExpected expected) {
        List<String> result = new ArrayList<>(expected.allowedExtraSourcePaths());
        result.addAll(expected.allowedExtraSourcePathPatterns());
        return result.stream().distinct().toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\\', '/').trim();
    }

    public record GateDecision(
            boolean passed,
            String failureCategory,
            boolean sourceHit,
            boolean forbiddenSourceLeak,
            boolean docsLeak,
            boolean generatedDocLeak,
            boolean legacyFallbackUsed,
            boolean evidenceChainMatched,
            int productEvidenceCount,
            List<String> unexpectedSourcePaths,
            int unexpectedSourceCount,
            boolean expectedSourceTopNHit,
            Map<String, List<String>> winnerPathsByGroup,
            int maxProductEvidenceCount,
            int maxUnexpectedSourceCount,
            List<String> missingEvidenceGroups,
            List<String> forbiddenSourceHits,
            List<String> diagnostics
    ) {
    }
}
