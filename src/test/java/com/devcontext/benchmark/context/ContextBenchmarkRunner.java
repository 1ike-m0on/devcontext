package com.devcontext.benchmark.context;

import com.devcontext.application.context.QueryTermNormalizer;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.EvidenceFragment;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.ProbeRequest;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.ProbeResult;
import com.devcontext.application.knowledge.KnowledgeQueryPlanner;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ContextBenchmarkRunner {

    private final ObjectMapper objectMapper;
    private final KnowledgeQueryPlanner queryPlanner;
    private final SourceEvidenceLoopProbe probe;
    private final ContextBenchmarkCaseLoader caseLoader;
    private final ContextBenchmarkRunDirectory runDirectory;
    private final ContextBenchmarkResultWriter writer;
    private final ContextBenchmarkStatsAnalyzer statsAnalyzer;
    private final ContextBenchmarkGates gates = new ContextBenchmarkGates();
    private final ContextBenchmarkReportNormalizer normalizer = new ContextBenchmarkReportNormalizer();
    private final GenericSourceLocator genericSourceLocator = new GenericSourceLocator();

    public ContextBenchmarkRunner() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    ContextBenchmarkRunner(ContextBenchmarkCaseLoader caseLoader) {
        this(new ObjectMapper().findAndRegisterModules(), caseLoader);
    }

    private ContextBenchmarkRunner(ObjectMapper objectMapper) {
        this(objectMapper, new ContextBenchmarkCaseLoader(objectMapper));
    }

    private ContextBenchmarkRunner(ObjectMapper objectMapper, ContextBenchmarkCaseLoader caseLoader) {
        this.objectMapper = objectMapper;
        this.queryPlanner = new KnowledgeQueryPlanner(new QueryTermNormalizer(objectMapper));
        this.probe = new SourceEvidenceLoopProbe();
        this.caseLoader = caseLoader;
        this.runDirectory = new ContextBenchmarkRunDirectory(objectMapper);
        this.writer = new ContextBenchmarkResultWriter(objectMapper);
        this.statsAnalyzer = new ContextBenchmarkStatsAnalyzer(objectMapper, writer);
    }

    public RunOutcome run(ContextBenchmarkRunConfig config) {
        Path runDir = runDirectory.prepare(config);
        if (config.statsOnly()) {
            List<ContextBenchmarkResult> results = statsAnalyzer.analyze(runDir);
            return new RunOutcome(runDir, results, qualityFailures(results, config));
        }

        ContextBenchmarkCaseLoader.LoadedCases loadedCases = caseLoader.load(config);
        List<ContextBenchmarkResult> results = new ArrayList<>();
        String runId = runDir.getFileName().toString();
        GitInfo gitInfo = gitInfo();

        if (loadedCases.cases().isEmpty() && !loadedCases.externalResults().isEmpty()) {
            for (ExternalBenchmarkLoadResult externalResult : loadedCases.externalResults()) {
                if (externalResult.unavailable()) {
                    ContextBenchmarkResult result = unavailableResult(config, runId, gitInfo, externalResult);
                    results.add(result);
                    writer.writeCase(runDir, result);
                }
            }
        }

        for (ContextBenchmarkCase benchmarkCase : loadedCases.cases()) {
            ContextBenchmarkResult result = executeCase(config, runId, gitInfo, benchmarkCase);
            results.add(result);
            writer.writeCase(runDir, result);
            writer.writeSummary(runDir, results);
        }
        writer.writeSummary(runDir, results);
        return new RunOutcome(runDir, results, qualityFailures(results, config));
    }

    private ContextBenchmarkResult executeCase(
            ContextBenchmarkRunConfig config,
            String runId,
            GitInfo gitInfo,
            ContextBenchmarkCase benchmarkCase
    ) {
        List<String> diagnostics = new ArrayList<>();
        KnowledgeQueryPlan plan = queryPlanner.plan(benchmarkCase.question());
        List<String> productSelectedSourcePaths = new ArrayList<>();
        List<String> diagnosticLocatorPaths = new ArrayList<>();
        Map<String, List<String>> winnerPathsByGroup = new LinkedHashMap<>();
        List<String> selectedEvidenceGroups = new ArrayList<>();
        List<String> missingEvidenceGroups = new ArrayList<>();
        boolean legacyFallbackUsed = false;

        if (requiresSourceSelection(benchmarkCase)) {
            Path root = resolveProjectRoot(benchmarkCase);
            if (Files.isDirectory(root)) {
                if (usesGenericLocator(benchmarkCase)) {
                    List<String> locatedPaths = genericSourceLocator.locate(
                            root,
                            benchmarkCase.question(),
                            genericLocatorLimit(benchmarkCase),
                            externalExpectedProjectFiles(benchmarkCase)
                    );
                    diagnosticLocatorPaths.addAll(filterForbiddenLocatorPaths(locatedPaths, benchmarkCase.expected()));
                } else {
                    try {
                        ProbeResult probeResult = probe.run(new ProbeRequest(
                                root,
                                benchmarkCase.question(),
                                1,
                                20_000,
                                plan.intent()
                        ));
                        for (EvidenceFragment fragment : probeResult.evidencePack()) {
                            productSelectedSourcePaths.add(fragment.path());
                            selectedEvidenceGroups.add(fragment.evidenceGroup());
                            addWinnerPath(winnerPathsByGroup, fragment.evidenceGroup(), fragment.path());
                        }
                        missingEvidenceGroups.addAll(probeResult.sufficiency().missingGroups());
                        legacyFallbackUsed = false;
                    } catch (RuntimeException e) {
                        diagnostics.add("source evidence loop failed: " + e.getClass().getSimpleName() + ":" + safeMessage(e));
                    }
                    diagnosticLocatorPaths.addAll(genericSourceLocator.locate(root, benchmarkCase.question(), 8));
                }
            } else {
                diagnostics.add("project root unavailable: " + root);
            }
        }

        productSelectedSourcePaths = normalizedDistinct(productSelectedSourcePaths);
        diagnosticLocatorPaths = normalizedDistinct(diagnosticLocatorPaths);
        winnerPathsByGroup = normalizedDistinctMap(winnerPathsByGroup);
        List<String> selectedSourcePathsForGate = usesGenericLocator(benchmarkCase)
                ? diagnosticLocatorPaths
                : productSelectedSourcePaths;
        selectedSourcePathsForGate = normalizedDistinct(selectedSourcePathsForGate);
        final List<String> gatePaths = selectedSourcePathsForGate;
        selectedEvidenceGroups = selectedEvidenceGroups.stream().distinct().toList();
        missingEvidenceGroups = missingEvidenceGroups.stream().distinct().toList();
        if (!usesGenericLocator(benchmarkCase) && !gatePaths.equals(productSelectedSourcePaths)) {
            diagnostics.add("product suite gate paths diverged from product evidence paths");
        }
        List<String> forbiddenHits = forbiddenHits(gatePaths, benchmarkCase.expected());
        boolean docsLeak = gatePaths.stream().anyMatch(this::docsPath);
        boolean generatedDocLeak = gatePaths.stream().anyMatch(this::generatedDocPath);
        boolean chainMatched = benchmarkCase.expected().evidenceChain().isEmpty()
                || benchmarkCase.expected().evidenceChain().entrySet().stream()
                .filter(entry -> !"name".equals(entry.getKey()))
                .allMatch(entry -> gatePaths.stream().anyMatch(path -> pathMatches(entry.getValue(), path)));
        ContextBenchmarkActual actual = new ContextBenchmarkActual(
                plan.intent(),
                plan.requiredEvidenceTypes().stream().map(Enum::name).toList(),
                plan.planningReasons(),
                targetTerms(plan),
                productSelectedSourcePaths,
                diagnosticLocatorPaths,
                selectedSourcePathsForGate,
                winnerPathsByGroup,
                selectedEvidenceGroups,
                forbiddenHits,
                docsLeak,
                generatedDocLeak,
                legacyFallbackUsed,
                chainMatched,
                missingEvidenceGroups,
                diagnostics
        );
        ContextBenchmarkGates.GateDecision decision = gates.evaluate(benchmarkCase, actual);
        return new ContextBenchmarkResult(
                runId,
                Instant.now().toString(),
                gitInfo.branch(),
                gitInfo.commit(),
                gitInfo.dirty(),
                benchmarkCase.suite(),
                benchmarkCase.caseId(),
                benchmarkCase.question(),
                benchmarkCase.language(),
                benchmarkCase.projectKind(),
                benchmarkCase.expected().intent(),
                actual.actualIntent(),
                expectedSourcePaths(benchmarkCase.expected()),
                actual.productSelectedSourcePaths(),
                actual.diagnosticLocatorPaths(),
                actual.selectedSourcePathsForGate(),
                decision.productEvidenceCount(),
                decision.unexpectedSourcePaths(),
                decision.unexpectedSourceCount(),
                decision.expectedSourceTopNHit(),
                decision.winnerPathsByGroup(),
                decision.maxProductEvidenceCount(),
                decision.maxUnexpectedSourceCount(),
                forbiddenSourcePaths(benchmarkCase.expected()),
                decision.sourceHit(),
                decision.forbiddenSourceLeak(),
                decision.docsLeak(),
                decision.generatedDocLeak(),
                decision.legacyFallbackUsed(),
                decision.evidenceChainMatched(),
                decision.missingEvidenceGroups(),
                decision.failureCategory(),
                decision.passed(),
                decision.diagnostics(),
                benchmarkCase.expected().evidenceGroups(),
                actual.selectedEvidenceGroups(),
                actual.actualRequiredEvidenceTypes(),
                actual.actualPlanningReasons()
        );
    }

    private List<String> normalizedDistinct(List<String> paths) {
        return paths.stream()
                .map(normalizer::path)
                .distinct()
                .toList();
    }

    private Map<String, List<String>> normalizedDistinctMap(Map<String, List<String>> pathsByGroup) {
        LinkedHashMap<String, List<String>> normalized = new LinkedHashMap<>();
        pathsByGroup.forEach((group, paths) -> normalized.put(group, normalizedDistinct(paths)));
        return normalized;
    }

    private void addWinnerPath(Map<String, List<String>> pathsByGroup, String group, String path) {
        String safeGroup = group == null || group.isBlank() ? "unknown" : group;
        pathsByGroup.computeIfAbsent(safeGroup, ignored -> new ArrayList<>()).add(path);
    }

    private boolean requiresSourceSelection(ContextBenchmarkCase benchmarkCase) {
        return !benchmarkCase.expected().sourcePaths().isEmpty()
                || !benchmarkCase.expected().sourcePathPatterns().isEmpty()
                || !benchmarkCase.expected().evidenceChain().isEmpty()
                || !benchmarkCase.expected().missingEvidenceGroups().isEmpty();
    }

    private boolean usesGenericLocator(ContextBenchmarkCase benchmarkCase) {
        return "cross-language".equals(benchmarkCase.suite())
                || benchmarkCase.suite().startsWith("external-");
    }

    private int genericLocatorLimit(ContextBenchmarkCase benchmarkCase) {
        if (benchmarkCase.suite().startsWith("external-")) {
            return Math.max(
                    8,
                    externalExpectedProjectFiles(benchmarkCase).size() + forbiddenSourcePaths(benchmarkCase.expected()).size() + 8
            );
        }
        return Math.max(8, externalExpectedProjectFiles(benchmarkCase).size());
    }

    private List<String> externalExpectedProjectFiles(ContextBenchmarkCase benchmarkCase) {
        if (!benchmarkCase.suite().startsWith("external-")) {
            return List.of();
        }
        return expectedSourcePaths(benchmarkCase.expected());
    }

    private List<String> filterForbiddenLocatorPaths(List<String> paths, ContextBenchmarkExpected expected) {
        List<String> forbidden = forbiddenSourcePaths(expected);
        if (forbidden.isEmpty()) {
            return paths;
        }
        return paths.stream()
                .filter(path -> forbidden.stream().noneMatch(pattern -> pathMatches(pattern, path)))
                .toList();
    }

    private Path resolveProjectRoot(ContextBenchmarkCase benchmarkCase) {
        if (benchmarkCase.projectRoot().isBlank() || "devcontext".equalsIgnoreCase(benchmarkCase.projectRoot())) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(benchmarkCase.projectRoot()).toAbsolutePath().normalize();
    }

    private List<ContextBenchmarkResult> qualityFailures(List<ContextBenchmarkResult> results, ContextBenchmarkRunConfig config) {
        return results.stream()
                .filter(result -> !result.passed())
                .filter(result -> !(config.allSuites() && result.toleratedUnavailable()))
                .toList();
    }

    private ContextBenchmarkResult unavailableResult(
            ContextBenchmarkRunConfig config,
            String runId,
            GitInfo gitInfo,
            ExternalBenchmarkLoadResult externalResult
    ) {
        return new ContextBenchmarkResult(
                runId,
                Instant.now().toString(),
                gitInfo.branch(),
                gitInfo.commit(),
                gitInfo.dirty(),
                config.suite(),
                config.suite() + "-unavailable",
                "External benchmark asset unavailable",
                "",
                "external",
                "implementation_detail",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                0,
                true,
                Map.of(),
                0,
                -1,
                externalResult.attemptedPaths(),
                false,
                false,
                false,
                false,
                false,
                true,
                List.of(),
                externalResult.failureCategory(),
                false,
                List.of(externalResult.unavailableReason()),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private List<String> targetTerms(KnowledgeQueryPlan plan) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.addAll(plan.normalizedTerms());
        terms.addAll(tokenizeQuestion(plan.originalQuery()));
        return terms.stream().toList();
    }

    private List<String> tokenizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String separated = question
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^\\p{IsHan}A-Za-z0-9_./-]+", " ")
                .toLowerCase(Locale.ROOT);
        for (String token : separated.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens.stream().toList();
    }

    private List<String> forbiddenHits(List<String> selectedSourcePaths, ContextBenchmarkExpected expected) {
        List<String> forbidden = forbiddenSourcePaths(expected);
        return selectedSourcePaths.stream()
                .filter(path -> forbidden.stream().anyMatch(pattern -> pathMatches(pattern, path)))
                .distinct()
                .toList();
    }

    private boolean pathMatches(String pattern, String path) {
        String normalizedPattern = normalizer.lowerPath(pattern);
        String normalizedPath = normalizer.lowerPath(path);
        if (normalizedPattern.equals(normalizedPath) || normalizedPath.endsWith(normalizedPattern)) {
            return true;
        }
        return (normalizedPattern.contains("*") || normalizedPattern.contains("?"))
                && normalizer.matchesGlob(normalizedPattern, normalizedPath);
    }

    private boolean docsPath(String path) {
        String lower = normalizer.lowerPath(path);
        return lower.startsWith("docs/")
                || lower.contains("/docs/")
                || lower.equals("readme.md")
                || lower.startsWith("readme.");
    }

    private boolean generatedDocPath(String path) {
        String lower = normalizer.lowerPath(path);
        return lower.startsWith(".ai/generated/")
                || lower.startsWith(".ai/manual/")
                || lower.equals(".ai/ai_readme.md");
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

    private GitInfo gitInfo() {
        return new GitInfo(
                runGit("git", "branch", "--show-current"),
                runGit("git", "rev-parse", "--short", "HEAD"),
                !runGit("git", "status", "--short").isBlank()
        );
    }

    private String runGit(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(Path.of("").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                return "";
            }
            return output.trim();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private String safeMessage(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "no_message";
        }
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    public record RunOutcome(Path runDir, List<ContextBenchmarkResult> results, List<ContextBenchmarkResult> qualityFailures) {
        public RunOutcome {
            results = results == null ? List.of() : List.copyOf(results);
            qualityFailures = qualityFailures == null ? List.of() : List.copyOf(qualityFailures);
        }
    }

    private record GitInfo(String branch, String commit, boolean dirty) {
        GitInfo {
            branch = branch == null ? "" : branch;
            commit = commit == null ? "" : commit;
        }
    }
}
