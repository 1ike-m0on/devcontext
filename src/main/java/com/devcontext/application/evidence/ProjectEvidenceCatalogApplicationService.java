package com.devcontext.application.evidence;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.context.ContextDocument;
import com.devcontext.domain.evidence.EvidenceSourceReliability;
import com.devcontext.domain.evidence.EvidenceType;
import com.devcontext.domain.evidence.ProjectEvidenceCategorySummary;
import com.devcontext.domain.evidence.ProjectEvidenceCoverageSummary;
import com.devcontext.domain.evidence.ProjectEvidenceSourceGroupSummary;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.context.ContextDocumentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class ProjectEvidenceCatalogApplicationService {

    private static final int MAX_SCAN_DEPTH = 30;
    private static final int SAMPLE_LIMIT = 8;
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git",
            ".idea",
            ".vscode",
            ".gradle",
            "target",
            "build",
            "out",
            "dist",
            "node_modules",
            "coverage",
            "data",
            "logs"
    );
    private static final List<SourceGroupDefinition> SOURCE_GROUPS = List.of(
            new SourceGroupDefinition(
                    "source_code",
                    "Source code",
                    true,
                    List.of(EvidenceType.SERVICE_CODE, EvidenceType.API_CONTROLLER)
            ),
            new SourceGroupDefinition(
                    "configuration",
                    "Configuration, deployment and CI",
                    true,
                    List.of(EvidenceType.CONFIG, EvidenceType.DEPLOYMENT, EvidenceType.CI)
            ),
            new SourceGroupDefinition(
                    "data_schema_mapper",
                    "SQL, schema and mapper",
                    true,
                    List.of(EvidenceType.SQL_SCHEMA, EvidenceType.MAPPER)
            ),
            new SourceGroupDefinition(
                    "test",
                    "Tests",
                    true,
                    List.of(EvidenceType.TEST)
            ),
            new SourceGroupDefinition(
                    "manual_documentation",
                    "Hand-written documentation",
                    false,
                    List.of(EvidenceType.MANUAL_DOC)
            ),
            new SourceGroupDefinition(
                    "generated_documentation",
                    "Generated documentation",
                    false,
                    List.of(EvidenceType.GENERATED_DOC, EvidenceType.CODE_MAP)
            ),
            new SourceGroupDefinition(
                    "benchmark_review_runtime_report",
                    "Benchmark, review and runtime reports",
                    true,
                    List.of(EvidenceType.BENCHMARK, EvidenceType.OBSERVABILITY)
            )
    );

    private final ProjectApplicationService projectService;
    private final ContextDocumentRepository contextDocumentRepository;

    public ProjectEvidenceCatalogApplicationService(
            ProjectApplicationService projectService,
            ContextDocumentRepository contextDocumentRepository
    ) {
        this.projectService = projectService;
        this.contextDocumentRepository = contextDocumentRepository;
    }

    public ProjectEvidenceCoverageSummary buildSummary(Long projectId) {
        Project project = projectService.getProject(projectId);
        Path root = Path.of(project.rootPath()).toAbsolutePath().normalize();
        CatalogBuilder builder = new CatalogBuilder();

        scanProjectFiles(root, builder);
        addContextDocumentRecords(projectId, root, builder);
        addIgnoredDirectorySkips(root, builder);
        addGeneratedDocumentPrimarySkip(builder);

        List<ProjectEvidenceSourceGroupSummary> sourceGroups = SOURCE_GROUPS.stream()
                .map(group -> builder.summary(group))
                .toList();

        return new ProjectEvidenceCoverageSummary(
                projectId,
                Instant.now(),
                sourceGroups.stream().mapToInt(ProjectEvidenceSourceGroupSummary::count).sum(),
                countByReliability(sourceGroups, EvidenceSourceReliability.PRIMARY),
                countByReliability(sourceGroups, EvidenceSourceReliability.SECONDARY),
                countByReliability(sourceGroups, EvidenceSourceReliability.DERIVED),
                builder.countsByEvidenceType(),
                builder.countsBySourceKind(),
                builder.countsByReliability(),
                sourceGroups,
                missingCategories(sourceGroups),
                builder.skippedCategories()
        );
    }

    private void scanProjectFiles(Path root, CatalogBuilder builder) {
        if (!Files.isDirectory(root)) {
            builder.addSkipped(
                    "project_root",
                    "Project root is not a readable directory.",
                    List.of(root.toString())
            );
            return;
        }
        try (Stream<Path> stream = Files.walk(root, MAX_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(root, path))
                    .sorted()
                    .forEach(path -> addFileEvidence(root, path, builder));
        } catch (IOException e) {
            builder.addSkipped(
                    "project_file_scan",
                    "Project files could not be scanned: " + e.getClass().getSimpleName(),
                    List.of(root.toString())
            );
        }
    }

    private void addFileEvidence(Path root, Path file, CatalogBuilder builder) {
        String relativePath = relative(root, file);
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (isReportPath(lower)) {
            builder.addEvidence("benchmark_review_runtime_report", EvidenceType.BENCHMARK, relativePath);
            return;
        }
        if (isGeneratedDocPath(lower)) {
            builder.addEvidence("generated_documentation", generatedDocEvidenceType(lower), relativePath);
            return;
        }
        if (isManualDocPath(lower)) {
            builder.addEvidence("manual_documentation", EvidenceType.MANUAL_DOC, relativePath);
            return;
        }
        if (isTestPath(lower)) {
            builder.addEvidence("test", EvidenceType.TEST, relativePath);
            return;
        }
        if (isSqlSchemaPath(lower)) {
            builder.addEvidence("data_schema_mapper", EvidenceType.SQL_SCHEMA, relativePath);
            return;
        }
        if (isMapperPath(lower)) {
            builder.addEvidence("data_schema_mapper", EvidenceType.MAPPER, relativePath);
            return;
        }
        if (isConfigurationPath(lower)) {
            builder.addEvidence("configuration", configurationEvidenceType(lower), relativePath);
            return;
        }
        if (isSourceCodePath(lower)) {
            builder.addEvidence("source_code", sourceCodeEvidenceType(lower), relativePath);
        }
    }

    private void addContextDocumentRecords(Long projectId, Path root, CatalogBuilder builder) {
        for (ContextDocument document : contextDocumentRepository.findByProjectId(projectId)) {
            if (document.filePath() == null || document.filePath().isBlank()) {
                continue;
            }
            Path target = root.resolve(document.filePath()).toAbsolutePath().normalize();
            if (!target.startsWith(root) || !Files.exists(target)) {
                builder.addSkipped(
                        document.generated() ? "generated_context_document_missing" : "manual_context_document_missing",
                        "Context document record exists but the file is missing.",
                        List.of(document.filePath())
                );
                continue;
            }
            if (document.generated()) {
                String lower = normalizePath(document.filePath()).toLowerCase(Locale.ROOT);
                builder.addEvidence("generated_documentation", generatedDocEvidenceType(lower), document.filePath());
            } else {
                builder.addEvidence("manual_documentation", EvidenceType.MANUAL_DOC, document.filePath());
            }
        }
    }

    private void addIgnoredDirectorySkips(Path root, CatalogBuilder builder) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root, 3)) {
            List<String> ignoredDirectories = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> isIgnoredDirectory(root, path))
                    .map(path -> relative(root, path))
                    .distinct()
                    .sorted()
                    .limit(SAMPLE_LIMIT)
                    .toList();
            if (!ignoredDirectories.isEmpty()) {
                builder.addSkipped(
                        "workspace_metadata_and_build_outputs",
                        "Workspace metadata, build outputs, dependency caches and logs are excluded from raw evidence coverage.",
                        ignoredDirectories
                );
            }
        } catch (IOException e) {
            builder.addSkipped(
                    "ignored_directory_scan",
                    "Ignored directories could not be inspected: " + e.getClass().getSimpleName(),
                    List.of(root.toString())
            );
        }
    }

    private void addGeneratedDocumentPrimarySkip(CatalogBuilder builder) {
        ProjectEvidenceSourceGroupSummary generated = builder.summary(groupByKey("generated_documentation"));
        if (generated.count() > 0) {
            builder.addSkipped(
                    "generated_documentation_as_primary_evidence",
                    "Generated docs are derived summaries; they are visible but excluded from primary evidence counts.",
                    generated.samplePaths()
            );
        }
    }

    private List<ProjectEvidenceCategorySummary> missingCategories(List<ProjectEvidenceSourceGroupSummary> sourceGroups) {
        return sourceGroups.stream()
                .filter(group -> !group.present())
                .map(group -> new ProjectEvidenceCategorySummary(
                        group.groupKey(),
                        0,
                        missingReason(group.groupKey()),
                        List.of()
                ))
                .toList();
    }

    private String missingReason(String groupKey) {
        return switch (groupKey) {
            case "source_code" -> "No source code evidence was found in the allowed project tree.";
            case "configuration" -> "No configuration, deployment or CI evidence was found.";
            case "data_schema_mapper" -> "No SQL schema, migration or mapper evidence was found.";
            case "test" -> "No test evidence was found.";
            case "manual_documentation" -> "No hand-written documentation was found.";
            case "generated_documentation" -> "No generated context documentation was found.";
            case "benchmark_review_runtime_report" -> "No benchmark, review or runtime report evidence was found.";
            default -> "No evidence was found for this category.";
        };
    }

    private int countByReliability(List<ProjectEvidenceSourceGroupSummary> groups, EvidenceSourceReliability reliability) {
        return groups.stream()
                .filter(group -> group.sourceReliabilities().contains(reliability.value()))
                .mapToInt(ProjectEvidenceSourceGroupSummary::count)
                .sum();
    }

    private boolean isGeneratedDocPath(String path) {
        return path.startsWith(".ai/generated/")
                || path.equals(".ai/ai_readme.md")
                || path.equals(".ai/code-map.json")
                || path.equals("agents.md");
    }

    private EvidenceType generatedDocEvidenceType(String path) {
        if (path.equals(".ai/code-map.json") || path.endsWith("/code-map.json")) {
            return EvidenceType.CODE_MAP;
        }
        return EvidenceType.GENERATED_DOC;
    }

    private boolean isManualDocPath(String path) {
        return path.startsWith(".ai/manual/")
                || path.equals("readme.md")
                || (path.startsWith("docs/") && path.endsWith(".md"))
                || (path.endsWith(".md") && !isGeneratedDocPath(path));
    }

    private boolean isReportPath(String path) {
        return path.startsWith("docs/benchmarks/")
                || path.startsWith("docs/reports/")
                || path.contains("/benchmarks/")
                || path.contains("/reports/")
                || path.contains("benchmark")
                || path.contains("load-test")
                || path.contains("review-report")
                || path.contains("runtime-report")
                || path.contains("manual-acceptance");
    }

    private boolean isTestPath(String path) {
        return path.contains("/test/")
                || path.contains("/tests/")
                || path.contains("/fixtures/")
                || path.endsWith("test.java")
                || path.endsWith("tests.java")
                || path.endsWith(".spec.ts")
                || path.endsWith(".test.ts")
                || path.endsWith(".spec.tsx")
                || path.endsWith(".test.tsx");
    }

    private boolean isSqlSchemaPath(String path) {
        return path.endsWith(".sql")
                || path.contains("/migration/")
                || path.contains("/migrations/")
                || path.contains("/schema/")
                || path.contains("/db/");
    }

    private boolean isMapperPath(String path) {
        return path.endsWith("mapper.xml")
                || path.contains("/mapper/")
                || path.contains("/mybatis/");
    }

    private boolean isConfigurationPath(String path) {
        String name = Path.of(path).getFileName().toString();
        return name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("requirements.txt")
                || name.equals("go.mod")
                || name.equals("cargo.toml")
                || name.equals("dockerfile")
                || name.equals(".env.example")
                || name.startsWith("application.")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".properties")
                || path.startsWith(".github/workflows/")
                || path.startsWith("deploy/")
                || path.startsWith("k8s/")
                || path.contains("/kubernetes/");
    }

    private EvidenceType configurationEvidenceType(String path) {
        String name = Path.of(path).getFileName().toString();
        if (path.startsWith(".github/workflows/")) {
            return EvidenceType.CI;
        }
        if (name.equals("dockerfile")
                || name.startsWith("docker-compose.")
                || name.equals("compose.yml")
                || name.equals("compose.yaml")
                || path.startsWith("deploy/")
                || path.startsWith("k8s/")
                || path.contains("/kubernetes/")) {
            return EvidenceType.DEPLOYMENT;
        }
        return EvidenceType.CONFIG;
    }

    private boolean isSourceCodePath(String path) {
        return path.endsWith(".java")
                || path.endsWith(".kt")
                || path.endsWith(".ts")
                || path.endsWith(".tsx")
                || path.endsWith(".js")
                || path.endsWith(".jsx")
                || path.endsWith(".py")
                || path.endsWith(".go")
                || path.endsWith(".rs")
                || path.endsWith(".cs")
                || path.endsWith(".php")
                || path.endsWith(".rb")
                || path.endsWith(".c")
                || path.endsWith(".cpp")
                || path.endsWith(".h")
                || path.endsWith(".hpp");
    }

    private EvidenceType sourceCodeEvidenceType(String path) {
        if (path.contains("controller") || path.contains("/api/") || path.contains("/web/")) {
            return EvidenceType.API_CONTROLLER;
        }
        return EvidenceType.SERVICE_CODE;
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (IGNORED_DIRS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isIgnoredDirectory(Path root, Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IGNORED_DIRS.contains(name) && path.toAbsolutePath().normalize().startsWith(root);
    }

    private SourceGroupDefinition groupByKey(String groupKey) {
        return SOURCE_GROUPS.stream()
                .filter(group -> group.groupKey().equals(groupKey))
                .findFirst()
                .orElseThrow();
    }

    private String relative(Path root, Path path) {
        return normalizePath(root.relativize(path.toAbsolutePath().normalize()).toString());
    }

    private String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private record SourceGroupDefinition(
            String groupKey,
            String label,
            boolean primaryEvidence,
            List<EvidenceType> plannedEvidenceTypes
    ) {
    }

    private static final class CatalogBuilder {
        private final Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
        private final Map<EvidenceType, Set<String>> pathsByEvidenceType = new EnumMap<>(EvidenceType.class);
        private final Map<String, Set<String>> pathsBySourceKind = new LinkedHashMap<>();
        private final Map<String, Set<String>> pathsByReliability = new LinkedHashMap<>();
        private final Map<String, SkippedAccumulator> skipped = new LinkedHashMap<>();

        private void addEvidence(String groupKey, EvidenceType evidenceType, String path) {
            String normalizedPath = path == null ? "" : path.replace('\\', '/');
            groups.computeIfAbsent(groupKey, ignored -> new GroupAccumulator())
                    .add(evidenceType, normalizedPath);
            pathsByEvidenceType.computeIfAbsent(evidenceType, ignored -> new LinkedHashSet<>()).add(normalizedPath);
            pathsBySourceKind.computeIfAbsent(evidenceType.sourceKind().value(), ignored -> new LinkedHashSet<>()).add(normalizedPath);
            pathsByReliability.computeIfAbsent(evidenceType.sourceReliability().value(), ignored -> new LinkedHashSet<>()).add(normalizedPath);
        }

        private void addSkipped(String category, String reason, List<String> paths) {
            skipped.computeIfAbsent(category, ignored -> new SkippedAccumulator(reason)).add(paths);
        }

        private ProjectEvidenceSourceGroupSummary summary(SourceGroupDefinition group) {
            GroupAccumulator accumulator = groups.getOrDefault(group.groupKey(), new GroupAccumulator());
            List<EvidenceType> evidenceTypes = accumulator.evidenceTypes().isEmpty()
                    ? group.plannedEvidenceTypes()
                    : accumulator.evidenceTypes();
            return new ProjectEvidenceSourceGroupSummary(
                    group.groupKey(),
                    group.label(),
                    accumulator.count(),
                    accumulator.count() > 0,
                    group.primaryEvidence(),
                    evidenceTypes.stream().map(EvidenceType::canonicalName).toList(),
                    evidenceTypes.stream()
                            .map(type -> type.sourceKind().value())
                            .distinct()
                            .toList(),
                    evidenceTypes.stream()
                            .map(type -> type.sourceReliability().value())
                            .distinct()
                            .toList(),
                    accumulator.samplePaths()
            );
        }

        private Map<String, Integer> countsByEvidenceType() {
            return countMap(pathsByEvidenceType);
        }

        private Map<String, Integer> countsBySourceKind() {
            return countMap(pathsBySourceKind);
        }

        private Map<String, Integer> countsByReliability() {
            return countMap(pathsByReliability);
        }

        private List<ProjectEvidenceCategorySummary> skippedCategories() {
            return skipped.entrySet().stream()
                    .map(entry -> new ProjectEvidenceCategorySummary(
                            entry.getKey(),
                            entry.getValue().paths().size(),
                            entry.getValue().reason(),
                            entry.getValue().samplePaths()
                    ))
                    .toList();
        }

        private Map<String, Integer> countMap(Map<?, Set<String>> pathsByKey) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            pathsByKey.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> counts.put(keyName(entry.getKey()), entry.getValue().size()));
            return counts;
        }

        private String keyName(Object key) {
            if (key instanceof EvidenceType evidenceType) {
                return evidenceType.canonicalName();
            }
            return String.valueOf(key);
        }
    }

    private static final class GroupAccumulator {
        private final Map<String, Set<EvidenceType>> evidenceTypesByPath = new LinkedHashMap<>();

        private void add(EvidenceType evidenceType, String path) {
            evidenceTypesByPath.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(evidenceType);
        }

        private int count() {
            return evidenceTypesByPath.size();
        }

        private List<EvidenceType> evidenceTypes() {
            return evidenceTypesByPath.values().stream()
                    .flatMap(Set::stream)
                    .distinct()
                    .sorted(Comparator.comparing(EvidenceType::canonicalName))
                    .toList();
        }

        private List<String> samplePaths() {
            return evidenceTypesByPath.keySet().stream()
                    .limit(SAMPLE_LIMIT)
                    .toList();
        }
    }

    private static final class SkippedAccumulator {
        private final String reason;
        private final Set<String> paths = new LinkedHashSet<>();

        private SkippedAccumulator(String reason) {
            this.reason = reason;
        }

        private void add(List<String> values) {
            if (values == null) {
                return;
            }
            paths.addAll(values);
        }

        private String reason() {
            return reason;
        }

        private Set<String> paths() {
            return paths;
        }

        private List<String> samplePaths() {
            return paths.stream()
                    .limit(SAMPLE_LIMIT)
                    .toList();
        }
    }
}
