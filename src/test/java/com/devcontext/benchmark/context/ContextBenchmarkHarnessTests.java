package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBenchmarkHarnessTests {

    @Test
    void harnessLoadsCaseCatalogAndWritesParseableReports() throws Exception {
        ContextBenchmarkCaseLoader.LoadedCases loadedCases = new ContextBenchmarkCaseLoader(new ObjectMapper())
                .load(new ContextBenchmarkRunConfig("all", 0, "", "catalog-check", null, false, false, 20260618L));
        assertThat(loadedCases.cases().stream()
                .filter(benchmarkCase -> !"external-aider-polyglot".equals(benchmarkCase.suite()))
                .count()).isGreaterThanOrEqualTo(118L);

        ContextBenchmarkRunner.RunOutcome outcome = new ContextBenchmarkRunner()
                .run(new ContextBenchmarkRunConfig(
                        "query-understanding",
                        3,
                        "",
                        "context-benchmark-harness-test",
                        null,
                        false,
                        false,
                        20260618L
                ));

        assertThat(outcome.qualityFailures()).isEmpty();
        Path summaryJson = outcome.runDir().resolve(ContextBenchmarkResultWriter.SUMMARY_JSON);
        assertThat(summaryJson).exists();
        JsonNode summary = new ObjectMapper().readTree(Files.readString(summaryJson));
        assertThat(summary.path("caseCount").asInt()).isEqualTo(3);
        assertThat(summary.path("totalCases").asInt()).isEqualTo(3);
        assertThat(summary.path("failedCases").asInt()).isZero();
        assertThat(summary.path("realExternalCaseCount").asInt()).isZero();
        assertThat(Files.list(outcome.runDir().resolve("cases")).count()).isEqualTo(3L);
        assertSummaryCanBeRecomputedFromCaseJson(outcome.runDir(), summary);

        ContextBenchmarkResult productResult = new ContextBenchmarkRunner()
                .run(new ContextBenchmarkRunConfig(
                        "evidence-pack",
                        1,
                        "",
                        "context-benchmark-product-gate-test",
                        null,
                        false,
                        false,
                        20260618L
                ))
                .results()
                .getFirst();
        assertThat(productResult.selectedSourcePathsForGate()).isEqualTo(productResult.productSelectedSourcePaths());
        assertThat(productResult.diagnosticLocatorPaths()).isNotEmpty();
        assertThat(productResult.productEvidenceCount()).isEqualTo(productResult.productSelectedSourcePaths().size());
        assertThat(productResult.maxProductEvidenceCount()).isEqualTo(8);
        assertThat(productResult.maxUnexpectedSourceCount()).isEqualTo(4);
        assertThat(productResult.unexpectedSourceCount()).isEqualTo(productResult.unexpectedSourcePaths().size());
        assertThat(productResult.winnerPathsByGroup()).isNotEmpty();
    }

    @Test
    void pendingProductContextCasesAreExcludedFromDefaultEvidencePackGate() {
        ContextBenchmarkCaseLoader loader = new ContextBenchmarkCaseLoader(new ObjectMapper());

        List<String> activeCaseIds = loader.load(ContextBenchmarkRunConfig.forSuite("evidence-pack"))
                .cases()
                .stream()
                .map(ContextBenchmarkCase::caseId)
                .toList();
        List<String> pendingCaseIds = loader.load(ContextBenchmarkRunConfig.forSuite("evidence-pack-pending"))
                .cases()
                .stream()
                .map(ContextBenchmarkCase::caseId)
                .toList();

        assertThat(activeCaseIds)
                .doesNotContain("evidence-coverage-api-source-pack", "source-evidence-loop-source-pack");
        assertThat(pendingCaseIds)
                .contains("evidence-coverage-api-source-pack", "source-evidence-loop-source-pack");
    }

    private void assertSummaryCanBeRecomputedFromCaseJson(Path runDir, JsonNode summary) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<ContextBenchmarkResult> caseResults;
        try (var stream = Files.list(runDir.resolve("cases"))) {
            caseResults = stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(path -> readCaseResult(objectMapper, path))
                    .toList();
        }
        JsonNode recomputed = objectMapper.valueToTree(new ContextBenchmarkResultWriter(objectMapper).summary(caseResults));
        for (String field : List.of(
                "caseCount",
                "totalCases",
                "passedCases",
                "failedCases",
                "unavailableCases",
                "realExternalCaseCount",
                "sourceHitRate"
        )) {
            assertThat(recomputed.path(field).asText()).as(field).isEqualTo(summary.path(field).asText());
        }
        for (String field : List.of(
                "failureCategories",
                "caseIds"
        )) {
            assertThat(recomputed.path(field)).as(field).isEqualTo(summary.path(field));
        }
    }

    private ContextBenchmarkResult readCaseResult(ObjectMapper objectMapper, Path path) {
        try {
            return objectMapper.readValue(path.toFile(), ContextBenchmarkResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read context benchmark case result: " + path, e);
        }
    }
}
