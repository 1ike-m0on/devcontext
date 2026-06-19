package com.devcontext.benchmark.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AiderPolyglotBenchmarkAdapter implements ExternalBenchmarkAdapter {

    private static final List<String> LANGUAGES = List.of("cpp", "go", "java", "javascript", "python", "rust");
    private static final int CASES_PER_LANGUAGE = 3;

    private final ObjectMapper objectMapper;
    private final List<Path> rootCandidatesOverride;

    public AiderPolyglotBenchmarkAdapter(ObjectMapper objectMapper) {
        this(objectMapper, List.of());
    }

    AiderPolyglotBenchmarkAdapter(ObjectMapper objectMapper, List<Path> rootCandidatesOverride) {
        this.objectMapper = objectMapper;
        this.rootCandidatesOverride = rootCandidatesOverride == null
                ? List.of()
                : rootCandidatesOverride.stream().map(Path::toAbsolutePath).map(Path::normalize).toList();
    }

    @Override
    public String suiteName() {
        return "external-aider-polyglot";
    }

    @Override
    public ExternalBenchmarkLoadResult load(ContextBenchmarkRunConfig config) {
        List<Path> candidates = rootCandidates();
        Path root = candidates.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElse(null);
        if (root == null) {
            return ExternalBenchmarkLoadResult.unavailable(
                    "Aider polyglot benchmark root not found. Set AIDER_POLYGLOT_BENCHMARK_ROOT.",
                    candidates.stream().map(Path::toString).toList()
            );
        }

        List<List<ContextBenchmarkCase>> casesByLanguage = new ArrayList<>();
        for (String language : LANGUAGES) {
            Path practice = root.resolve(language).resolve("exercises").resolve("practice");
            if (!Files.isDirectory(practice)) {
                continue;
            }
            List<ContextBenchmarkCase> languageCases = new ArrayList<>();
            try (var stream = Files.list(practice)) {
                List<Path> exercises = stream
                        .filter(Files::isDirectory)
                        .sorted()
                        .limit(CASES_PER_LANGUAGE)
                        .toList();
                for (Path exercise : exercises) {
                    readCase(language, exercise).ifPresent(languageCases::add);
                }
            } catch (IOException ignored) {
                // Keep loading other languages; the report will show whatever could be read.
            }
            if (!languageCases.isEmpty()) {
                casesByLanguage.add(languageCases);
            }
        }
        List<ContextBenchmarkCase> cases = roundRobin(casesByLanguage);
        if (cases.isEmpty()) {
            return ExternalBenchmarkLoadResult.unavailable(
                    "Aider polyglot root exists but no readable exercise .meta/config.json files were found: " + root,
                    candidates.stream().map(Path::toString).toList()
            );
        }
        return ExternalBenchmarkLoadResult.available(cases);
    }

    private java.util.Optional<ContextBenchmarkCase> readCase(String language, Path exercise) {
        Path configPath = exercise.resolve(".meta").resolve("config.json");
        if (!Files.isRegularFile(configPath)) {
            return java.util.Optional.empty();
        }
        try {
            JsonNode config = objectMapper.readTree(Files.readString(configPath));
            List<String> solutionFiles = stringList(config.path("files").path("solution"));
            if (solutionFiles.isEmpty()) {
                return java.util.Optional.empty();
            }
            List<String> forbidden = new ArrayList<>();
            forbidden.addAll(stringList(config.path("files").path("test")));
            forbidden.addAll(stringList(config.path("files").path("example")));
            forbidden.add("tests/**");
            forbidden.add("test/**");
            forbidden.add(".docs/**");
            forbidden.add(".meta/**");
            forbidden.add("example.*");
            forbidden.add("*_example.*");

            String caseId = "aider-polyglot-" + language + "-" + exercise.getFileName();
            String exerciseName = exercise.getFileName().toString().replace('-', ' ');
            String instructions = readInstructions(exercise);
            String locationPrompt = "\n\nExercise: " + exerciseName + ". For this " + language
                    + " exercise, which implementation source files should be inspected or changed? "
                    + "Return source files only; do not return checks, examples, docs, or metadata.";
            String question = instructions.isBlank()
                    ? locationPrompt.trim()
                    : clip(instructions) + locationPrompt;
            ContextBenchmarkExpected expected = new ContextBenchmarkExpected(
                    "",
                    List.of(),
                    List.of(),
                    solutionFiles,
                    List.of(),
                    List.of(),
                    List.of(),
                    forbidden,
                    List.of(),
                    List.of(),
                    List.of(),
                    java.util.Map.of(),
                    List.of(),
                    null,
                    null,
                    ""
            );
            return java.util.Optional.of(new ContextBenchmarkCase(
                    caseId,
                    suiteName(),
                    question,
                    language,
                    "aider-polyglot",
                    exercise.toAbsolutePath().normalize().toString(),
                    "source-target",
                    List.of("external", "aider-polyglot", language),
                    expected
            ));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private List<ContextBenchmarkCase> roundRobin(List<List<ContextBenchmarkCase>> casesByLanguage) {
        List<ContextBenchmarkCase> cases = new ArrayList<>();
        for (int index = 0; index < CASES_PER_LANGUAGE; index++) {
            for (List<ContextBenchmarkCase> languageCases : casesByLanguage) {
                if (index < languageCases.size()) {
                    cases.add(languageCases.get(index));
                }
            }
        }
        return cases;
    }

    private List<Path> rootCandidates() {
        if (!rootCandidatesOverride.isEmpty()) {
            return rootCandidatesOverride;
        }
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        String env = System.getenv("AIDER_POLYGLOT_BENCHMARK_ROOT");
        if (env != null && !env.isBlank()) {
            candidates.add(Path.of(env));
        }
        return candidates.stream().toList();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("");
            if (!value.isBlank()) {
                values.add(value.replace('\\', '/'));
            }
        }
        return values;
    }

    private String readInstructions(Path exercise) throws IOException {
        StringBuilder builder = new StringBuilder();
        Path introduction = exercise.resolve(".docs").resolve("introduction.md");
        Path instructions = exercise.resolve(".docs").resolve("instructions.md");
        Path append = exercise.resolve(".docs").resolve("instructions.append.md");
        for (Path file : List.of(introduction, instructions, append)) {
            if (Files.isRegularFile(file)) {
                builder.append(Files.readString(file)).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String clip(String value) {
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 900 ? compact : compact.substring(0, 900);
    }
}
