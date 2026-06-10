package com.devcontext.application.graph;

import com.devcontext.application.profile.ProjectProfileApplicationService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMap;
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

        GraphAccumulator(Long projectId, Instant now) {
            this.projectId = projectId;
            this.now = now;
        }

        void addCodeMap(CodeMap codeMap) {
            addNode("file", fileKey(CODE_MAP_PATH), "code-map.json", CODE_MAP_PATH, EvidenceType.CODE_MAP);
            safeList(codeMap.modules()).forEach(this::addModule);
            safeList(codeMap.symbols()).forEach(this::addSymbol);
            safeList(codeMap.entrypoints()).forEach(this::addEntrypoint);
            safeList(codeMap.endpoints()).forEach(this::addEndpoint);
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

        private void addSymbol(CodeSymbol symbol) {
            String fileKey = fileKey(symbol.file());
            String symbolKey = symbolKey(symbol);
            addNode("file", fileKey, fileLabel(symbol.file()), symbol.file(), EvidenceType.SERVICE_CODE);
            addNode("symbol", symbolKey, symbol.name(), symbol.file(), EvidenceType.SERVICE_CODE);
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
            addEdge("handled_by", endpointKey, symbolKey, "handled by " + endpoint.className(), endpoint.file(), EvidenceType.API_CONTROLLER);
            addEdge("defined_in", endpointKey, fileKey, "defined in " + endpoint.file(), endpoint.file(), EvidenceType.API_CONTROLLER);
            if (!isBlank(endpoint.module())) {
                String moduleKey = moduleKey(endpoint.module());
                addNode("module", moduleKey, endpoint.module(), CODE_MAP_PATH, EvidenceType.CODE_MAP);
                addEdge("belongs_to", endpointKey, moduleKey, "belongs to " + endpoint.module(), endpoint.file(), EvidenceType.CODE_MAP);
            }
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
