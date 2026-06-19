package com.devcontext.benchmark.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ContextBenchmarkCaseLoader {

    private static final String CASE_ROOT = "context-benchmark/cases/";

    private final ObjectMapper objectMapper;
    private final AiderPolyglotBenchmarkAdapter aiderPolyglotAdapter;
    private final RepoBenchBenchmarkAdapter repoBenchAdapter;
    private final MultiSweBenchBenchmarkAdapter multiSweBenchAdapter;

    public ContextBenchmarkCaseLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.aiderPolyglotAdapter = new AiderPolyglotBenchmarkAdapter(objectMapper);
        this.repoBenchAdapter = new RepoBenchBenchmarkAdapter(objectMapper);
        this.multiSweBenchAdapter = new MultiSweBenchBenchmarkAdapter(objectMapper);
    }

    ContextBenchmarkCaseLoader(ObjectMapper objectMapper, AiderPolyglotBenchmarkAdapter aiderPolyglotAdapter) {
        this(
                objectMapper,
                aiderPolyglotAdapter,
                new RepoBenchBenchmarkAdapter(objectMapper),
                new MultiSweBenchBenchmarkAdapter(objectMapper)
        );
    }

    ContextBenchmarkCaseLoader(
            ObjectMapper objectMapper,
            AiderPolyglotBenchmarkAdapter aiderPolyglotAdapter,
            RepoBenchBenchmarkAdapter repoBenchAdapter
    ) {
        this(objectMapper, aiderPolyglotAdapter, repoBenchAdapter, new MultiSweBenchBenchmarkAdapter(objectMapper));
    }

    ContextBenchmarkCaseLoader(
            ObjectMapper objectMapper,
            AiderPolyglotBenchmarkAdapter aiderPolyglotAdapter,
            RepoBenchBenchmarkAdapter repoBenchAdapter,
            MultiSweBenchBenchmarkAdapter multiSweBenchAdapter
    ) {
        this.objectMapper = objectMapper;
        this.aiderPolyglotAdapter = aiderPolyglotAdapter;
        this.repoBenchAdapter = repoBenchAdapter;
        this.multiSweBenchAdapter = multiSweBenchAdapter;
    }

    public LoadedCases load(ContextBenchmarkRunConfig config) {
        List<ContextBenchmarkCase> cases = new ArrayList<>();
        List<ExternalBenchmarkLoadResult> externalResults = new ArrayList<>();
        switch (config.suite()) {
            case "query-understanding" -> cases.addAll(loadResource("query-understanding-regression.json", "query-understanding"));
            case "evidence-pack" -> cases.addAll(activeCases(loadResource("evidence-pack-regression.json", "evidence-pack")));
            case "evidence-pack-pending" -> cases.addAll(pendingCases(loadResource("evidence-pack-regression.json", "evidence-pack-pending")));
            case "boundary" -> cases.addAll(loadResource("boundary-matrix.json", "boundary"));
            case "cross-language" -> cases.addAll(loadResource("cross-language-general.json", "cross-language"));
            case "external-aider-polyglot" -> {
                ExternalBenchmarkLoadResult result = aiderPolyglotAdapter.load(config);
                externalResults.add(result);
                cases.addAll(result.cases());
            }
            case "external-repobench" -> {
                ExternalBenchmarkLoadResult result = repoBenchAdapter.load(config);
                externalResults.add(result);
                cases.addAll(result.cases());
            }
            case "external-multi-swe-bench" -> {
                ExternalBenchmarkLoadResult result = multiSweBenchAdapter.load(config);
                externalResults.add(result);
                cases.addAll(result.cases());
            }
            case "external-swe-bench-lite", "external-defects4j" ->
                    externalResults.add(ExternalBenchmarkLoadResult.notImplemented(config.suite()));
            case "all" -> {
                cases.addAll(loadResource("query-understanding-regression.json", "query-understanding"));
                cases.addAll(activeCases(loadResource("evidence-pack-regression.json", "evidence-pack")));
                cases.addAll(loadResource("boundary-matrix.json", "boundary"));
                cases.addAll(loadResource("cross-language-general.json", "cross-language"));
                ExternalBenchmarkLoadResult result = aiderPolyglotAdapter.load(config);
                externalResults.add(result);
                cases.addAll(result.cases());
            }
            default -> throw new IllegalArgumentException("Unsupported context benchmark suite: " + config.suite());
        }
        validateActiveCaseSourceOracles(cases, config);
        cases = filter(cases, config);
        return new LoadedCases(cases, externalResults);
    }

    private List<ContextBenchmarkCase> loadResource(String resourceName, String suite) {
        String resource = CASE_ROOT + resourceName;
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Missing benchmark case resource: " + resource);
            }
            List<ContextBenchmarkCase> cases = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            return cases.stream()
                    .map(benchmarkCase -> benchmarkCase.suite().isBlank() ? benchmarkCase.withSuite(suite) : benchmarkCase)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load benchmark cases: " + resource, e);
        }
    }

    private List<ContextBenchmarkCase> filter(List<ContextBenchmarkCase> cases, ContextBenchmarkRunConfig config) {
        List<ContextBenchmarkCase> filtered = cases;
        if (!config.keywords().isBlank()) {
            List<String> keywords = java.util.Arrays.stream(config.keywords().split(","))
                    .map(String::trim)
                    .filter(keyword -> !keyword.isBlank())
                    .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                    .toList();
            filtered = filtered.stream()
                    .filter(benchmarkCase -> keywords.stream().anyMatch(keyword -> haystack(benchmarkCase).contains(keyword)))
                    .toList();
        }
        if (config.caseLimit() > 0 && filtered.size() > config.caseLimit()) {
            filtered = filtered.subList(0, config.caseLimit());
        }
        return List.copyOf(filtered);
    }

    private List<ContextBenchmarkCase> activeCases(List<ContextBenchmarkCase> cases) {
        return cases.stream()
                .filter(benchmarkCase -> !benchmarkCase.pendingByDefault())
                .toList();
    }

    private List<ContextBenchmarkCase> pendingCases(List<ContextBenchmarkCase> cases) {
        return cases.stream()
                .filter(ContextBenchmarkCase::pendingByDefault)
                .toList();
    }

    private void validateActiveCaseSourceOracles(List<ContextBenchmarkCase> cases, ContextBenchmarkRunConfig config) {
        if (config.explicitExternalSuite() || "evidence-pack-pending".equals(config.suite())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (ContextBenchmarkCase benchmarkCase : cases) {
            if (benchmarkCase.pendingByDefault() || benchmarkCase.suite().startsWith("external-")) {
                continue;
            }
            Path root = resolveProjectRoot(benchmarkCase);
            for (String pattern : expectedSourceOracles(benchmarkCase.expected())) {
                if (!sourceOracleExists(root, pattern)) {
                    missing.add(benchmarkCase.caseId() + " -> " + pattern);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Default context benchmark cases reference missing expected source paths. "
                    + "Move future product-context cases to pending before using them in default gates: " + missing);
        }
    }

    private Path resolveProjectRoot(ContextBenchmarkCase benchmarkCase) {
        if (benchmarkCase.projectRoot().isBlank() || "devcontext".equalsIgnoreCase(benchmarkCase.projectRoot())) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(benchmarkCase.projectRoot()).toAbsolutePath().normalize();
    }

    private List<String> expectedSourceOracles(ContextBenchmarkExpected expected) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.addAll(expected.sourcePaths());
        result.addAll(expected.sourcePathPatterns());
        expected.evidenceChain().forEach((key, value) -> {
            if (!"name".equals(key) && value != null && !value.isBlank()) {
                result.add(value);
            }
        });
        return result.stream().toList();
    }

    private boolean sourceOracleExists(Path root, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String normalizedPattern = pattern.replace('\\', '/');
        if (!containsGlob(normalizedPattern)) {
            return Files.exists(root.resolve(normalizedPattern).normalize());
        }
        String regex = globRegex(normalizedPattern);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .anyMatch(path -> path.matches(regex));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to validate expected source oracle under " + root, e);
        }
    }

    private boolean containsGlob(String value) {
        return value.contains("*") || value.contains("?") || value.contains("[") || value.contains("{");
    }

    private String globRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < pattern.length() && pattern.charAt(index + 1) == '*';
                if (doubleStar) {
                    regex.append(".*");
                    index++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                    regex.append('\\');
                }
                regex.append(current);
            }
        }
        return regex.append('$').toString();
    }

    private String haystack(ContextBenchmarkCase benchmarkCase) {
        return (benchmarkCase.caseId() + "\n"
                + benchmarkCase.question() + "\n"
                + benchmarkCase.language() + "\n"
                + benchmarkCase.projectKind() + "\n"
                + String.join(" ", benchmarkCase.tags())).toLowerCase(Locale.ROOT);
    }

    public record LoadedCases(List<ContextBenchmarkCase> cases, List<ExternalBenchmarkLoadResult> externalResults) {
        public LoadedCases {
            cases = cases == null ? List.of() : List.copyOf(cases);
            externalResults = externalResults == null ? List.of() : List.copyOf(externalResults);
        }
    }
}
