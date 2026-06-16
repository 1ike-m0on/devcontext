package com.devcontext.application.graph;

import com.devcontext.application.profile.ProjectProfileApplicationService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeMapConfigKey;
import com.devcontext.domain.codemap.CodeMapDependencyEdge;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeMapRoutingHint;
import com.devcontext.domain.codemap.CodeMapTestRelation;
import com.devcontext.domain.codemap.CodeModule;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.evidence.EvidenceType;
import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNeighborhood;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.domain.graph.ProjectGraphSummary;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.graph.ProjectGraphRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ProjectGraphApplicationService {

    private static final String CODE_MAP_PATH = ".ai/code-map.json";

    private final ProjectApplicationService projectService;
    private final ProjectProfileApplicationService profileService;
    private final ProjectGraphRepository graphRepository;
    private final ObjectMapper objectMapper;

    public ProjectGraphApplicationService(
            ProjectApplicationService projectService,
            ProjectProfileApplicationService profileService,
            ProjectGraphRepository graphRepository,
            ObjectMapper objectMapper
    ) {
        this.projectService = projectService;
        this.profileService = profileService;
        this.graphRepository = graphRepository;
        this.objectMapper = objectMapper;
    }

    public ProjectGraphSummary rebuildGraph(Long projectId) {
        Project project = projectService.getProject(projectId);
        ProjectProfile profile = profileService.getProfile(projectId);
        Instant now = Instant.now();
        GraphAccumulator graph = new GraphAccumulator(projectId, now);
        List<String> warnings = new ArrayList<>(profile.warnings());

        Optional<CodeMap> codeMap = readCodeMap(project, warnings);
        codeMap.ifPresent(graph::addCodeMap);
        graph.addProfile(profile);

        graphRepository.replaceProjectGraph(projectId, graph.nodes(), graph.edges());
        String status = hasDegradedSource(warnings) ? "degraded" : "ready";
        return summary(projectId, status, warnings, graph.nodes(), graph.edges(), now);
    }

    public ProjectGraphSummary getSummary(Long projectId) {
        projectService.getProject(projectId);
        List<ProjectGraphNode> nodes = graphRepository.findNodesByProjectId(projectId);
        List<ProjectGraphEdge> edges = graphRepository.findEdgesByProjectId(projectId);
        String status = nodes.isEmpty() ? "empty" : "ready";
        List<String> warnings = nodes.isEmpty()
                ? List.of("Project graph is empty; call graph build service before querying relationships.")
                : List.of();
        Instant updatedAt = latestUpdatedAt(nodes, edges).orElse(Instant.now());
        return summary(projectId, status, warnings, nodes, edges, updatedAt);
    }

    public ProjectGraphNeighborhood neighbors(Long projectId, String stableKey, String sourcePath, Integer limit) {
        projectService.getProject(projectId);
        List<ProjectGraphNode> nodes = graphRepository.findNodesByProjectId(projectId);
        List<ProjectGraphEdge> edges = graphRepository.findEdgesByProjectId(projectId);
        Map<String, ProjectGraphNode> nodesByKey = nodes.stream()
                .collect(Collectors.toMap(ProjectGraphNode::stableKey, node -> node, (left, right) -> left, LinkedHashMap::new));
        List<String> warnings = new ArrayList<>();
        List<ProjectGraphNode> seedNodes = seedNodes(nodes, stableKey, sourcePath);
        if (seedNodes.isEmpty()) {
            warnings.add("No graph node matched the supplied seed.");
            return new ProjectGraphNeighborhood(projectId, List.of(), List.of(), List.of(), warnings);
        }
        Set<String> seedKeys = seedNodes.stream()
                .map(ProjectGraphNode::stableKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int safeLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 100));
        List<ProjectGraphEdge> matchedEdges = new ArrayList<>();
        LinkedHashMap<String, ProjectGraphNode> neighborsByKey = new LinkedHashMap<>();
        for (ProjectGraphEdge edge : edges) {
            boolean fromSeed = seedKeys.contains(edge.fromNodeKey());
            boolean toSeed = seedKeys.contains(edge.toNodeKey());
            if (!fromSeed && !toSeed) {
                continue;
            }
            matchedEdges.add(edge);
            String neighborKey = fromSeed ? edge.toNodeKey() : edge.fromNodeKey();
            ProjectGraphNode neighbor = nodesByKey.get(neighborKey);
            if (neighbor != null && !seedKeys.contains(neighbor.stableKey())) {
                neighborsByKey.putIfAbsent(neighbor.stableKey(), neighbor);
            }
            if (matchedEdges.size() >= safeLimit) {
                break;
            }
        }
        return new ProjectGraphNeighborhood(
                projectId,
                seedNodes,
                neighborsByKey.values().stream().toList(),
                matchedEdges,
                warnings
        );
    }

    private Optional<CodeMap> readCodeMap(Project project, List<String> warnings) {
        Path root = Path.of(project.rootPath()).toAbsolutePath().normalize();
        Path codeMapPath = root.resolve(CODE_MAP_PATH).toAbsolutePath().normalize();
        if (!codeMapPath.startsWith(root) || !Files.isRegularFile(codeMapPath)) {
            warnings.add("Missing .ai/code-map.json; graph uses ProjectProfile facts only.");
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(codeMapPath), CodeMap.class));
        } catch (IOException e) {
            warnings.add(".ai/code-map.json could not be parsed; graph uses ProjectProfile facts only.");
            return Optional.empty();
        }
    }

    private List<ProjectGraphNode> seedNodes(List<ProjectGraphNode> nodes, String stableKey, String sourcePath) {
        if (stableKey != null && !stableKey.isBlank()) {
            return nodes.stream()
                    .filter(node -> stableKey.trim().equals(node.stableKey()))
                    .toList();
        }
        if (sourcePath != null && !sourcePath.isBlank()) {
            String normalized = sourcePath.trim();
            return nodes.stream()
                    .filter(node -> normalized.equals(node.sourcePath()))
                    .toList();
        }
        return List.of();
    }

    private ProjectGraphSummary summary(
            Long projectId,
            String status,
            List<String> warnings,
            List<ProjectGraphNode> nodes,
            List<ProjectGraphEdge> edges,
            Instant updatedAt
    ) {
        return new ProjectGraphSummary(
                projectId,
                status,
                nodes.size(),
                edges.size(),
                countBy(nodes.stream().map(ProjectGraphNode::nodeType).toList()),
                countBy(edges.stream().map(ProjectGraphEdge::edgeType).toList()),
                warnings.stream().distinct().toList(),
                updatedAt
        );
    }

    private Map<String, Integer> countBy(List<String> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        values.stream().sorted().forEach(value -> counts.merge(value, 1, Integer::sum));
        return counts;
    }

    private Optional<Instant> latestUpdatedAt(List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
        return java.util.stream.Stream.concat(
                        nodes.stream().map(ProjectGraphNode::updatedAt),
                        edges.stream().map(ProjectGraphEdge::updatedAt)
                )
                .max(Comparator.naturalOrder());
    }

    private boolean hasDegradedSource(List<String> warnings) {
        return warnings.stream().anyMatch(warning ->
                warning.startsWith("Missing")
                        || warning.startsWith("No context")
                        || warning.startsWith("No indexed")
                        || warning.startsWith("Indexed knowledge")
                        || warning.contains("could not be parsed"));
    }

    private static class GraphAccumulator {

        private final Long projectId;
        private final Instant now;
        private final Map<String, ProjectGraphNode> nodes = new LinkedHashMap<>();
        private final Map<String, ProjectGraphEdge> edges = new LinkedHashMap<>();
        private final Map<String, String> uniqueSymbolKeysByName = new LinkedHashMap<>();
        private final Set<String> ambiguousSymbolNames = new LinkedHashSet<>();

        GraphAccumulator(Long projectId, Instant now) {
            this.projectId = projectId;
            this.now = now;
        }

        void addCodeMap(CodeMap codeMap) {
            addNode("file", fileKey(CODE_MAP_PATH), "code-map.json", CODE_MAP_PATH, EvidenceType.CODE_MAP);
            safeList(codeMap.files()).forEach(this::addFileEntry);
            safeList(codeMap.modules()).forEach(this::addModule);
            safeList(codeMap.symbols()).forEach(this::addSymbol);
            safeList(codeMap.entrypoints()).forEach(this::addEntrypoint);
            safeList(codeMap.endpoints()).forEach(this::addEndpoint);
            safeList(codeMap.configKeys()).forEach(this::addConfigKey);
            safeList(codeMap.sqlHints()).forEach(hint -> addRoutingHint(hint, "sql_hint", EvidenceType.SQL_SCHEMA, "SQL"));
            safeList(codeMap.mapperHints()).forEach(hint -> addRoutingHint(hint, "mapper_hint", EvidenceType.MAPPER, "mapper"));
            safeList(codeMap.entityHints()).forEach(hint -> addRoutingHint(hint, "entity_hint", EvidenceType.SQL_SCHEMA, "entity"));
            safeList(codeMap.testRelations()).forEach(this::addTestRelation);
            safeList(codeMap.dependencyEdges()).forEach(this::addDependencyEdge);
        }

        void addProfile(ProjectProfile profile) {
            for (ProjectProfileFact fact : safeList(profile.facts())) {
                ProjectProfileSourceReference source = firstSource(fact);
                String factKey = profileFactKey(fact);
                EvidenceType evidenceType = evidenceTypeOrDefault(source, EvidenceType.GENERATED_DOC);
                addNodeWithReference("profile_fact", factKey, fact.factType() + ": " + fact.name(), source, evidenceType);
                for (ProjectProfileSourceReference reference : safeList(fact.sourceReferences())) {
                    String sourceNodeKey = sourceNodeKey(reference, fact);
                    EvidenceType referenceEvidenceType = evidenceTypeOrDefault(reference, EvidenceType.GENERATED_DOC);
                    addNodeWithReference(sourceNodeType(reference, fact), sourceNodeKey, sourceLabel(reference), reference, referenceEvidenceType);
                    addEdge("supported_by", factKey, sourceNodeKey, "supported by " + reference.sourcePath(), reference, referenceEvidenceType);
                }
            }
        }

        private void addModule(CodeModule module) {
            String moduleKey = moduleKey(module.name());
            addNode("module", moduleKey, module.name(), CODE_MAP_PATH, EvidenceType.CODE_MAP);
            addEdge("described_by", moduleKey, fileKey(CODE_MAP_PATH), "described by code-map", CODE_MAP_PATH, EvidenceType.CODE_MAP);
        }

        private void addFileEntry(CodeMapFileEntry file) {
            if (file == null || isBlank(file.path())) {
                return;
            }
            String fileKey = fileKey(file.path());
            EvidenceType evidenceType = evidenceTypeForFile(file);
            addNode("file", fileKey, fileLabel(file.path()), file.path(), evidenceType);
            if (!isBlank(file.module())) {
                String moduleKey = moduleKey(file.module());
                addNode("module", moduleKey, file.module(), CODE_MAP_PATH, EvidenceType.CODE_MAP);
                addEdge("contains", moduleKey, fileKey, "Code Map file module: " + file.module(), file.path(), EvidenceType.CODE_MAP);
            }
            safeList(file.roles()).forEach(role -> addFileRole(file.path(), role));
        }

        private void addFileRole(String filePath, String role) {
            if (isBlank(filePath) || isBlank(role)) {
                return;
            }
            String roleKey = fileRoleKey(role);
            addNode("file_role", roleKey, role, CODE_MAP_PATH, EvidenceType.CODE_MAP);
            addEdge("has_role", fileKey(filePath), roleKey, "Code Map file role: " + role, filePath, EvidenceType.CODE_MAP);
        }

        private void addSymbol(CodeSymbol symbol) {
            String fileKey = fileKey(symbol.file());
            String symbolKey = symbolKey(symbol);
            addNode("file", fileKey, fileLabel(symbol.file()), symbol.file(), EvidenceType.SERVICE_CODE);
            addNode("symbol", symbolKey, symbol.name(), symbol.file(), EvidenceType.SERVICE_CODE);
            rememberSymbolKey(symbol.name(), symbolKey);
            addEdge("declares", fileKey, symbolKey, "declares " + symbol.name(), symbol.file(), EvidenceType.SERVICE_CODE);
            if (!isBlank(symbol.module())) {
                String moduleKey = moduleKey(symbol.module());
                addNode("module", moduleKey, symbol.module(), CODE_MAP_PATH, EvidenceType.CODE_MAP);
                addEdge("contains", moduleKey, fileKey, "contains " + symbol.file(), symbol.file(), EvidenceType.CODE_MAP);
                addEdge("belongs_to", symbolKey, moduleKey, "belongs to " + symbol.module(), symbol.file(), EvidenceType.CODE_MAP);
            }
        }

        private void addEntrypoint(CodeEntrypoint entrypoint) {
            String fileKey = fileKey(entrypoint.file());
            String entrypointKey = entrypointKey(entrypoint);
            addNode("file", fileKey, fileLabel(entrypoint.file()), entrypoint.file(), EvidenceType.API_CONTROLLER);
            addNode("entrypoint", entrypointKey, entrypoint.type() + ": " + entrypoint.file(), entrypoint.file(), EvidenceType.API_CONTROLLER);
            addEdge("entrypoint_in_file", entrypointKey, fileKey, "entrypoint in file", entrypoint.file(), EvidenceType.API_CONTROLLER);
        }

        private void addEndpoint(CodeEndpoint endpoint) {
            String endpointKey = endpointKey(endpoint);
            String fileKey = fileKey(endpoint.file());
            String symbolKey = symbolKey(endpoint.file(), endpoint.className());
            addNode("endpoint", endpointKey, endpoint.httpMethod() + " " + endpoint.path(), endpoint.file(), EvidenceType.API_CONTROLLER);
            addNode("file", fileKey, fileLabel(endpoint.file()), endpoint.file(), EvidenceType.API_CONTROLLER);
            addNode("symbol", symbolKey, endpoint.className(), endpoint.file(), EvidenceType.API_CONTROLLER);
            rememberSymbolKey(endpoint.className(), symbolKey);
            addEdge("handled_by", endpointKey, symbolKey, "handled by " + endpoint.className(), endpoint.file(), EvidenceType.API_CONTROLLER);
            addEdge("defined_in", endpointKey, fileKey, "defined in " + endpoint.file(), endpoint.file(), EvidenceType.API_CONTROLLER);
            if (!isBlank(endpoint.module())) {
                String moduleKey = moduleKey(endpoint.module());
                addNode("module", moduleKey, endpoint.module(), CODE_MAP_PATH, EvidenceType.CODE_MAP);
                addEdge("belongs_to", endpointKey, moduleKey, "belongs to " + endpoint.module(), endpoint.file(), EvidenceType.CODE_MAP);
            }
        }

        private void addConfigKey(CodeMapConfigKey configKey) {
            if (configKey == null || isBlank(configKey.key()) || isBlank(configKey.file())) {
                return;
            }
            String fileKey = fileKey(configKey.file());
            String configNodeKey = configKey(configKey);
            addNode("file", fileKey, fileLabel(configKey.file()), configKey.file(), EvidenceType.CONFIG);
            addNode("config_key", configNodeKey, configKey.key(), configKey.file(), EvidenceType.CONFIG);
            addEdge(
                    "configured_in",
                    configNodeKey,
                    fileKey,
                    "Code Map config key: " + configKey.key() + " in " + configKey.file(),
                    configKey.file(),
                    EvidenceType.CONFIG
            );
        }

        private void addRoutingHint(
                CodeMapRoutingHint hint,
                String nodeType,
                EvidenceType evidenceType,
                String sourceLabel
        ) {
            if (hint == null || isBlank(hint.name()) || isBlank(hint.file())) {
                return;
            }
            String fileKey = fileKey(hint.file());
            String hintKey = routingHintKey(nodeType, hint);
            String hintLabel = routingHintLabel(hint);
            addNode("file", fileKey, fileLabel(hint.file()), hint.file(), evidenceType);
            addNode(nodeType, hintKey, hintLabel, hint.file(), evidenceType);
            addEdge(
                    "hinted_by",
                    hintKey,
                    fileKey,
                    "Code Map " + sourceLabel + " hint: " + hintLabel + " in " + hint.file(),
                    hint.file(),
                    evidenceType
            );
        }

        private void addTestRelation(CodeMapTestRelation relation) {
            if (relation == null || isBlank(relation.testFile()) || isBlank(relation.targetFile())) {
                return;
            }
            String testFileKey = fileKey(relation.testFile());
            String targetFileKey = fileKey(relation.targetFile());
            addNode("file", testFileKey, fileLabel(relation.testFile()), relation.testFile(), EvidenceType.TEST);
            addNode("file", targetFileKey, fileLabel(relation.targetFile()), relation.targetFile(), EvidenceType.SERVICE_CODE);
            addEdge(
                    "tests",
                    testFileKey,
                    targetFileKey,
                    "Code Map test relation: " + relationLabel(relation),
                    relation.testFile(),
                    EvidenceType.TEST
            );
        }

        private void addDependencyEdge(CodeMapDependencyEdge dependency) {
            if (dependency == null) {
                return;
            }
            String fromNodeKey = dependencyNodeKey(dependency.fromFile(), dependency.fromSymbol());
            String toNodeKey = dependencyNodeKey(dependency.toFile(), dependency.toSymbol());
            if (isBlank(fromNodeKey) || isBlank(toNodeKey)) {
                return;
            }
            String edgeType = isBlank(dependency.edgeType()) ? "depends_on" : dependency.edgeType();
            addEdge(
                    edgeType,
                    fromNodeKey,
                    toNodeKey,
                    "Code Map dependency edge: " + edgeType,
                    isBlank(dependency.fromFile()) ? CODE_MAP_PATH : dependency.fromFile(),
                    EvidenceType.CODE_MAP
            );
        }

        private String dependencyNodeKey(String filePath, String symbolName) {
            if (!isBlank(filePath)) {
                String fileKey = fileKey(filePath);
                addNode("file", fileKey, fileLabel(filePath), filePath, EvidenceType.SERVICE_CODE);
                if (isBlank(symbolName)) {
                    return fileKey;
                }
                String symbolKey = symbolKey(filePath, symbolName);
                addNode("symbol", symbolKey, symbolName, filePath, EvidenceType.SERVICE_CODE);
                rememberSymbolKey(symbolName, symbolKey);
                addEdge("declares", fileKey, symbolKey, "declares " + symbolName, filePath, EvidenceType.SERVICE_CODE);
                return symbolKey;
            }
            if (isBlank(symbolName)) {
                return "";
            }
            Optional<String> knownSymbolKey = knownSymbolKey(symbolName);
            if (knownSymbolKey.isPresent()) {
                return knownSymbolKey.get();
            }
            String symbolRefKey = symbolRefKey(symbolName);
            addNode("symbol_ref", symbolRefKey, symbolName, CODE_MAP_PATH, EvidenceType.CODE_MAP);
            return symbolRefKey;
        }

        private void addNode(String nodeType, String stableKey, String label, String sourcePath, EvidenceType evidenceType) {
            nodes.putIfAbsent(stableKey, new ProjectGraphNode(
                    null,
                    projectId,
                    nodeType,
                    stableKey,
                    label,
                    sourcePath,
                    evidenceType.canonicalName(),
                    evidenceType.sourceKind().value(),
                    evidenceType.sourceReliability().value(),
                    now,
                    now
            ));
        }

        private void addNodeWithReference(
                String nodeType,
                String stableKey,
                String label,
                ProjectProfileSourceReference source,
                EvidenceType fallback
        ) {
            EvidenceType evidenceType = evidenceTypeOrDefault(source, fallback);
            nodes.putIfAbsent(stableKey, new ProjectGraphNode(
                    null,
                    projectId,
                    nodeType,
                    stableKey,
                    label,
                    source == null ? null : source.sourcePath(),
                    evidenceType.canonicalName(),
                    source == null ? evidenceType.sourceKind().value() : source.sourceKind(),
                    source == null ? evidenceType.sourceReliability().value() : source.sourceReliability(),
                    now,
                    now
            ));
        }

        private void addEdge(
                String edgeType,
                String fromNodeKey,
                String toNodeKey,
                String label,
                String sourcePath,
                EvidenceType evidenceType
        ) {
            String stableKey = edgeKey(edgeType, fromNodeKey, toNodeKey);
            edges.putIfAbsent(stableKey, new ProjectGraphEdge(
                    null,
                    projectId,
                    edgeType,
                    stableKey,
                    fromNodeKey,
                    toNodeKey,
                    label,
                    sourcePath,
                    evidenceType.canonicalName(),
                    evidenceType.sourceKind().value(),
                    evidenceType.sourceReliability().value(),
                    now,
                    now
            ));
        }

        private void addEdge(
                String edgeType,
                String fromNodeKey,
                String toNodeKey,
                String label,
                ProjectProfileSourceReference source,
                EvidenceType fallback
        ) {
            EvidenceType evidenceType = evidenceTypeOrDefault(source, fallback);
            String stableKey = edgeKey(edgeType, fromNodeKey, toNodeKey);
            edges.putIfAbsent(stableKey, new ProjectGraphEdge(
                    null,
                    projectId,
                    edgeType,
                    stableKey,
                    fromNodeKey,
                    toNodeKey,
                    label,
                    source == null ? null : source.sourcePath(),
                    evidenceType.canonicalName(),
                    source == null ? evidenceType.sourceKind().value() : source.sourceKind(),
                    source == null ? evidenceType.sourceReliability().value() : source.sourceReliability(),
                    now,
                    now
            ));
        }

        List<ProjectGraphNode> nodes() {
            return nodes.values().stream()
                    .sorted(Comparator.comparing(ProjectGraphNode::nodeType).thenComparing(ProjectGraphNode::stableKey))
                    .toList();
        }

        List<ProjectGraphEdge> edges() {
            return edges.values().stream()
                    .sorted(Comparator.comparing(ProjectGraphEdge::edgeType).thenComparing(ProjectGraphEdge::stableKey))
                    .toList();
        }

        private ProjectProfileSourceReference firstSource(ProjectProfileFact fact) {
            return safeList(fact.sourceReferences()).stream().findFirst().orElse(null);
        }

        private Optional<EvidenceType> normalizedEvidenceType(ProjectProfileSourceReference source) {
            return source == null ? Optional.empty() : EvidenceType.normalize(source.evidenceType());
        }

        private EvidenceType evidenceTypeOrDefault(ProjectProfileSourceReference source, EvidenceType fallback) {
            return normalizedEvidenceType(source).orElse(fallback);
        }

        private String sourceNodeType(ProjectProfileSourceReference reference, ProjectProfileFact fact) {
            if ("context_asset".equals(fact.factType())) {
                return "context_asset";
            }
            if (reference != null && isContextAssetPath(reference.sourcePath())) {
                return "context_asset";
            }
            return "file";
        }

        private String sourceNodeKey(ProjectProfileSourceReference reference, ProjectProfileFact fact) {
            String sourcePath = reference == null ? fact.name() : reference.sourcePath();
            if ("context_asset".equals(fact.factType()) || isContextAssetPath(sourcePath)) {
                return contextAssetKey(sourcePath);
            }
            return fileKey(sourcePath);
        }

        private String sourceLabel(ProjectProfileSourceReference reference) {
            if (reference == null || isBlank(reference.sourcePath())) {
                return "unknown source";
            }
            return fileLabel(reference.sourcePath());
        }

        private boolean isContextAssetPath(String sourcePath) {
            if (isBlank(sourcePath)) {
                return false;
            }
            return sourcePath.equals("AGENTS.md")
                    || sourcePath.startsWith(".ai/")
                    || sourcePath.endsWith(".md");
        }

        private <T> List<T> safeList(List<T> values) {
            return values == null ? List.of() : values;
        }

        private String profileFactKey(ProjectProfileFact fact) {
            return "profile_fact:" + fact.factType() + ":" + fact.name();
        }

        private String moduleKey(String moduleName) {
            return "module:" + moduleName;
        }

        private String fileKey(String filePath) {
            return "file:" + filePath;
        }

        private String contextAssetKey(String sourcePath) {
            return "context_asset:" + sourcePath;
        }

        private String fileRoleKey(String role) {
            return "file_role:" + role;
        }

        private String configKey(CodeMapConfigKey configKey) {
            return "config_key:" + configKey.file() + "#" + configKey.key();
        }

        private String routingHintKey(String nodeType, CodeMapRoutingHint hint) {
            String kind = isBlank(hint.kind()) ? "hint" : hint.kind();
            return nodeType + ":" + hint.file() + "#" + kind + ":" + hint.name();
        }

        private String routingHintLabel(CodeMapRoutingHint hint) {
            return isBlank(hint.kind()) ? hint.name() : hint.kind() + ": " + hint.name();
        }

        private String relationLabel(CodeMapTestRelation relation) {
            return isBlank(relation.relationType()) ? "test covers target" : relation.relationType();
        }

        private String symbolRefKey(String symbolName) {
            return "symbol_ref:" + symbolName;
        }

        private void rememberSymbolKey(String symbolName, String symbolKey) {
            if (isBlank(symbolName) || isBlank(symbolKey)) {
                return;
            }
            if (ambiguousSymbolNames.contains(symbolName)) {
                return;
            }
            String existing = uniqueSymbolKeysByName.putIfAbsent(symbolName, symbolKey);
            if (existing != null && !existing.equals(symbolKey)) {
                uniqueSymbolKeysByName.remove(symbolName);
                ambiguousSymbolNames.add(symbolName);
            }
        }

        private Optional<String> knownSymbolKey(String symbolName) {
            if (isBlank(symbolName) || ambiguousSymbolNames.contains(symbolName)) {
                return Optional.empty();
            }
            return Optional.ofNullable(uniqueSymbolKeysByName.get(symbolName));
        }

        private String symbolKey(CodeSymbol symbol) {
            return symbolKey(symbol.file(), symbol.name());
        }

        private String symbolKey(String filePath, String className) {
            return "symbol:" + filePath + "#" + className;
        }

        private String endpointKey(CodeEndpoint endpoint) {
            return "endpoint:" + endpoint.httpMethod() + " " + endpoint.path();
        }

        private String entrypointKey(CodeEntrypoint entrypoint) {
            return "entrypoint:" + entrypoint.file();
        }

        private String edgeKey(String edgeType, String fromNodeKey, String toNodeKey) {
            return edgeType + ":" + fromNodeKey + "->" + toNodeKey;
        }

        private EvidenceType evidenceTypeForFile(CodeMapFileEntry file) {
            String kind = normalized(file.kind());
            Set<String> roles = safeList(file.roles()).stream()
                    .filter(role -> !isBlank(role))
                    .map(this::normalized)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (kind.equals("configuration") || roles.contains("configuration")) {
                return EvidenceType.CONFIG;
            }
            if (kind.equals("database_schema") || roles.contains("sql") || roles.contains("database-schema")) {
                return EvidenceType.SQL_SCHEMA;
            }
            if (kind.equals("mapper") || roles.contains("mapper") || roles.contains("data-access")) {
                return EvidenceType.MAPPER;
            }
            if (kind.equals("test") || roles.contains("test")) {
                return EvidenceType.TEST;
            }
            if (roles.contains("controller") || roles.contains("endpoint")) {
                return EvidenceType.API_CONTROLLER;
            }
            if (roles.contains("entity") || roles.contains("domain-entity")) {
                return EvidenceType.SQL_SCHEMA;
            }
            if (kind.equals("documentation")) {
                return file.path() != null && file.path().startsWith(".ai/generated/")
                        ? EvidenceType.GENERATED_DOC
                        : EvidenceType.MANUAL_DOC;
            }
            return EvidenceType.SERVICE_CODE;
        }

        private String normalized(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }

        private String fileLabel(String filePath) {
            if (isBlank(filePath)) {
                return "unknown file";
            }
            int slash = filePath.lastIndexOf('/');
            return slash < 0 ? filePath : filePath.substring(slash + 1);
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
