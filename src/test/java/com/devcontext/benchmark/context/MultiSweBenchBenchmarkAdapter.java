package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MultiSweBenchBenchmarkAdapter implements ExternalBenchmarkAdapter {

    private static final Set<String> PRIMARY_SOURCE_EXTENSIONS = Set.of(
            ".c", ".h", ".cc", ".cpp", ".cxx", ".hpp", ".java", ".kt", ".scala", ".py", ".pyi",
            ".go", ".rs", ".js", ".jsx", ".ts", ".tsx", ".rb", ".php", ".cs", ".swift", ".m",
            ".mm", ".erl", ".ex", ".exs", ".clj", ".cljs", ".lua", ".sh", ".bash", ".zsh",
            ".sql", ".yml", ".yaml", ".json", ".toml", ".xml", ".gradle", ".cmake", ".pony"
    );

    private final ObjectMapper objectMapper;
    private final List<Path> datasetFileCandidatesOverride;

    public MultiSweBenchBenchmarkAdapter(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    MultiSweBenchBenchmarkAdapter(ObjectMapper objectMapper, List<Path> datasetFileCandidatesOverride) {
        this.objectMapper = objectMapper;
        this.datasetFileCandidatesOverride = datasetFileCandidatesOverride == null
                ? List.of()
                : datasetFileCandidatesOverride.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    @Override
    public String suiteName() {
        return "external-multi-swe-bench";
    }

    @Override
    public ExternalBenchmarkLoadResult load(ContextBenchmarkRunConfig config) {
        List<Path> candidates = datasetFileCandidates(config);
        Path datasetFile = candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        if (datasetFile == null) {
            return ExternalBenchmarkLoadResult.unavailable(
                    "Multi-SWE-bench dataset file not found. Pass -DatasetFile or set MULTI_SWE_BENCH_DATASET_FILE.",
                    candidates.stream().map(Path::toString).toList()
            );
        }

        try {
            List<ContextBenchmarkCase> cases = loadCases(datasetFile, config == null ? 0 : config.caseLimit());
            if (cases.isEmpty()) {
                return new ExternalBenchmarkLoadResult(
                        List.of(),
                        true,
                        "ORACLE_PARSING_FAILED",
                        "Multi-SWE-bench dataset file exists but no readable cases with fix_patch source oracle were found: "
                                + datasetFile,
                        List.of(datasetFile.toString())
                );
            }
            return ExternalBenchmarkLoadResult.available(cases);
        } catch (IOException | RuntimeException e) {
            return new ExternalBenchmarkLoadResult(
                    List.of(),
                    true,
                    "EXTERNAL_ADAPTER_FAILED",
                    "Multi-SWE-bench adapter failed for " + datasetFile + ": "
                            + e.getClass().getSimpleName() + ":" + safeMessage(e),
                    List.of(datasetFile.toString())
            );
        }
    }

    private List<ContextBenchmarkCase> loadCases(Path datasetFile, int caseLimit) throws IOException {
        List<ContextBenchmarkCase> cases = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(datasetFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safeLine = line.trim();
                if (safeLine.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(safeLine);
                readCase(datasetFile, node).ifPresent(cases::add);
                if (caseLimit > 0 && cases.size() >= caseLimit) {
                    break;
                }
            }
        }
        return cases;
    }

    private Optional<ContextBenchmarkCase> readCase(Path datasetFile, JsonNode node) {
        String fixPatch = firstText(node, "fix_patch", "patch");
        List<String> sourcePaths = changedPaths(fixPatch).stream()
                .filter(this::primarySourcePath)
                .distinct()
                .toList();
        if (sourcePaths.isEmpty()) {
            return Optional.empty();
        }

        String testPatch = firstText(node, "test_patch");
        List<String> forbiddenPaths = new ArrayList<>();
        forbiddenPaths.addAll(changedPaths(testPatch).stream()
                .distinct()
                .toList());
        forbiddenPaths.addAll(changedPaths(fixPatch).stream()
                .filter(this::nonPrimaryForbiddenPath)
                .filter(path -> forbiddenPaths.stream().noneMatch(path::equals))
                .toList());
        String caseId = caseId(node);
        Path projectRoot = materializeProject(datasetFile, caseId, fixPatch, testPatch, sourcePaths, forbiddenPaths);
        String language = normalizeLanguage(firstText(node, "language", "lang"), sourcePaths);
        String question = question(node, sourcePaths.size(), forbiddenPaths.size());

        ContextBenchmarkExpected expected = new ContextBenchmarkExpected(
                "",
                List.of(),
                List.of(),
                sourcePaths,
                List.of(),
                List.of(),
                List.of(),
                forbiddenPaths,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
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
                "multi-swe-bench",
                projectRoot.toString(),
                "source-target",
                tags(node, datasetFile),
                expected
        ));
    }

    private Path materializeProject(
            Path datasetFile,
            String caseId,
            String fixPatch,
            String testPatch,
            List<String> sourcePaths,
            List<String> forbiddenPaths
    ) {
        Path root = Path.of("target", "context-benchmark-generated", "multi-swe-bench",
                        datasetLabel(datasetFile), safeId(caseId))
                .toAbsolutePath()
                .normalize();
        Map<String, String> files = new LinkedHashMap<>();
        files.putAll(patchFileContents(fixPatch));
        files.putAll(patchFileContents(testPatch));
        for (String path : sourcePaths) {
            files.putIfAbsent(path, placeholder(path));
        }
        for (String path : forbiddenPaths) {
            files.putIfAbsent(path, placeholder(path));
        }

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String relative = normalizePath(entry.getKey());
            if (relative.isBlank() || relative.startsWith("../") || relative.contains("/../")) {
                continue;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root)) {
                continue;
            }
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return root;
            }
        }
        return root;
    }

    private Map<String, String> patchFileContents(String patch) {
        LinkedHashMap<String, StringBuilder> builders = new LinkedHashMap<>();
        String currentPath = "";
        for (String line : (patch == null ? "" : patch).split("\\R", -1)) {
            String diffPath = diffPath(line);
            if (!diffPath.isBlank()) {
                currentPath = diffPath;
                builders.putIfAbsent(currentPath, new StringBuilder());
                continue;
            }
            if (currentPath.isBlank()) {
                continue;
            }
            StringBuilder builder = builders.get(currentPath);
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                continue;
            }
            if (line.startsWith("+") || line.startsWith(" ")) {
                builder.append(line.substring(1)).append(System.lineSeparator());
            }
        }

        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        builders.forEach((path, builder) -> files.put(path, builder.isEmpty() ? placeholder(path) : builder.toString()));
        return files;
    }

    private List<String> changedPaths(String patch) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        String currentPath = "";
        for (String line : (patch == null ? "" : patch).split("\\R", -1)) {
            String diffPath = diffPath(line);
            if (!diffPath.isBlank()) {
                currentPath = diffPath;
                paths.add(currentPath);
                continue;
            }
            String plusPath = plusPath(line);
            if (!plusPath.isBlank()) {
                currentPath = plusPath;
                paths.add(currentPath);
            }
        }
        return paths.stream().toList();
    }

    private String diffPath(String line) {
        if (line == null || !line.startsWith("diff --git ")) {
            return "";
        }
        int bIndex = line.indexOf(" b/");
        if (bIndex < 0) {
            return "";
        }
        return normalizeDiffPath(line.substring(bIndex + 3));
    }

    private String plusPath(String line) {
        if (line == null || !line.startsWith("+++ ")) {
            return "";
        }
        return normalizeDiffPath(line.substring(4));
    }

    private String normalizeDiffPath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2) {
            path = path.substring(1, path.length() - 1);
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            path = path.substring(2);
        }
        int tabIndex = path.indexOf('\t');
        if (tabIndex >= 0) {
            path = path.substring(0, tabIndex);
        }
        path = normalizePath(path);
        return path.equals("/dev/null") || path.equals("dev/null") ? "" : path;
    }

    private boolean primarySourcePath(String path) {
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        if (lower.isBlank() || testPath(lower) || docPath(lower) || examplePath(lower)) {
            return false;
        }
        if (lower.equals("makefile") || lower.equals("dockerfile") || lower.endsWith("/makefile")
                || lower.endsWith("/dockerfile") || lower.endsWith("cmakelists.txt")) {
            return true;
        }
        return PRIMARY_SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private boolean testPath(String lower) {
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.startsWith("test/")
                || lower.startsWith("tests/")
                || lower.contains("/test/")
                || lower.contains("/tests/")
                || fileName.startsWith("test_")
                || fileName.contains("_test.")
                || fileName.contains(".test.")
                || fileName.contains("_spec.")
                || fileName.contains(".spec.");
    }

    private boolean docPath(String lower) {
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.startsWith("docs/")
                || lower.startsWith(".docs/")
                || lower.startsWith(".release-notes/")
                || lower.contains("/docs/")
                || fileName.startsWith("readme")
                || fileName.startsWith("changelog")
                || fileName.endsWith(".md")
                || fileName.endsWith(".rst")
                || fileName.endsWith(".txt");
    }

    private boolean examplePath(String lower) {
        return lower.startsWith("example/")
                || lower.startsWith("examples/")
                || lower.contains("/example/")
                || lower.contains("/examples/");
    }

    private boolean nonPrimaryForbiddenPath(String path) {
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        return testPath(lower) || docPath(lower) || examplePath(lower);
    }

    private String question(JsonNode node, int sourceCount, int forbiddenCount) {
        StringBuilder builder = new StringBuilder();
        String repo = repoName(node);
        if (!repo.isBlank()) {
            builder.append("Multi-SWE-bench issue for ").append(repo).append(". ");
        } else {
            builder.append("Multi-SWE-bench issue. ");
        }
        appendSection(builder, "Issue title", firstText(node, "title"));
        appendSection(builder, "Issue body", firstText(node, "body"));
        JsonNode resolvedIssues = node.path("resolved_issues");
        if (resolvedIssues.isArray()) {
            for (JsonNode issue : resolvedIssues) {
                appendSection(builder, "Resolved issue " + firstText(issue, "number"),
                        firstText(issue, "title") + "\n" + firstText(issue, "body"));
            }
        }
        builder.append("Which implementation source files need context to fix this issue? ");
        builder.append("Return source files only; do not return tests, docs, README, release notes, or metadata. ");
        builder.append("Oracle has ").append(sourceCount).append(" source file(s)");
        if (forbiddenCount > 0) {
            builder.append(" and ").append(forbiddenCount).append(" test/diagnostic forbidden path(s)");
        }
        builder.append(".");
        return clip(builder.toString(), 8_000);
    }

    private void appendSection(StringBuilder builder, String label, String value) {
        String safeValue = clip(value, 1_800);
        if (!safeValue.isBlank()) {
            builder.append(label).append(": ").append(safeValue).append(" ");
        }
    }

    private List<String> tags(JsonNode node, Path datasetFile) {
        List<String> tags = new ArrayList<>();
        tags.add("external");
        tags.add("multi-swe-bench");
        String difficulty = firstText(node, "difficulty");
        if (!difficulty.isBlank()) {
            tags.add("difficulty:" + difficulty);
        }
        String language = firstText(node, "language");
        if (!language.isBlank()) {
            tags.add("language:" + normalizeLanguage(language, List.of()));
        }
        tags.add("dataset:" + datasetLabel(datasetFile));
        return tags;
    }

    private String caseId(JsonNode node) {
        String instanceId = firstText(node, "instance_id", "instanceId", "id");
        if (!instanceId.isBlank()) {
            return "multi-swe-bench-" + safeId(instanceId);
        }
        String repo = repoName(node).replace('/', '-');
        String number = firstText(node, "number");
        String hash = Integer.toHexString((repo + number + firstText(node, "title")).hashCode());
        return "multi-swe-bench-" + safeId(repo + "-" + number + "-" + hash);
    }

    private String repoName(JsonNode node) {
        String org = firstText(node, "org", "owner");
        String repo = firstText(node, "repo", "repository");
        if (!org.isBlank() && !repo.isBlank()) {
            return org + "/" + repo;
        }
        return repo;
    }

    private List<Path> datasetFileCandidates(ContextBenchmarkRunConfig config) {
        if (config != null && config.datasetFile() != null) {
            return List.of(config.datasetFile());
        }
        if (!datasetFileCandidatesOverride.isEmpty()) {
            return datasetFileCandidatesOverride;
        }
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        String env = System.getenv("MULTI_SWE_BENCH_DATASET_FILE");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        return candidates.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
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

    private String normalizeLanguage(String value, List<String> sourcePaths) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return sourcePaths.stream().findFirst().map(this::languageFromPath).orElse("");
    }

    private String languageFromPath(String path) {
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py")) {
            return "python";
        }
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "javascript";
        }
        if (lower.endsWith(".rs")) {
            return "rust";
        }
        if (lower.endsWith(".go")) {
            return "go";
        }
        if (lower.endsWith(".c") || lower.endsWith(".h") || lower.endsWith(".cpp") || lower.endsWith(".cc")) {
            return "c";
        }
        return "";
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').trim();
    }

    private String datasetLabel(Path datasetFile) {
        String fileName = datasetFile == null || datasetFile.getFileName() == null
                ? "dataset"
                : datasetFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.contains("flash")) {
            return "flash";
        }
        if (fileName.contains("mini")) {
            return "mini";
        }
        return safeId(fileName);
    }

    private String safeId(String value) {
        String safe = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('\\', '-')
                .replace('/', '-')
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        return safe.isBlank() ? "unknown" : safe;
    }

    private String placeholder(String path) {
        return "/* Multi-SWE-bench patch materialized file: " + path + " */" + System.lineSeparator();
    }

    private String clip(String value, int limit) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= limit ? compact : compact.substring(0, limit);
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "no_message";
        }
        return message.length() <= 240 ? message : message.substring(0, 240);
    }
}
