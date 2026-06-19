package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class RepoBenchBenchmarkAdapter implements ExternalBenchmarkAdapter {

    private static final List<String> LANGUAGES = List.of("python", "java");

    private final ObjectMapper objectMapper;
    private final List<Path> rootCandidatesOverride;

    public RepoBenchBenchmarkAdapter(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    RepoBenchBenchmarkAdapter(ObjectMapper objectMapper, List<Path> rootCandidatesOverride) {
        this.objectMapper = objectMapper;
        this.rootCandidatesOverride = rootCandidatesOverride == null
                ? List.of()
                : rootCandidatesOverride.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    @Override
    public String suiteName() {
        return "external-repobench";
    }

    @Override
    public ExternalBenchmarkLoadResult load(ContextBenchmarkRunConfig config) {
        List<Path> candidates = rootCandidates(config);
        List<Path> existingRoots = candidates.stream()
                .filter(Files::isDirectory)
                .toList();
        if (existingRoots.isEmpty()) {
            return ExternalBenchmarkLoadResult.unavailable(
                    "RepoBench root not found. Set REPOBENCH_ROOT or REPOBENCH_BENCHMARK_ROOT.",
                    candidates.stream().map(Path::toString).toList()
            );
        }

        for (Path root : existingRoots) {
            List<ContextBenchmarkCase> loaded = loadFromRoot(root);
            if (!loaded.isEmpty()) {
                return ExternalBenchmarkLoadResult.available(stratified(loaded));
            }
        }

        return new ExternalBenchmarkLoadResult(
                List.of(),
                true,
                "ORACLE_PARSING_FAILED",
                "RepoBench root exists but no readable cases with related cross-file oracle paths were found. "
                        + "Existing roots checked: " + existingRoots,
                candidates.stream().map(Path::toString).toList()
        );
    }

    private List<ContextBenchmarkCase> loadFromRoot(Path root) {
        List<ContextBenchmarkCase> cases = new ArrayList<>();
        for (Path caseFile : caseFiles(root)) {
            cases.addAll(readCaseFile(root, caseFile));
        }
        return cases;
    }

    private List<Path> caseFiles(Path root) {
        List<Path> files = new ArrayList<>();
        Path fixtureCases = root.resolve("cases.jsonl");
        if (Files.isRegularFile(fixtureCases)) {
            files.add(fixtureCases);
            return files;
        }
        try (var stream = Files.walk(root, 4)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".jsonl") || fileName.endsWith(".json");
                    })
                    .filter(path -> !path.toString().contains("\\target\\")
                            && !path.toString().replace('\\', '/').contains("/target/"))
                    .sorted()
                    .limit(20)
                    .toList();
        } catch (IOException e) {
            return files;
        }
    }

    private List<ContextBenchmarkCase> readCaseFile(Path root, Path caseFile) {
        try {
            String content = Files.readString(caseFile);
            String trimmed = content.trim();
            if (trimmed.startsWith("[")) {
                List<ContextBenchmarkCase> cases = new ArrayList<>();
                JsonNode array = objectMapper.readTree(trimmed);
                if (array.isArray()) {
                    for (JsonNode node : array) {
                        readCase(root, node).ifPresent(cases::add);
                    }
                }
                return cases;
            }
            List<ContextBenchmarkCase> cases = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String safeLine = line.trim();
                    if (!safeLine.isBlank()) {
                        readCase(root, objectMapper.readTree(safeLine)).ifPresent(cases::add);
                    }
                }
            }
            return cases;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Optional<ContextBenchmarkCase> readCase(Path root, JsonNode node) {
        String language = firstText(node, "language", "lang");
        if (language.isBlank()) {
            language = inferLanguage(firstText(node, "targetFile", "target_file", "filePath", "file_path", "path"));
        }
        language = normalizeLanguage(language);
        if (!LANGUAGES.contains(language)) {
            return Optional.empty();
        }

        List<String> relatedFiles = stringList(node, "relatedFiles", "related_files", "contextFiles",
                "context_files", "oracleFiles", "oracle_files", "dependencies");
        if (relatedFiles.isEmpty()) {
            relatedFiles = pathListFromObjects(node, "context", "retrieval_context", "crossFileContext", "cross_file_context");
        }
        relatedFiles = normalizedPaths(relatedFiles);
        if (relatedFiles.isEmpty()) {
            return Optional.empty();
        }

        String repoRoot = firstText(node, "repoRoot", "repo_root", "repositoryRoot", "repository_root", "projectRoot", "project_root");
        Path projectRoot = repoRoot.isBlank() ? root : root.resolve(repoRoot).normalize();
        if (!Files.isDirectory(projectRoot)) {
            return Optional.empty();
        }

        String targetFile = normalizePath(firstText(node, "targetFile", "target_file", "filePath", "file_path", "path"));
        String targetSymbol = firstText(node, "targetSymbol", "target_symbol", "symbol", "maskedSymbol", "masked_symbol");
        String maskedLocation = firstText(node, "maskedLocation", "masked_location", "maskedLine", "masked_line",
                "prompt", "cropped_code", "import_statement");
        String caseId = firstText(node, "caseId", "case_id", "id", "taskId", "task_id");
        if (caseId.isBlank()) {
            caseId = "repobench-" + language + "-" + Integer.toHexString((targetFile + relatedFiles).hashCode());
        }
        projectRoot = materializeIfNeeded(projectRoot, node, caseId, targetFile);

        List<String> forbidden = new ArrayList<>();
        forbidden.addAll(stringList(node, "forbiddenFiles", "forbidden_files", "metadataFiles", "metadata_files"));
        forbidden.addAll(stringList(node, "decoyFiles", "decoy_files"));
        forbidden.add("docs/**");
        forbidden.add(".docs/**");
        forbidden.add("README*");
        forbidden.add("readme*");
        forbidden.add("metadata/**");
        forbidden.add(".meta/**");

        String question = question(language, targetFile, targetSymbol, maskedLocation);
        ContextBenchmarkExpected expected = new ContextBenchmarkExpected(
                "",
                List.of(),
                List.of(),
                relatedFiles,
                List.of(),
                targetFile.isBlank() ? List.of() : List.of(targetFile),
                List.of(),
                normalizedPaths(forbidden),
                List.of(),
                List.of(),
                List.of(),
                java.util.Map.of(),
                List.of(),
                null,
                null,
                ""
        );
        return Optional.of(new ContextBenchmarkCase(
                caseId,
                suiteName(),
                question,
                language,
                isFakeRoot(root) ? "repobench-fake" : "repobench",
                projectRoot.toAbsolutePath().normalize().toString(),
                "source-target",
                List.of("external", "repobench", language),
                expected
        ));
    }

    private List<ContextBenchmarkCase> stratified(List<ContextBenchmarkCase> cases) {
        List<List<ContextBenchmarkCase>> byLanguage = new ArrayList<>();
        for (String language : LANGUAGES) {
            List<ContextBenchmarkCase> languageCases = cases.stream()
                    .filter(benchmarkCase -> language.equals(benchmarkCase.language()))
                    .toList();
            if (!languageCases.isEmpty()) {
                byLanguage.add(languageCases);
            }
        }
        List<ContextBenchmarkCase> result = new ArrayList<>();
        int maxLanguageCases = byLanguage.stream()
                .mapToInt(List::size)
                .max()
                .orElse(0);
        for (int index = 0; index < maxLanguageCases; index++) {
            for (List<ContextBenchmarkCase> languageCases : byLanguage) {
                if (index < languageCases.size()) {
                    result.add(languageCases.get(index));
                }
            }
        }
        return result;
    }

    private List<Path> rootCandidates(ContextBenchmarkRunConfig config) {
        if (!rootCandidatesOverride.isEmpty()) {
            return rootCandidatesOverride;
        }
        if (config != null && config.runName().contains("fake")) {
            return fakeRoot().map(List::of).orElseGet(List::of);
        }
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        for (String envName : List.of("REPOBENCH_ROOT", "REPOBENCH_BENCHMARK_ROOT")) {
            String env = System.getenv(envName);
            if (env != null && !env.isBlank()) {
                candidates.add(Path.of(env));
            }
        }
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    private Optional<Path> fakeRoot() {
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource("context-benchmark/external-fixtures/fake-repobench");
        if (resource == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(resource.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private String question(String language, String targetFile, String targetSymbol, String maskedLocation) {
        StringBuilder builder = new StringBuilder();
        builder.append("RepoBench ").append(language).append(" cross-file context request. ");
        if (!targetFile.isBlank()) {
            builder.append("Target file: ").append(targetFile).append(". ");
        }
        if (!targetSymbol.isBlank()) {
            builder.append("Target symbol: ").append(targetSymbol).append(". ");
        }
        if (!maskedLocation.isBlank()) {
            builder.append("Masked location: ").append(clip(maskedLocation)).append(". ");
        }
        builder.append("Which repository source files provide the cross-file context needed at this location? ");
        builder.append("Return source files only; do not return docs, README, metadata, or decoy files.");
        return builder.toString();
    }

    private List<String> stringList(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : value) {
                    String text = item.isTextual() ? item.asText("") : firstText(item, "path", "file", "filePath", "file_path");
                    if (!text.isBlank()) {
                        values.add(text);
                    }
                }
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private List<String> pathListFromObjects(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode item : value) {
                    String path = firstText(item, "path", "file", "filePath", "file_path", "targetFile", "target_file");
                    if (!path.isBlank()) {
                        values.add(path);
                    }
                }
                if (!values.isEmpty()) {
                    return values;
                }
            }
        }
        return List.of();
    }

    private Path materializeIfNeeded(Path projectRoot, JsonNode node, String caseId, String targetFile) {
        Map<String, String> snippets = snippetFiles(node, targetFile);
        if (snippets.isEmpty()) {
            return projectRoot;
        }
        boolean allPresent = snippets.keySet().stream()
                .allMatch(path -> Files.isRegularFile(projectRoot.resolve(path).normalize()));
        if (allPresent) {
            return projectRoot;
        }
        Path generatedRoot = Path.of("target", "context-benchmark-generated", "repobench", safeCaseId(caseId))
                .toAbsolutePath()
                .normalize();
        try {
            for (Map.Entry<String, String> entry : snippets.entrySet()) {
                Path file = generatedRoot.resolve(entry.getKey()).normalize();
                if (!file.startsWith(generatedRoot)) {
                    continue;
                }
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue());
            }
            return generatedRoot;
        } catch (IOException e) {
            return projectRoot;
        }
    }

    private Map<String, String> snippetFiles(JsonNode node, String targetFile) {
        LinkedHashMap<String, String> snippets = new LinkedHashMap<>();
        addContextSnippets(snippets, node.path("context"));
        addContextSnippets(snippets, node.path("retrieval_context"));
        addContextSnippets(snippets, node.path("crossFileContext"));
        addContextSnippets(snippets, node.path("cross_file_context"));

        String targetContent = firstText(node, "cropped_code", "code", "source", "prompt");
        if (!targetFile.isBlank() && !targetContent.isBlank()) {
            String importStatement = firstText(node, "import_statement", "imports");
            snippets.putIfAbsent(targetFile, importStatement.isBlank()
                    ? targetContent
                    : importStatement + System.lineSeparator() + targetContent);
        }
        return snippets;
    }

    private void addContextSnippets(Map<String, String> snippets, JsonNode context) {
        if (!context.isArray()) {
            return;
        }
        for (JsonNode item : context) {
            String path = normalizePath(firstText(item, "path", "file", "filePath", "file_path"));
            String snippet = firstText(item, "snippet", "content", "code", "source");
            if (!path.isBlank() && !snippet.isBlank()) {
                snippets.putIfAbsent(path, snippet);
            }
        }
    }

    private String safeCaseId(String caseId) {
        String safe = caseId == null ? "unknown" : caseId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isTextual() || value.isNumber()) {
                String text = value.asText("").trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private List<String> normalizedPaths(List<String> paths) {
        return paths.stream()
                .map(this::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim();
    }

    private String normalizeLanguage(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        if (normalized.equals("py")) {
            return "python";
        }
        return normalized;
    }

    private String inferLanguage(String path) {
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            return "python";
        }
        if (lower.endsWith(".java")) {
            return "java";
        }
        return "";
    }

    private boolean isFakeRoot(Path root) {
        return root.toString().replace('\\', '/').contains("fake-repobench");
    }

    private String clip(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 240);
    }
}
