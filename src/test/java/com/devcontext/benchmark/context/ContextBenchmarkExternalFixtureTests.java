package com.devcontext.benchmark.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBenchmarkExternalFixtureTests {

    @Test
    void adapterReturnsCasesOrExplicitUnavailable() {
        ExternalBenchmarkLoadResult result = new AiderPolyglotBenchmarkAdapter(new ObjectMapper())
                .load(ContextBenchmarkRunConfig.forSuite("external-aider-polyglot"));
        if (result.unavailable()) {
            assertThat(result.failureCategory()).isEqualTo("EXTERNAL_ASSET_UNAVAILABLE");
            assertThat(result.unavailableReason()).contains("Aider polyglot");
            assertThat(result.attemptedPaths()).isNotEmpty();
        } else {
            assertThat(result.cases()).isNotEmpty();
            assertThat(result.cases()).allSatisfy(benchmarkCase ->
                    assertThat(benchmarkCase.expected().sourcePaths()).isNotEmpty());
            assertThat(result.cases().stream()
                    .limit(6)
                    .map(ContextBenchmarkCase::language)
                    .distinct()
                    .count()).isEqualTo(6L);
        }
    }

    @Test
    void fakeFixtureReadsSolutionOracleForbiddenPathsAndSupportsStratifiedLimit() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        ExternalBenchmarkLoadResult result = fakeAdapter(objectMapper)
                .load(config("external-aider-polyglot", 0, "fake-external-adapter"));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.cases()).hasSize(4);
        assertThat(result.cases().stream().map(ContextBenchmarkCase::language).toList())
                .containsExactly("javascript", "rust", "javascript", "rust");
        assertThat(result.cases().stream().limit(2).map(ContextBenchmarkCase::language).toList())
                .containsExactly("javascript", "rust");

        ContextBenchmarkCase rustManifest = result.cases().stream()
                .filter(benchmarkCase -> benchmarkCase.caseId().contains("manifest-rust"))
                .findFirst()
                .orElseThrow();
        assertThat(rustManifest.expected().sourcePaths())
                .containsExactly("src/lib.rs", "Cargo.toml");
        assertThat(rustManifest.expected().forbiddenSourcePaths())
                .contains("tests/manifest_test.rs", "examples/demo.rs", "tests/**", "test/**", ".docs/**", ".meta/**");
        assertThat(rustManifest.question())
                .contains("Manifest Rust")
                .contains("Return source files only");
    }

    @Test
    void fakeFixtureRunnerUsesDiagnosticGatePathsWithoutProductEvidence() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                fakeAdapter(objectMapper)
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-aider-polyglot",
                2,
                "fake-external-fixture-calibration"
        ));

        assertThat(outcome.qualityFailures()).isEmpty();
        assertThat(outcome.results()).hasSize(2);
        assertThat(outcome.results().stream().map(ContextBenchmarkResult::language).toList())
                .containsExactly("javascript", "rust");
        assertThat(outcome.results()).allSatisfy(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.productSelectedSourcePaths()).isEmpty();
            assertThat(result.selectedSourcePathsForGate()).isEqualTo(result.diagnosticLocatorPaths());
            assertThat(result.selectedSourcePathsForGate()).isNotEmpty();
            assertThat(result.selectedSourcePathsForGate())
                    .noneMatch(path -> path.contains(".docs/")
                            || path.contains(".meta/")
                            || path.startsWith("test/")
                            || path.startsWith("tests/")
                            || path.contains("/test/")
                            || path.contains("/tests/")
                            || path.startsWith("example")
                            || path.startsWith("examples/")
                            || path.contains("/example")
                            || path.contains("/examples/"));
            assertThat(result.forbiddenSourceLeak()).isFalse();
        });
        ContextBenchmarkResult rust = outcome.results().stream()
                .filter(result -> "rust".equals(result.language()))
                .findFirst()
                .orElseThrow();
        assertThat(rust.expectedSourcePaths()).contains("src/lib.rs", "Cargo.toml");
        assertThat(rust.selectedSourcePathsForGate()).contains("src/lib.rs", "Cargo.toml");
    }

    @Test
    void genericLocatorDoesNotCopyMissingOraclePathsIntoSelectedPaths() throws Exception {
        Path root = fakeRoot().resolve("rust").resolve("exercises").resolve("practice").resolve("manifest-rust");
        List<String> selected = new GenericSourceLocator().locate(
                root,
                "Return source files only for the manifest rust crate.",
                8,
                List.of("src/lib.rs", "Cargo.toml", "src/missing.rs")
        );

        assertThat(selected).contains("src/lib.rs", "Cargo.toml");
        assertThat(selected).doesNotContain("src/missing.rs");
        assertThat(selected).allSatisfy(path -> assertThat(Files.isRegularFile(root.resolve(path))).isTrue());
    }

    @Test
    void genericLocatorAllowsExplicitExternalOracleUnderTestsDirectory() throws Exception {
        Path root = Path.of("target", "context-benchmark-runs", "external-test-oracle-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Path expected = root.resolve("pkg/tests/helpers.py");
        Path decoy = root.resolve("pkg/tests/noise_test.py");
        Files.createDirectories(expected.getParent());
        Files.writeString(expected, "def set_nan_tensor_to_zero(t):\n    return t\n");
        Files.writeString(decoy, "def set_nan_tensor_to_zero(t):\n    raise AssertionError()\n");

        List<String> selected = new GenericSourceLocator().locate(
                root,
                "Return source files only for set_nan_tensor_to_zero.",
                8,
                List.of("pkg/tests/helpers.py")
        );

        assertThat(selected).contains("pkg/tests/helpers.py");
        assertThat(selected).doesNotContain("pkg/tests/noise_test.py");
    }

    @Test
    void fakeRepoBenchFixtureReadsCrossFileOracleAndForbiddenPaths() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        ExternalBenchmarkLoadResult result = fakeRepoBenchAdapter(objectMapper)
                .load(config("external-repobench", 0, "fake-repobench-adapter"));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.cases()).hasSize(4);
        assertThat(result.cases().stream().map(ContextBenchmarkCase::language).toList())
                .containsExactly("python", "java", "python", "java");
        assertThat(result.cases().stream().filter(benchmarkCase -> "python".equals(benchmarkCase.language())).count())
                .isEqualTo(2);
        assertThat(result.cases().stream().filter(benchmarkCase -> "java".equals(benchmarkCase.language())).count())
                .isEqualTo(2);

        ContextBenchmarkCase python = result.cases().getFirst();
        assertThat(python.expected().sourcePaths())
                .contains("app/services/ticket_service.py", "app/models/ticket.py");
        assertThat(python.expected().sourcePaths()).isNotEmpty();
        assertThat(python.expected().forbiddenSourcePaths())
                .contains("docs/ticket-flow.md", "README.md", "metadata/case.json", "app/noise/email_sender.py");
        assertThat(python.question())
                .contains("RepoBench python cross-file context request")
                .contains("Target file: app/api/tickets.py")
                .contains("Return source files only");
    }

    @Test
    void fakeRepoBenchRunnerUsesDiagnosticGatePathsAndRejectsDecoys() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                fakeAdapter(objectMapper),
                fakeRepoBenchAdapter(objectMapper)
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-repobench",
                4,
                "external-repobench-fake-calibration"
        ));

        assertThat(outcome.qualityFailures()).isEmpty();
        assertThat(outcome.results()).hasSize(4);
        assertThat(outcome.results().stream().map(ContextBenchmarkResult::language).toList())
                .containsExactly("python", "java", "python", "java");
        assertThat(outcome.results()).allSatisfy(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.productSelectedSourcePaths()).isEmpty();
            assertThat(result.selectedSourcePathsForGate()).isEqualTo(result.diagnosticLocatorPaths());
            assertThat(result.selectedSourcePathsForGate()).isNotEmpty();
            assertThat(result.selectedSourcePathsForGate())
                    .noneMatch(path -> path.startsWith("docs/")
                            || path.startsWith(".docs/")
                            || path.startsWith("metadata/")
                            || path.equalsIgnoreCase("README.md")
                            || path.contains("/noise/"));
            assertThat(result.forbiddenSourceLeak()).isFalse();
            assertThat(result.sourceHit()).isTrue();
        });
    }

    @Test
    void repoBenchV11JsonlContextSchemaMaterializesSnippetProject() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Path root = Path.of("target", "context-benchmark-runs", "repobench-v11-schema-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        Files.writeString(root.resolve("cases.jsonl"), """
                {"language":"python","repo_name":"demo/repo","file_path":"pkg/api.py","cropped_code":"from pkg.service import create_ticket\\ncreate_ticket(payload)","context":[{"path":"pkg/service.py","snippet":"def create_ticket(payload):\\n    return payload"},{"path":"pkg/model.py","snippet":"class Ticket:\\n    pass"}],"level":"2k"}
                """);

        ExternalBenchmarkLoadResult result = new RepoBenchBenchmarkAdapter(objectMapper, List.of(root))
                .load(config("external-repobench", 1, "repobench-v11-schema"));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.cases()).hasSize(1);
        ContextBenchmarkCase benchmarkCase = result.cases().getFirst();
        assertThat(benchmarkCase.expected().sourcePaths()).containsExactly("pkg/service.py", "pkg/model.py");
        Path generatedRoot = Path.of(benchmarkCase.projectRoot());
        assertThat(generatedRoot).isDirectory();
        assertThat(Files.readString(generatedRoot.resolve("pkg/service.py"))).contains("create_ticket");
        assertThat(Files.readString(generatedRoot.resolve("pkg/api.py"))).contains("create_ticket(payload)");
    }

    @Test
    void repoBenchV11JsonlDoesNotApplyAdapterSideTenCaseCap() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Path root = Path.of("target", "context-benchmark-runs", "repobench-v11-limit-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        StringBuilder jsonl = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            jsonl.append(objectMapper.writeValueAsString(java.util.Map.of(
                    "case_id", "python-" + index,
                    "language", "python",
                    "file_path", "pkg/api_" + index + ".py",
                    "cropped_code", "from pkg.service_" + index + " import handle\nhandle(payload)",
                    "context", List.of(java.util.Map.of(
                            "path", "pkg/service_" + index + ".py",
                            "snippet", "def handle(payload):\n    return payload"
                    ))
            ))).append(System.lineSeparator());
        }
        for (int index = 0; index < 6; index++) {
            jsonl.append(objectMapper.writeValueAsString(java.util.Map.of(
                    "case_id", "java-" + index,
                    "language", "java",
                    "file_path", "src/main/java/demo/Api" + index + ".java",
                    "cropped_code", "new Service" + index + "().handle(payload);",
                    "context", List.of(java.util.Map.of(
                            "path", "src/main/java/demo/Service" + index + ".java",
                            "snippet", "class Service" + index + " { Object handle(Object payload) { return payload; } }"
                    ))
            ))).append(System.lineSeparator());
        }
        Files.writeString(root.resolve("cases.jsonl"), jsonl.toString());

        ExternalBenchmarkLoadResult result = new RepoBenchBenchmarkAdapter(objectMapper, List.of(root))
                .load(config("external-repobench", 80, "repobench-v11-limit"));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.cases()).hasSize(12);
        assertThat(result.cases().stream().map(ContextBenchmarkCase::language).limit(4).toList())
                .containsExactly("python", "java", "python", "java");
    }

    @Test
    void missingRepoBenchAssetIsRequiredFailureForExplicitSuite() {
        ObjectMapper objectMapper = objectMapper();
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                fakeAdapterUnchecked(objectMapper),
                new RepoBenchBenchmarkAdapter(objectMapper, List.of(missingExternalRoot()))
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-repobench",
                1,
                "missing-repobench-required"
        ));

        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.passed()).isFalse();
        assertThat(result.failureCategory()).isEqualTo("EXTERNAL_ASSET_UNAVAILABLE");
        assertThat(result.toleratedUnavailable()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic).contains("RepoBench root not found"));
        assertThat(outcome.qualityFailures()).containsExactly(result);
    }

    @Test
    void repoBenchOracleParsingFailureDoesNotCountAsRealExternalCase() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Path emptyRoot = Path.of("target", "context-benchmark-runs", "empty-repobench-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(emptyRoot);
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                fakeAdapterUnchecked(objectMapper),
                new RepoBenchBenchmarkAdapter(objectMapper, List.of(emptyRoot))
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-repobench",
                1,
                "repobench-oracle-parsing"
        ));

        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.failureCategory()).isEqualTo("ORACLE_PARSING_FAILED");
        var summary = objectMapper.readTree(outcome.runDir().resolve(ContextBenchmarkResultWriter.SUMMARY_JSON).toFile());
        assertThat(summary.path("failedCases").asInt()).isEqualTo(1);
        assertThat(summary.path("unavailableCases").asInt()).isZero();
        assertThat(summary.path("realExternalCaseCount").asInt()).isZero();
    }

    @Test
    void multiSweBenchAdapterReadsExplicitDatasetFileAndPatchOracles() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Path datasetFile = multiSweBenchDatasetFile(objectMapper);

        ExternalBenchmarkLoadResult result = new MultiSweBenchBenchmarkAdapter(objectMapper)
                .load(config("external-multi-swe-bench", 1, "multi-swe-adapter", datasetFile));

        assertThat(result.unavailable()).isFalse();
        assertThat(result.cases()).hasSize(1);
        ContextBenchmarkCase benchmarkCase = result.cases().getFirst();
        assertThat(benchmarkCase.caseId()).isEqualTo("multi-swe-bench-demo-org__demo-repo-42");
        assertThat(benchmarkCase.question())
                .contains("Issue title: Fix ticket status propagation")
                .contains("Resolved issue 41")
                .contains("Return source files only");
        assertThat(benchmarkCase.expected().sourcePaths()).containsExactly("src/app/service.py");
        assertThat(benchmarkCase.expected().forbiddenSourcePaths())
                .contains("tests/test_service.py", "src/app/testing.py", "docs/release.md");
        assertThat(benchmarkCase.expected().sourcePaths()).doesNotContain("docs/release.md");
        Path projectRoot = Path.of(benchmarkCase.projectRoot());
        assertThat(Files.readString(projectRoot.resolve("src/app/service.py"))).contains("mark_resolved");
        assertThat(Files.readString(projectRoot.resolve("tests/test_service.py"))).contains("test_mark_resolved");
    }

    @Test
    void multiSweBenchRunnerUsesDiagnosticPathsAndRejectsTests() throws Exception {
        ObjectMapper objectMapper = objectMapper();
        Path datasetFile = multiSweBenchDatasetFile(objectMapper);
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(objectMapper));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-multi-swe-bench",
                1,
                "multi-swe-runner",
                datasetFile
        ));

        assertThat(outcome.qualityFailures()).isEmpty();
        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.passed()).isTrue();
        assertThat(result.productSelectedSourcePaths()).isEmpty();
        assertThat(result.selectedSourcePathsForGate()).isEqualTo(result.diagnosticLocatorPaths());
        assertThat(result.selectedSourcePathsForGate()).contains("src/app/service.py");
        assertThat(result.selectedSourcePathsForGate()).noneMatch(path -> path.startsWith("tests/"));
        assertThat(result.selectedSourcePathsForGate()).doesNotContain("src/app/testing.py");
        assertThat(result.forbiddenSourceLeak()).isFalse();
        var summary = objectMapper.readTree(outcome.runDir().resolve(ContextBenchmarkResultWriter.SUMMARY_JSON).toFile());
        assertThat(summary.path("realExternalCaseCount").asInt()).isEqualTo(1);
    }

    @Test
    void missingMultiSweBenchDatasetIsRequiredFailureForExplicitSuite() {
        ObjectMapper objectMapper = objectMapper();
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                fakeAdapterUnchecked(objectMapper),
                new RepoBenchBenchmarkAdapter(objectMapper, List.of(missingExternalRoot())),
                new MultiSweBenchBenchmarkAdapter(objectMapper, List.of(missingExternalRoot()))
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-multi-swe-bench",
                1,
                "missing-multi-swe-required"
        ));

        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.passed()).isFalse();
        assertThat(result.failureCategory()).isEqualTo("EXTERNAL_ASSET_UNAVAILABLE");
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic).contains("Multi-SWE-bench dataset file not found"));
        assertThat(outcome.qualityFailures()).containsExactly(result);
    }

    @Test
    void missingExternalAssetIsRequiredFailureForExplicitSuite() {
        ObjectMapper objectMapper = objectMapper();
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new ContextBenchmarkCaseLoader(
                objectMapper,
                new AiderPolyglotBenchmarkAdapter(objectMapper, List.of(missingExternalRoot()))
        ));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config(
                "external-aider-polyglot",
                1,
                "missing-external-required"
        ));

        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.passed()).isFalse();
        assertThat(result.failureCategory()).isEqualTo("EXTERNAL_ASSET_UNAVAILABLE");
        assertThat(result.toleratedUnavailable()).isTrue();
        assertThat(result.diagnostics()).anySatisfy(diagnostic ->
                assertThat(diagnostic).contains("Aider polyglot benchmark root not found"));
        assertThat(outcome.qualityFailures()).containsExactly(result);
    }

    @Test
    void missingExternalAssetIsOptionalForAllSuite() {
        ExternalBenchmarkLoadResult unavailable = ExternalBenchmarkLoadResult.unavailable(
                "fake external fixture is intentionally unavailable",
                List.of(missingExternalRoot().toString())
        );
        ContextBenchmarkRunner runner = new ContextBenchmarkRunner(new UnavailableOnlyCaseLoader(objectMapper(), unavailable));

        ContextBenchmarkRunner.RunOutcome outcome = runner.run(config("all", 0, "missing-external-optional"));

        assertThat(outcome.results()).hasSize(1);
        ContextBenchmarkResult result = outcome.results().getFirst();
        assertThat(result.failureCategory()).isEqualTo("EXTERNAL_ASSET_UNAVAILABLE");
        assertThat(result.toleratedUnavailable()).isTrue();
        assertThat(outcome.qualityFailures()).isEmpty();
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    private AiderPolyglotBenchmarkAdapter fakeAdapter(ObjectMapper objectMapper) throws Exception {
        return new AiderPolyglotBenchmarkAdapter(objectMapper, List.of(fakeRoot()));
    }

    private AiderPolyglotBenchmarkAdapter fakeAdapterUnchecked(ObjectMapper objectMapper) {
        try {
            return fakeAdapter(objectMapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RepoBenchBenchmarkAdapter fakeRepoBenchAdapter(ObjectMapper objectMapper) throws Exception {
        return new RepoBenchBenchmarkAdapter(objectMapper, List.of(fakeRepoBenchRoot()));
    }

    private Path fakeRoot() throws Exception {
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("context-benchmark/external-fixtures/fake-polyglot");
        assertThat(resource).isNotNull();
        return Path.of(resource.toURI()).toAbsolutePath().normalize();
    }

    private Path fakeRepoBenchRoot() throws Exception {
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("context-benchmark/external-fixtures/fake-repobench");
        assertThat(resource).isNotNull();
        return Path.of(resource.toURI()).toAbsolutePath().normalize();
    }

    private Path missingExternalRoot() {
        return Path.of("target", "context-benchmark-runs", "missing-external-assets", UUID.randomUUID().toString())
                .toAbsolutePath()
                .normalize();
    }

    private ContextBenchmarkRunConfig config(String suite, int caseLimit, String runName) {
        return config(suite, caseLimit, runName, null);
    }

    private ContextBenchmarkRunConfig config(String suite, int caseLimit, String runName, Path datasetFile) {
        Path runDir = Path.of("target", "context-benchmark-runs", runName + "-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        return new ContextBenchmarkRunConfig(suite, caseLimit, "", runName, runDir, false, false, 20260618L, datasetFile);
    }

    private Path multiSweBenchDatasetFile(ObjectMapper objectMapper) throws Exception {
        Path root = Path.of("target", "context-benchmark-runs", "multi-swe-fixture-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        Path datasetFile = root.resolve("multi_swe_bench_fixture.jsonl");
        String fixPatch = """
                diff --git a/src/app/service.py b/src/app/service.py
                index 1111111..2222222 100644
                --- a/src/app/service.py
                +++ b/src/app/service.py
                @@ -1,3 +1,4 @@
                 def mark_resolved(ticket):
                +    ticket.status = "resolved"
                     return ticket
                diff --git a/docs/release.md b/docs/release.md
                index 3333333..4444444 100644
                --- a/docs/release.md
                +++ b/docs/release.md
                @@ -1 +1,2 @@
                 Release notes
                +Ticket status propagation fixed.
                """;
        String testPatch = """
                diff --git a/tests/test_service.py b/tests/test_service.py
                index 5555555..6666666 100644
                --- a/tests/test_service.py
                +++ b/tests/test_service.py
                @@ -1,2 +1,5 @@
                +def test_mark_resolved():
                +    ticket = Ticket()
                +    assert mark_resolved(ticket).status == "resolved"
                diff --git a/src/app/testing.py b/src/app/testing.py
                index 7777777..8888888 100644
                --- a/src/app/testing.py
                +++ b/src/app/testing.py
                @@ -1,2 +1,4 @@
                +def helper_mark_resolved():
                +    return mark_resolved(Ticket())
                """;
        String line = objectMapper.writeValueAsString(java.util.Map.of(
                "org", "demo-org",
                "repo", "demo-repo",
                "number", 42,
                "title", "Fix ticket status propagation",
                "body", "Resolving a ticket should update the domain status.",
                "resolved_issues", List.of(java.util.Map.of(
                        "number", 41,
                        "title", "Ticket stays open",
                        "body", "The service returns a ticket without changing status."
                )),
                "fix_patch", fixPatch,
                "test_patch", testPatch,
                "instance_id", "demo-org__demo-repo-42",
                "language", "python"
        ));
        Files.writeString(datasetFile, line + System.lineSeparator());
        return datasetFile;
    }

    private static final class UnavailableOnlyCaseLoader extends ContextBenchmarkCaseLoader {
        private final ExternalBenchmarkLoadResult unavailable;

        private UnavailableOnlyCaseLoader(ObjectMapper objectMapper, ExternalBenchmarkLoadResult unavailable) {
            super(objectMapper);
            this.unavailable = unavailable;
        }

        @Override
        public LoadedCases load(ContextBenchmarkRunConfig config) {
            return new LoadedCases(List.of(), List.of(unavailable));
        }
    }
}
