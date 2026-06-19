package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextBenchmarkReportReliabilityTests {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void perCaseJsonSummaryRecomputationAndStatsOnlyAreReliable() throws Exception {
        ContextBenchmarkRunner.RunOutcome initial = new ContextBenchmarkRunner()
                .run(new ContextBenchmarkRunConfig(
                        "query-understanding",
                        3,
                        "",
                        "context-benchmark-stats-test",
                        null,
                        false,
                        false,
                        20260618L
                ));
        assertThat(initial.qualityFailures()).isEmpty();

        Path runDir = initial.runDir();
        List<ContextBenchmarkResult> caseResults = readCaseResults(runDir);
        assertThat(caseResults).hasSize(3);
        JsonNode originalSummary = readSummary(runDir);
        assertSummaryMatchesRecomputed(caseResults, originalSummary);

        Map<Path, FileTime> caseTimestampsBefore = caseFileTimestamps(runDir);
        ContextBenchmarkRunner.RunOutcome statsOnly = new ContextBenchmarkRunner()
                .run(new ContextBenchmarkRunConfig(
                        "query-understanding",
                        0,
                        "",
                        "ignored-stats-run-name",
                        runDir,
                        true,
                        false,
                        20260618L
                ));

        assertThat(statsOnly.results()).hasSize(3);
        assertThat(statsOnly.qualityFailures()).isEmpty();
        assertThat(caseFileTimestamps(runDir)).isEqualTo(caseTimestampsBefore);
        assertSummaryMatchesRecomputed(readCaseResults(runDir), readSummary(runDir));
    }

    @Test
    void statsOnlyRequiresRunDir() {
        assertThatThrownBy(() -> new ContextBenchmarkRunner().run(new ContextBenchmarkRunConfig(
                "query-understanding",
                0,
                "",
                "stats-missing-run-dir",
                null,
                true,
                false,
                20260618L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StatsOnly requires -RunDir");
    }

    @Test
    void statsOnlyRejectsMissingRunDirUnderBenchmarkBase() {
        Path missingRunDir = Path.of("target", "context-benchmark-runs", "missing-stats-run")
                .toAbsolutePath()
                .normalize();
        assertThatThrownBy(() -> new ContextBenchmarkRunner().run(new ContextBenchmarkRunConfig(
                "query-understanding",
                0,
                "",
                "stats-missing-dir",
                missingRunDir,
                true,
                false,
                20260618L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("StatsOnly runDir does not exist");
    }

    @Test
    void statsOnlyRejectsRunDirOutsideBenchmarkBase() {
        Path outsideRunDir = Path.of("target").toAbsolutePath().normalize();
        assertThatThrownBy(() -> new ContextBenchmarkRunner().run(new ContextBenchmarkRunConfig(
                "query-understanding",
                0,
                "",
                "stats-bad-dir",
                outsideRunDir,
                true,
                false,
                20260618L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Context benchmark runDir must stay under");
    }

    @Test
    void statsOnlyRejectsRunDirWithoutCaseResults() throws Exception {
        Path emptyRunDir = Path.of("target", "context-benchmark-runs", "empty-stats-run")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(emptyRunDir);

        assertThatThrownBy(() -> new ContextBenchmarkRunner().run(new ContextBenchmarkRunConfig(
                "query-understanding",
                0,
                "",
                "stats-empty-dir",
                emptyRunDir,
                true,
                false,
                20260618L
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No context benchmark cases directory found");
    }

    private List<ContextBenchmarkResult> readCaseResults(Path runDir) throws Exception {
        try (var stream = Files.list(runDir.resolve("cases"))) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::readCaseResult)
                    .sorted(java.util.Comparator.comparing(ContextBenchmarkResult::generatedAt)
                            .thenComparing(ContextBenchmarkResult::caseId))
                    .toList();
        }
    }

    private ContextBenchmarkResult readCaseResult(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), ContextBenchmarkResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse case result: " + path, e);
        }
    }

    private JsonNode readSummary(Path runDir) throws Exception {
        return objectMapper.readTree(Files.readString(runDir.resolve(ContextBenchmarkResultWriter.SUMMARY_JSON)));
    }

    private void assertSummaryMatchesRecomputed(
            List<ContextBenchmarkResult> caseResults,
            JsonNode summary
    ) {
        JsonNode recomputed = objectMapper.valueToTree(new ContextBenchmarkResultWriter(objectMapper).summary(caseResults));
        for (String field : List.of(
                "caseCount",
                "totalCases",
                "passedCases",
                "failedCases",
                "unavailableCases",
                "realExternalCaseCount",
                "sourceHitRate",
                "unexpectedSourceTotalCount",
                "maxObservedProductEvidenceCount"
        )) {
            assertThat(recomputed.path(field).asText()).as(field).isEqualTo(summary.path(field).asText());
        }
        for (String field : List.of("failureCategories", "caseIds")) {
            assertThat(recomputed.path(field)).as(field).isEqualTo(summary.path(field));
        }
    }

    private Map<Path, FileTime> caseFileTimestamps(Path runDir) throws Exception {
        LinkedHashMap<Path, FileTime> timestamps = new LinkedHashMap<>();
        try (var stream = Files.list(runDir.resolve("cases"))) {
            for (Path path : stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                timestamps.put(path, Files.getLastModifiedTime(path));
            }
        }
        return timestamps;
    }
}
