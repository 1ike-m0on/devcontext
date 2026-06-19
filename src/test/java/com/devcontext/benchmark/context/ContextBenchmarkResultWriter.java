package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ContextBenchmarkResultWriter {

    static final String SUMMARY_JSON = "context-benchmark-summary.json";
    static final String SUMMARY_MD = "context-benchmark-summary.md";

    private final ObjectMapper objectMapper;
    private final ContextBenchmarkReportNormalizer normalizer = new ContextBenchmarkReportNormalizer();

    public ContextBenchmarkResultWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void writeCase(Path runDir, ContextBenchmarkResult result) {
        try {
            Path caseFile = runDir.resolve("cases").resolve(normalizer.caseFileName(result.caseId()));
            Files.createDirectories(caseFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(caseFile.toFile(), result);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write context benchmark case result: " + result.caseId(), e);
        }
    }

    public void writeSummary(Path runDir, List<ContextBenchmarkResult> results) {
        Map<String, Object> summary = summary(results);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve(SUMMARY_JSON).toFile(), summary);
            Files.writeString(runDir.resolve(SUMMARY_MD), markdown(summary, results));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write context benchmark summary: " + runDir, e);
        }
    }

    public Map<String, Object> summary(List<ContextBenchmarkResult> results) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        ContextBenchmarkResult first = results.isEmpty() ? null : results.getFirst();
        int completed = results.size();
        long passed = results.stream().filter(ContextBenchmarkResult::passed).count();
        long unavailable = results.stream().filter(ContextBenchmarkResult::toleratedUnavailable).count();
        long failed = results.stream().filter(result -> !result.passed() && !result.toleratedUnavailable()).count();
        summary.put("runId", first == null ? "" : first.runId());
        summary.put("generatedAt", first == null ? "" : first.generatedAt());
        summary.put("gitBranch", first == null ? "" : first.gitBranch());
        summary.put("gitCommit", first == null ? "" : first.gitCommit());
        summary.put("gitDirty", first != null && first.gitDirty());
        summary.put("suite", first == null ? "" : first.suite());
        summary.put("caseCount", completed);
        summary.put("totalCases", completed);
        summary.put("completedCases", completed);
        summary.put("passedCases", passed);
        summary.put("failedCases", failed);
        summary.put("unavailableCases", unavailable);
        summary.put("realExternalCaseCount", realExternalCaseCount(results));
        summary.put("passRate", completed == 0 ? 0.0d : round(100.0d * passed / completed));
        summary.put("intentAccuracy", rate(results, result -> result.expectedIntent().isBlank() || result.expectedIntent().equals(result.actualIntent())));
        summary.put("sourceHitRate", rate(results, ContextBenchmarkResult::sourceHit));
        summary.put("evidenceChainMatchedRate", rate(results, ContextBenchmarkResult::evidenceChainMatched));
        summary.put("precisionGateCaseCount", results.stream()
                .filter(result -> result.maxProductEvidenceCount() > 0 || result.maxUnexpectedSourceCount() >= 0)
                .count());
        summary.put("averageProductEvidenceCount", average(results, ContextBenchmarkResult::productEvidenceCount));
        summary.put("maxObservedProductEvidenceCount", max(results, ContextBenchmarkResult::productEvidenceCount));
        summary.put("unexpectedSourceTotalCount", results.stream().mapToInt(ContextBenchmarkResult::unexpectedSourceCount).sum());
        summary.put("unexpectedSourceCaseCount", results.stream().filter(result -> result.unexpectedSourceCount() > 0).count());
        summary.put("expectedSourceTopNHitRate", conditionalRate(
                results,
                result -> result.maxProductEvidenceCount() > 0,
                ContextBenchmarkResult::expectedSourceTopNHit
        ));
        summary.put("legacyFallbackCount", results.stream().filter(ContextBenchmarkResult::legacyFallbackUsed).count());
        summary.put("docsLeakCount", results.stream().filter(ContextBenchmarkResult::docsLeak).count());
        summary.put("generatedDocLeakCount", results.stream().filter(ContextBenchmarkResult::generatedDocLeak).count());
        summary.put("forbiddenSourceLeakCount", results.stream().filter(ContextBenchmarkResult::forbiddenSourceLeak).count());
        summary.put("failureCategories", results.stream()
                .filter(result -> result.failureCategory() != null && !result.failureCategory().isBlank())
                .collect(Collectors.groupingBy(ContextBenchmarkResult::failureCategory, LinkedHashMap::new, Collectors.counting())));
        summary.put("caseIds", results.stream().map(ContextBenchmarkResult::caseId).toList());
        return summary;
    }

    private String markdown(Map<String, Object> summary, List<ContextBenchmarkResult> results) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Context Benchmark Summary\n\n");
        for (String key : List.of("runId", "generatedAt", "gitBranch", "gitCommit", "gitDirty", "suite",
                "caseCount", "totalCases", "passedCases", "failedCases", "unavailableCases",
                "realExternalCaseCount", "precisionGateCaseCount", "passRate")) {
            builder.append("- ").append(key).append(": `").append(summary.get(key)).append("`\n");
        }
        builder.append("\n## Suites\n\n");
        Map<String, Long> bySuite = results.stream()
                .collect(Collectors.groupingBy(ContextBenchmarkResult::suite, LinkedHashMap::new, Collectors.counting()));
        bySuite.forEach((suite, count) -> builder.append("- ").append(suite).append(": `").append(count).append("`\n"));
        builder.append("\n## Failures\n\n");
        List<ContextBenchmarkResult> failures = results.stream()
                .filter(result -> !result.passed())
                .toList();
        if (failures.isEmpty()) {
            builder.append("- none\n");
        } else {
            for (ContextBenchmarkResult failure : failures) {
                builder.append("- `").append(failure.caseId()).append("` ")
                        .append(failure.failureCategory()).append(" ")
                        .append(failure.diagnostics()).append("\n");
            }
        }
        return builder.toString();
    }

    private double rate(List<ContextBenchmarkResult> results, java.util.function.Predicate<ContextBenchmarkResult> predicate) {
        if (results.isEmpty()) {
            return 0.0d;
        }
        long matched = results.stream().filter(predicate).count();
        return round(100.0d * matched / results.size());
    }

    private double conditionalRate(
            List<ContextBenchmarkResult> results,
            java.util.function.Predicate<ContextBenchmarkResult> denominator,
            java.util.function.Predicate<ContextBenchmarkResult> predicate
    ) {
        List<ContextBenchmarkResult> applicable = results.stream().filter(denominator).toList();
        if (applicable.isEmpty()) {
            return 0.0d;
        }
        long matched = applicable.stream().filter(predicate).count();
        return round(100.0d * matched / applicable.size());
    }

    private double average(
            List<ContextBenchmarkResult> results,
            java.util.function.ToIntFunction<ContextBenchmarkResult> value
    ) {
        if (results.isEmpty()) {
            return 0.0d;
        }
        return round(results.stream().mapToInt(value).average().orElse(0.0d));
    }

    private int max(
            List<ContextBenchmarkResult> results,
            java.util.function.ToIntFunction<ContextBenchmarkResult> value
    ) {
        return results.stream().mapToInt(value).max().orElse(0);
    }

    private long realExternalCaseCount(List<ContextBenchmarkResult> results) {
        return results.stream()
                .filter(result -> result.suite().startsWith("external-"))
                .filter(result -> !result.toleratedUnavailable())
                .filter(result -> !externalSentinelResult(result))
                .count();
    }

    private boolean externalSentinelResult(ContextBenchmarkResult result) {
        return result.caseId().equals(result.suite() + "-unavailable");
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
