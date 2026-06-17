package com.devcontext.application.knowledge;

import com.devcontext.application.context.ReadOnlyContextProvider;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeMapConfigKey;
import com.devcontext.domain.codemap.CodeMapDependencyEdge;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeMapRoutingHint;
import com.devcontext.domain.codemap.CodeMapTestRelation;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.context.ReadOnlyContextBudget;
import com.devcontext.domain.context.ReadOnlyContextFileReadRequest;
import com.devcontext.domain.context.ReadOnlyContextReadResult;
import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.domain.knowledge.EvidenceEvaluation;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.graph.ProjectGraphRepository;
import com.devcontext.ports.knowledge.KnowledgeSourceRepository;
import com.devcontext.ports.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ControlledDeepScanService {

    private static final int MAX_SCAN_FILES = 4;
    private static final int MAX_SCAN_CHARS = 12_000;
    private static final int MAX_SCAN_LINES = 240;
    private static final int MAX_CODE_MAP_CHARS = 80_000;
    private static final int MAX_CODE_MAP_LINES = 2_000;
    private static final Set<KnowledgeEvidenceType> SCANNABLE_EVIDENCE_TYPES = EnumSet.of(
            KnowledgeEvidenceType.API_CONTROLLER,
            KnowledgeEvidenceType.SERVICE_CODE,
            KnowledgeEvidenceType.CONFIG,
            KnowledgeEvidenceType.SQL_SCHEMA,
            KnowledgeEvidenceType.MAPPER,
            KnowledgeEvidenceType.TEST
    );
    private static final Set<String> SCANNABLE_SOURCE_KINDS = Set.of(
            "api_surface",
            "source_code",
            "configuration",
            "data_schema",
            "data_access",
            "test_artifact"
    );

    private final KnowledgeSourceRepository sourceRepository;
    private final ProjectRepository projectRepository;
    private final ProjectGraphRepository projectGraphRepository;
    private final KnowledgeEvidenceClassifier evidenceClassifier;
    private final ObjectMapper objectMapper;
    private final ReadOnlyContextProvider readOnlyContextProvider;

    public ControlledDeepScanService(
            KnowledgeSourceRepository sourceRepository,
            ProjectRepository projectRepository,
            ProjectGraphRepository projectGraphRepository,
            KnowledgeEvidenceClassifier evidenceClassifier,
            ObjectMapper objectMapper,
            ReadOnlyContextProvider readOnlyContextProvider
    ) {
        this.sourceRepository = sourceRepository;
        this.projectRepository = projectRepository;
        this.projectGraphRepository = projectGraphRepository;
        this.evidenceClassifier = evidenceClassifier;
        this.objectMapper = objectMapper;
        this.readOnlyContextProvider = readOnlyContextProvider;
    }

    public Result scan(Long runId, Long requestedSourceId, KnowledgeSearchResponse response, EvidenceEvaluation evaluation) {
        if (evaluation == null || evaluation.sufficient()) {
            return Result.skipped("initial_evidence_supported");
        }
        TargetPlan targetPlan = TargetPlan.from(response == null ? null : response.queryPlan());
        if (!targetPlan.hasScannableNeed()) {
            return Result.skipped("query_plan_no_source_config_sql_test_evidence");
        }
        Optional<KnowledgeSource> source = resolveSource(requestedSourceId, response);
        if (source.isEmpty()) {
            return Result.skipped("knowledge_source_unresolved");
        }
        Path root = sourceRoot(source.get());
        if (!Files.isDirectory(root)) {
            return Result.skipped("knowledge_source_root_unavailable");
        }

        Map<String, CandidateAccumulator> candidateMap = new LinkedHashMap<>();
        List<String> skippedReasons = new ArrayList<>();
        resolveProject(source.get(), root).ifPresent(project -> collectGraphCandidates(project.id(), targetPlan, candidateMap));
        collectCodeMapCandidates(runId, root, targetPlan, candidateMap, skippedReasons);

        List<ScanCandidate> candidates = candidateMap.values().stream()
                .map(CandidateAccumulator::toCandidate)
                .toList();
        if (candidates.isEmpty()) {
            return Result.skipped(
                    "no_project_graph_or_code_map_candidates",
                    List.of(),
                    List.of(),
                    skippedReasons,
                    List.of(),
                    false,
                    0,
                    0,
                    0
            );
        }

        ScanBudget budget = new ScanBudget();
        List<String> scannedFiles = new ArrayList<>();
        List<KnowledgeSearchResult> evidenceCandidates = new ArrayList<>();
        for (ScanCandidate candidate : candidates) {
            if (budget.files >= MAX_SCAN_FILES) {
                budget.budgetLimited = true;
                skippedReasons.add("file_budget_exhausted:" + candidate.relativePath());
                continue;
            }
            if (budget.chars >= MAX_SCAN_CHARS || budget.lines >= MAX_SCAN_LINES) {
                budget.budgetLimited = true;
                skippedReasons.add("content_budget_exhausted:" + candidate.relativePath());
                continue;
            }
            ReadOnlyContextReadResult read = readCandidate(runId, root, candidate, budget);
            budget.files += read.filesRead();
            budget.chars += read.charactersReturned();
            budget.lines += read.linesReturned();
            budget.budgetLimited = budget.budgetLimited || read.budgetLimited();
            if (!read.finished()) {
                skippedReasons.add("candidate_read_" + read.status() + ":" + read.reason() + ":" + candidate.relativePath());
                continue;
            }
            if (read.content().isBlank()) {
                skippedReasons.add("candidate_empty:" + candidate.relativePath());
                continue;
            }
            scannedFiles.add(candidate.relativePath());
            evidenceCandidates.add(toSearchResult(
                    source.get(),
                    candidate,
                    read.content(),
                    evidenceCandidates.size()
            ));
        }

        return Result.finished(
                candidates.stream().map(ScanCandidate::relativePath).toList(),
                scannedFiles,
                skippedReasons,
                evidenceCandidates,
                budget.budgetLimited,
                budget.files,
                budget.chars,
                budget.lines
        );
    }

    private ReadOnlyContextReadResult readCandidate(
            Long runId,
            Path root,
            ScanCandidate candidate,
            ScanBudget budget
    ) {
        return readOnlyContextProvider.readFile(new ReadOnlyContextFileReadRequest(
                runId,
                root,
                candidate.relativePath(),
                ReadOnlyContextBudget.read(
                        1,
                        MAX_SCAN_CHARS - budget.chars,
                        MAX_SCAN_LINES - budget.lines
                ),
                "controlled_deep_scan_candidate"
        ));
    }

    private KnowledgeSearchResult toSearchResult(
            KnowledgeSource source,
            ScanCandidate candidate,
            String scannedContent,
            int index
    ) {
        LinkedHashSet<KnowledgeEvidenceType> evidenceTypes = new LinkedHashSet<>(candidate.evidenceTypes());
        evidenceTypes.addAll(evidenceClassifier.classify(
                candidate.relativePath(),
                title(candidate.relativePath()),
                "controlled-deep-scan",
                scannedContent
        ));
        if (evidenceTypes.stream().anyMatch(type -> type != KnowledgeEvidenceType.GENERATED_DOC && type != KnowledgeEvidenceType.CODE_MAP)) {
            evidenceTypes.remove(KnowledgeEvidenceType.GENERATED_DOC);
            evidenceTypes.remove(KnowledgeEvidenceType.CODE_MAP);
        }

        List<String> scoreReasons = new ArrayList<>();
        scoreReasons.add("controlled_deep_scan");
        candidate.reasons().stream()
                .map(reason -> "deep_scan_candidate:" + reason)
                .forEach(scoreReasons::add);

        return new KnowledgeSearchResult(
                null,
                null,
                source.id(),
                source.name(),
                candidate.relativePath(),
                title(candidate.relativePath()),
                "controlled-deep-scan",
                renderEvidenceContent(candidate, scannedContent),
                0.0,
                0.0,
                0.01 + Math.max(0, SCANNABLE_EVIDENCE_TYPES.size() - index) * 0.001,
                evidenceTypes.stream().toList(),
                List.copyOf(scoreReasons)
        );
    }

    private String renderEvidenceContent(ScanCandidate candidate, String scannedContent) {
        return "# Controlled deep scan evidence\n\n"
                + "File: " + candidate.relativePath() + "\n"
                + "Candidate reasons: " + candidate.reasons() + "\n\n"
                + "```" + codeFenceLanguage(candidate.relativePath()) + "\n"
                + scannedContent.stripTrailing() + "\n"
                + "```\n";
    }

    private Optional<KnowledgeSource> resolveSource(Long requestedSourceId, KnowledgeSearchResponse response) {
        Long sourceId = requestedSourceId;
        if (sourceId == null && response != null && response.results() != null) {
            sourceId = response.results().stream()
                    .map(KnowledgeSearchResult::sourceId)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        return sourceId == null ? Optional.empty() : sourceRepository.findById(sourceId);
    }

    private Optional<Project> resolveProject(KnowledgeSource source, Path sourceRoot) {
        Optional<Project> exact = projectRepository.findByRootPath(sourceRoot.toString());
        if (exact.isPresent()) {
            return exact;
        }
        String sourceRootKey = pathKey(sourceRoot.toString());
        return projectRepository.findAll().stream()
                .filter(project -> {
                    String projectRootKey = pathKey(project.rootPath());
                    return !sourceRootKey.isBlank()
                            && !projectRootKey.isBlank()
                            && (sourceRootKey.equals(projectRootKey) || sourceRootKey.startsWith(projectRootKey + "/"));
                })
                .max(Comparator.comparingInt(project -> pathKey(project.rootPath()).length()));
    }

    private void collectGraphCandidates(
            Long projectId,
            TargetPlan targetPlan,
            Map<String, CandidateAccumulator> candidateMap
    ) {
        if (projectId == null) {
            return;
        }
        for (ProjectGraphNode node : projectGraphRepository.findNodesByProjectId(projectId)) {
            if (targetPlan.matches(node.evidenceType(), node.sourceKind())) {
                addCandidate(
                        candidateMap,
                        node.sourcePath(),
                        "project_graph_node:" + safe(node.nodeType()),
                        evidenceType(node.evidenceType())
                );
            }
        }
        for (ProjectGraphEdge edge : projectGraphRepository.findEdgesByProjectId(projectId)) {
            if (targetPlan.matches(edge.evidenceType(), edge.sourceKind())) {
                addCandidate(
                        candidateMap,
                        edge.sourcePath(),
                        "project_graph_edge:" + safe(edge.edgeType()),
                        evidenceType(edge.evidenceType())
                );
            }
        }
    }

    private void collectCodeMapCandidates(
            Long runId,
            Path root,
            TargetPlan targetPlan,
            Map<String, CandidateAccumulator> candidateMap,
            List<String> skippedReasons
    ) {
        ReadOnlyContextReadResult codeMapRead = readOnlyContextProvider.readFile(new ReadOnlyContextFileReadRequest(
                runId,
                root,
                ".ai/code-map.json",
                ReadOnlyContextBudget.read(1, MAX_CODE_MAP_CHARS, MAX_CODE_MAP_LINES),
                "controlled_deep_scan_code_map"
        ));
        if (!codeMapRead.finished()) {
            skippedReasons.add("code_map_missing");
            if (!codeMapRead.reason().isBlank()) {
                skippedReasons.add("code_map_read_" + codeMapRead.status() + ":" + codeMapRead.reason());
            }
            return;
        }
        CodeMap codeMap;
        try {
            codeMap = objectMapper.readValue(codeMapRead.content(), CodeMap.class);
        } catch (IOException e) {
            skippedReasons.add("code_map_unreadable");
            return;
        }

        for (CodeMapFileEntry file : safeList(codeMap.files())) {
            KnowledgeEvidenceType evidenceType = evidenceTypeForCodeMapFile(file);
            if (targetPlan.matches(evidenceType)) {
                addCandidate(candidateMap, file.path(), "code_map_file:" + safe(file.kind()), evidenceType);
            }
        }
        for (CodeEndpoint endpoint : safeList(codeMap.endpoints())) {
            if (targetPlan.matches(KnowledgeEvidenceType.API_CONTROLLER)) {
                addCandidate(candidateMap, endpoint.file(), "code_map_endpoint", KnowledgeEvidenceType.API_CONTROLLER);
            }
        }
        for (CodeSymbol symbol : safeList(codeMap.symbols())) {
            KnowledgeEvidenceType evidenceType = evidenceTypeForSymbol(symbol);
            if (targetPlan.matches(evidenceType)) {
                addCandidate(candidateMap, symbol.file(), "code_map_symbol:" + safe(symbol.role()), evidenceType);
            }
        }
        for (CodeMapConfigKey configKey : safeList(codeMap.configKeys())) {
            if (targetPlan.matches(KnowledgeEvidenceType.CONFIG)) {
                addCandidate(candidateMap, configKey.file(), "code_map_config_key", KnowledgeEvidenceType.CONFIG);
            }
        }
        for (CodeMapRoutingHint hint : safeList(codeMap.sqlHints())) {
            if (targetPlan.matches(KnowledgeEvidenceType.SQL_SCHEMA)) {
                addCandidate(candidateMap, hint.file(), "code_map_sql_hint", KnowledgeEvidenceType.SQL_SCHEMA);
            }
        }
        for (CodeMapRoutingHint hint : safeList(codeMap.mapperHints())) {
            if (targetPlan.matches(KnowledgeEvidenceType.MAPPER)) {
                addCandidate(candidateMap, hint.file(), "code_map_mapper_hint", KnowledgeEvidenceType.MAPPER);
            }
        }
        for (CodeMapRoutingHint hint : safeList(codeMap.entityHints())) {
            if (targetPlan.matches(KnowledgeEvidenceType.SQL_SCHEMA)) {
                addCandidate(candidateMap, hint.file(), "code_map_entity_hint", KnowledgeEvidenceType.SQL_SCHEMA);
            }
        }
        for (CodeMapTestRelation relation : safeList(codeMap.testRelations())) {
            if (targetPlan.matches(KnowledgeEvidenceType.TEST)) {
                addCandidate(candidateMap, relation.testFile(), "code_map_test_relation", KnowledgeEvidenceType.TEST);
            }
            if (targetPlan.matches(KnowledgeEvidenceType.SERVICE_CODE)) {
                addCandidate(candidateMap, relation.targetFile(), "code_map_test_target", KnowledgeEvidenceType.SERVICE_CODE);
            }
        }
        for (CodeMapDependencyEdge dependency : safeList(codeMap.dependencyEdges())) {
            if (targetPlan.matches(KnowledgeEvidenceType.SERVICE_CODE)) {
                addCandidate(candidateMap, dependency.fromFile(), "code_map_dependency_from", KnowledgeEvidenceType.SERVICE_CODE);
                addCandidate(candidateMap, dependency.toFile(), "code_map_dependency_to", KnowledgeEvidenceType.SERVICE_CODE);
            }
        }
    }

    private void addCandidate(
            Map<String, CandidateAccumulator> candidates,
            String rawPath,
            String reason,
            KnowledgeEvidenceType evidenceType
    ) {
        String relativePath = normalizeRelativePath(rawPath);
        if (relativePath.isBlank() || !isLikelyScannablePath(relativePath)) {
            return;
        }
        CandidateAccumulator candidate = candidates.computeIfAbsent(relativePath, CandidateAccumulator::new);
        candidate.addReason(reason);
        if (evidenceType != null) {
            candidate.addEvidenceType(evidenceType);
        }
    }

    private Path sourceRoot(KnowledgeSource source) {
        return Path.of(source.rootPath()).toAbsolutePath().normalize();
    }

    private KnowledgeEvidenceType evidenceType(String value) {
        return KnowledgeEvidenceType.normalize(value).orElse(null);
    }

    private KnowledgeEvidenceType evidenceTypeForCodeMapFile(CodeMapFileEntry file) {
        if (file == null) {
            return null;
        }
        String kind = normalized(file.kind());
        Set<String> roles = safeList(file.roles()).stream()
                .map(this::normalized)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
        if ("configuration".equals(kind) || roles.contains("configuration")) {
            return KnowledgeEvidenceType.CONFIG;
        }
        if ("database_schema".equals(kind) || roles.contains("sql") || roles.contains("database-schema")) {
            return KnowledgeEvidenceType.SQL_SCHEMA;
        }
        if ("mapper".equals(kind) || roles.contains("mapper") || roles.contains("data-access")) {
            return KnowledgeEvidenceType.MAPPER;
        }
        if ("test".equals(kind) || roles.contains("test")) {
            return KnowledgeEvidenceType.TEST;
        }
        if (roles.contains("controller") || roles.contains("endpoint")) {
            return KnowledgeEvidenceType.API_CONTROLLER;
        }
        if (roles.contains("entity") || roles.contains("domain-entity")) {
            return KnowledgeEvidenceType.SQL_SCHEMA;
        }
        if ("source".equals(kind)) {
            return KnowledgeEvidenceType.SERVICE_CODE;
        }
        return null;
    }

    private KnowledgeEvidenceType evidenceTypeForSymbol(CodeSymbol symbol) {
        if (symbol == null) {
            return null;
        }
        return switch (normalized(symbol.role())) {
            case "controller" -> KnowledgeEvidenceType.API_CONTROLLER;
            case "repository", "mapper", "dao" -> KnowledgeEvidenceType.MAPPER;
            case "entity" -> KnowledgeEvidenceType.SQL_SCHEMA;
            case "service", "handler", "usecase", "component" -> KnowledgeEvidenceType.SERVICE_CODE;
            default -> KnowledgeEvidenceType.SERVICE_CODE;
        };
    }

    private boolean isLikelyScannablePath(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".ai/") || lower.startsWith(".git/") || lower.contains("/node_modules/") || lower.startsWith("target/")) {
            return false;
        }
        return lower.endsWith(".java")
                || lower.endsWith(".kt")
                || lower.endsWith(".kts")
                || lower.endsWith(".ts")
                || lower.endsWith(".tsx")
                || lower.endsWith(".js")
                || lower.endsWith(".jsx")
                || lower.endsWith(".sql")
                || lower.endsWith(".xml")
                || lower.endsWith(".yml")
                || lower.endsWith(".yaml")
                || lower.endsWith(".properties")
                || lower.endsWith(".env")
                || lower.endsWith(".env.example")
                || lower.endsWith("compose.yml")
                || lower.endsWith("compose.yaml")
                || lower.endsWith("docker-compose.yml")
                || lower.endsWith("docker-compose.yaml");
    }

    private String normalizeRelativePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            return "";
        }
        return normalized;
    }

    private String title(String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String codeFenceLanguage(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "java";
        }
        if (lower.endsWith(".sql")) {
            return "sql";
        }
        if (lower.endsWith(".xml")) {
            return "xml";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "yaml";
        }
        if (lower.endsWith(".properties")) {
            return "properties";
        }
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) {
            return "typescript";
        }
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) {
            return "javascript";
        }
        return "";
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String pathKey(String value) {
        return normalizePath(value).toLowerCase(Locale.ROOT);
    }

    private String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    public record Result(
            String status,
            String skipReason,
            List<String> candidateFiles,
            List<String> scannedFiles,
            List<String> skippedReasons,
            List<KnowledgeSearchResult> evidenceCandidates,
            boolean budgetLimited,
            int fileBudget,
            int charBudget,
            int lineBudget,
            int filesRead,
            int charsRead,
            int linesRead
    ) {
        public Result {
            candidateFiles = candidateFiles == null ? List.of() : List.copyOf(candidateFiles);
            scannedFiles = scannedFiles == null ? List.of() : List.copyOf(scannedFiles);
            skippedReasons = skippedReasons == null ? List.of() : List.copyOf(skippedReasons);
            evidenceCandidates = evidenceCandidates == null ? List.of() : List.copyOf(evidenceCandidates);
        }

        static Result skipped(String reason) {
            return skipped(reason, List.of(), List.of(), List.of(), List.of(), false, 0, 0, 0);
        }

        static Result skipped(
                String reason,
                List<String> candidateFiles,
                List<String> scannedFiles,
                List<String> skippedReasons,
                List<KnowledgeSearchResult> evidenceCandidates,
                boolean budgetLimited,
                int filesRead,
                int charsRead,
                int linesRead
        ) {
            return new Result(
                    "skipped",
                    reason,
                    candidateFiles,
                    scannedFiles,
                    skippedReasons,
                    evidenceCandidates,
                    budgetLimited,
                    MAX_SCAN_FILES,
                    MAX_SCAN_CHARS,
                    MAX_SCAN_LINES,
                    filesRead,
                    charsRead,
                    linesRead
            );
        }

        static Result finished(
                List<String> candidateFiles,
                List<String> scannedFiles,
                List<String> skippedReasons,
                List<KnowledgeSearchResult> evidenceCandidates,
                boolean budgetLimited,
                int filesRead,
                int charsRead,
                int linesRead
        ) {
            return new Result(
                    "finished",
                    null,
                    candidateFiles,
                    scannedFiles,
                    skippedReasons,
                    evidenceCandidates,
                    budgetLimited,
                    MAX_SCAN_FILES,
                    MAX_SCAN_CHARS,
                    MAX_SCAN_LINES,
                    filesRead,
                    charsRead,
                    linesRead
            );
        }

        boolean started() {
            return !"skipped".equals(status);
        }
    }

    private record TargetPlan(
            Set<KnowledgeEvidenceType> evidenceTypes,
            Set<String> sourceKinds
    ) {
        static TargetPlan from(KnowledgeQueryPlan plan) {
            LinkedHashSet<KnowledgeEvidenceType> types = new LinkedHashSet<>();
            LinkedHashSet<String> kinds = new LinkedHashSet<>();
            if (plan != null) {
                addScannableTypes(types, plan.requiredEvidenceTypes());
                addScannableTypes(types, plan.preferredEvidenceTypes());
                addScannableKinds(kinds, plan.requiredSourceKinds());
                addScannableKinds(kinds, plan.preferredSourceKinds());
            }
            types.stream()
                    .map(type -> type.sourceKind().value())
                    .forEach(kinds::add);
            return new TargetPlan(types, kinds);
        }

        private static void addScannableTypes(Set<KnowledgeEvidenceType> target, List<KnowledgeEvidenceType> values) {
            if (values == null) {
                return;
            }
            values.stream()
                    .filter(SCANNABLE_EVIDENCE_TYPES::contains)
                    .forEach(target::add);
        }

        private static void addScannableKinds(Set<String> target, List<String> values) {
            if (values == null) {
                return;
            }
            values.stream()
                    .filter(Objects::nonNull)
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .filter(SCANNABLE_SOURCE_KINDS::contains)
                    .forEach(target::add);
        }

        TargetPlan {
            evidenceTypes = evidenceTypes == null ? Set.of() : Set.copyOf(evidenceTypes);
            sourceKinds = sourceKinds == null ? Set.of() : Set.copyOf(sourceKinds);
        }

        boolean hasScannableNeed() {
            return !evidenceTypes.isEmpty() || !sourceKinds.isEmpty();
        }

        boolean matches(String evidenceType, String sourceKind) {
            Optional<KnowledgeEvidenceType> normalizedType = KnowledgeEvidenceType.normalize(evidenceType);
            if (normalizedType.isPresent() && matches(normalizedType.get())) {
                return true;
            }
            return sourceKind != null && sourceKinds.contains(sourceKind.trim().toLowerCase(Locale.ROOT));
        }

        boolean matches(KnowledgeEvidenceType evidenceType) {
            if (evidenceType == null) {
                return false;
            }
            return evidenceTypes.contains(evidenceType)
                    || sourceKinds.contains(evidenceType.sourceKind().value());
        }
    }

    private static final class CandidateAccumulator {
        private final String relativePath;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final LinkedHashSet<KnowledgeEvidenceType> evidenceTypes = new LinkedHashSet<>();

        private CandidateAccumulator(String relativePath) {
            this.relativePath = relativePath;
        }

        private void addReason(String reason) {
            if (reason != null && !reason.isBlank()) {
                reasons.add(reason);
            }
        }

        private void addEvidenceType(KnowledgeEvidenceType evidenceType) {
            if (evidenceType != null) {
                evidenceTypes.add(evidenceType);
            }
        }

        private ScanCandidate toCandidate() {
            return new ScanCandidate(relativePath, reasons.stream().toList(), evidenceTypes.stream().toList());
        }
    }

    private record ScanCandidate(
            String relativePath,
            List<String> reasons,
            List<KnowledgeEvidenceType> evidenceTypes
    ) {
        private ScanCandidate {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
        }
    }

    private static final class ScanBudget {
        private int files;
        private int chars;
        private int lines;
        private boolean budgetLimited;
    }

}
