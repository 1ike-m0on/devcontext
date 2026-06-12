package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.ports.graph.ProjectGraphRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class ProjectGraphReviewContextProvider implements ReviewContextProvider {

    private static final int PRIORITY = 810;
    private static final int MAX_SEED_NODES = 6;
    private static final int MAX_RELATIONSHIPS = 18;
    private static final int MAX_CONTENT_CHARS = 3_200;
    private static final Set<String> REVIEW_NODE_TYPES = Set.of(
            "file",
            "module",
            "symbol",
            "endpoint",
            "entrypoint",
            "profile_fact",
            "context_asset"
    );
    private static final Map<String, Integer> NODE_TYPE_PRIORITY = Map.of(
            "endpoint", 100,
            "symbol", 95,
            "module", 90,
            "file", 80,
            "entrypoint", 75,
            "profile_fact", 70,
            "context_asset", 60
    );

    private final ProjectGraphRepository graphRepository;

    public ProjectGraphReviewContextProvider(ProjectGraphRepository graphRepository) {
        this.graphRepository = graphRepository;
    }

    @Override
    public boolean supports(ReviewContextRequest request) {
        return request.project() != null
                && request.project().id() != null
                && request.diff() != null
                && request.diff().changedFiles() != null
                && !request.diff().changedFiles().isEmpty();
    }

    @Override
    public List<ContextItem> provide(ReviewContextRequest request) {
        List<String> touchedPaths = touchedPaths(request);
        if (touchedPaths.isEmpty()) {
            return List.of();
        }

        List<ProjectGraphNode> nodes = graphRepository.findNodesByProjectId(request.project().id());
        if (nodes.isEmpty()) {
            return List.of();
        }
        List<ProjectGraphNode> seedNodes = seedNodes(nodes, touchedPaths);
        if (seedNodes.isEmpty()) {
            return List.of();
        }

        List<ProjectGraphEdge> edges = graphRepository.findEdgesByProjectId(request.project().id());
        List<GraphRelation> relations = oneHopRelations(seedNodes, nodes, edges);
        if (relations.isEmpty()) {
            return List.of();
        }

        String content = renderGraphContext(touchedPaths, relations);
        return List.of(new ContextItem(
                null,
                null,
                request.project().id(),
                "PROJECT_GRAPH_NEIGHBORS",
                "ProjectGraph one-hop neighbors",
                content,
                "project-graph:" + request.project().id(),
                PRIORITY,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        ));
    }

    private List<String> touchedPaths(ReviewContextRequest request) {
        return request.diff().changedFiles().stream()
                .map(this::normalizePath)
                .filter(path -> !path.isBlank())
                .distinct()
                .toList();
    }

    private List<ProjectGraphNode> seedNodes(List<ProjectGraphNode> nodes, List<String> touchedPaths) {
        LinkedHashMap<String, ProjectGraphNode> seeds = new LinkedHashMap<>();
        for (String touchedPath : touchedPaths) {
            List<ProjectGraphNode> fileMatches = nodes.stream()
                    .filter(node -> isReviewNode(node))
                    .filter(node -> "file".equals(normalizeKey(node.nodeType())))
                    .filter(node -> matchesTouchedPath(node, touchedPath))
                    .sorted(Comparator.comparing(node -> valueOr(node.stableKey(), "")))
                    .toList();
            if (!fileMatches.isEmpty()) {
                fileMatches.forEach(node -> seeds.putIfAbsent(node.stableKey(), node));
                continue;
            }
            nodes.stream()
                    .filter(this::isReviewNode)
                    .filter(node -> matchesTouchedPath(node, touchedPath))
                    .sorted(Comparator
                            .comparingInt((ProjectGraphNode node) -> nodeTypePriority(node)).reversed()
                            .thenComparing(node -> valueOr(node.label(), ""))
                            .thenComparing(node -> valueOr(node.stableKey(), "")))
                    .forEach(node -> seeds.putIfAbsent(node.stableKey(), node));
        }
        return seeds.values().stream()
                .limit(MAX_SEED_NODES)
                .toList();
    }

    private List<GraphRelation> oneHopRelations(
            List<ProjectGraphNode> seedNodes,
            List<ProjectGraphNode> nodes,
            List<ProjectGraphEdge> edges
    ) {
        Set<String> seedKeys = seedNodes.stream()
                .map(ProjectGraphNode::stableKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, ProjectGraphNode> nodesByKey = nodes.stream()
                .filter(this::isReviewNode)
                .collect(Collectors.toMap(
                        ProjectGraphNode::stableKey,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
        Map<String, ProjectGraphNode> seedByKey = seedNodes.stream()
                .collect(Collectors.toMap(
                        ProjectGraphNode::stableKey,
                        node -> node,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        LinkedHashMap<String, GraphRelation> relations = new LinkedHashMap<>();
        for (ProjectGraphEdge edge : edges) {
            boolean fromSeed = seedKeys.contains(edge.fromNodeKey());
            boolean toSeed = seedKeys.contains(edge.toNodeKey());
            if (!fromSeed && !toSeed) {
                continue;
            }
            ProjectGraphNode seed = seedByKey.get(fromSeed ? edge.fromNodeKey() : edge.toNodeKey());
            ProjectGraphNode neighbor = nodesByKey.get(fromSeed ? edge.toNodeKey() : edge.fromNodeKey());
            if (seed == null || neighbor == null || seedKeys.contains(neighbor.stableKey())) {
                continue;
            }
            String relationKey = seed.stableKey() + "|" + edge.stableKey() + "|" + neighbor.stableKey();
            relations.putIfAbsent(relationKey, new GraphRelation(seed, edge, neighbor, fromSeed));
        }

        return relations.values().stream()
                .sorted(Comparator
                        .comparingInt((GraphRelation relation) -> relationPriority(relation)).reversed()
                        .thenComparing(relation -> valueOr(relation.seed().sourcePath(), ""))
                        .thenComparing(relation -> valueOr(relation.edge().edgeType(), ""))
                        .thenComparing(relation -> valueOr(relation.neighbor().label(), "")))
                .limit(MAX_RELATIONSHIPS)
                .toList();
    }

    private String renderGraphContext(List<String> touchedPaths, List<GraphRelation> relations) {
        StringBuilder builder = new StringBuilder();
        builder.append("ProjectGraph one-hop neighbors for code review. Use as compact structure hints; do not override the diff, review rules, ProjectProfile facts, or review feedback memory.")
                .append(System.lineSeparator());
        builder.append("Touched paths: ")
                .append(String.join(", ", touchedPaths.stream().limit(8).toList()))
                .append(System.lineSeparator())
                .append(System.lineSeparator());
        builder.append("One-hop relations:").append(System.lineSeparator());

        ProjectGraphNode currentSeed = null;
        for (GraphRelation relation : relations) {
            if (currentSeed == null || !currentSeed.stableKey().equals(relation.seed().stableKey())) {
                currentSeed = relation.seed();
                builder.append("- Seed ")
                        .append(formatNode(currentSeed, 100))
                        .append(System.lineSeparator());
            }
            builder.append("  - ")
                    .append(relation.outgoing() ? "--" : "<--")
                    .append(valueOr(relation.edge().edgeType(), "related_to"))
                    .append(relation.outgoing() ? "--> " : "-- ")
                    .append(formatNode(relation.neighbor(), 110));
            if (relation.edge().sourcePath() != null && !relation.edge().sourcePath().isBlank()) {
                builder.append(" edgeSourcePath=").append(trim(normalizePath(relation.edge().sourcePath()), 100));
            }
            builder.append(System.lineSeparator());
            if (builder.length() >= MAX_CONTENT_CHARS) {
                builder.append("- Additional ProjectGraph relations omitted by review context budget.")
                        .append(System.lineSeparator());
                break;
            }
        }

        return builder.length() <= MAX_CONTENT_CHARS
                ? builder.toString()
                : builder.substring(0, MAX_CONTENT_CHARS) + System.lineSeparator() + "[truncated]";
    }

    private String formatNode(ProjectGraphNode node, int maxLabelLength) {
        StringBuilder builder = new StringBuilder();
        builder.append("[")
                .append(valueOr(node.nodeType(), "node"))
                .append("] ")
                .append(trim(valueOr(node.label(), valueOr(node.stableKey(), "unnamed")), maxLabelLength));
        if (node.sourcePath() != null && !node.sourcePath().isBlank()) {
            builder.append(" sourcePath=").append(trim(normalizePath(node.sourcePath()), 110));
        }
        return builder.toString();
    }

    private int relationPriority(GraphRelation relation) {
        return nodeTypePriority(relation.neighbor()) + edgeTypeBonus(relation.edge().edgeType());
    }

    private int edgeTypeBonus(String edgeType) {
        String normalized = normalizeKey(edgeType);
        return switch (normalized) {
            case "defined_in", "declares", "handled_by", "belongs_to", "contains" -> 12;
            case "supported_by", "entrypoint_in_file" -> 8;
            default -> 0;
        };
    }

    private int nodeTypePriority(ProjectGraphNode node) {
        return NODE_TYPE_PRIORITY.getOrDefault(normalizeKey(node.nodeType()), 10);
    }

    private boolean matchesTouchedPath(ProjectGraphNode node, String touchedPath) {
        String normalizedSourcePath = normalizePath(node.sourcePath());
        String normalizedStableKey = normalizePath(node.stableKey());
        String fileStableKey = "file:" + touchedPath;
        return touchedPath.equals(normalizedSourcePath)
                || fileStableKey.equals(normalizedStableKey);
    }

    private boolean isReviewNode(ProjectGraphNode node) {
        return node != null
                && node.stableKey() != null
                && REVIEW_NODE_TYPES.contains(normalizeKey(node.nodeType()));
    }

    private String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength - 3) + "...";
    }

    private int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record GraphRelation(
            ProjectGraphNode seed,
            ProjectGraphEdge edge,
            ProjectGraphNode neighbor,
            boolean outgoing
    ) {
    }
}
