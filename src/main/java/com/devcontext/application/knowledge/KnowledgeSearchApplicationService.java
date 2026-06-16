package com.devcontext.application.knowledge;

import com.devcontext.application.memory.ObservationCaptureService;
import com.devcontext.common.error.ApiException;
import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.KeywordSearchHit;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeFusionScore;
import com.devcontext.domain.knowledge.KnowledgeProjectContextSignal;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.graph.ProjectGraphNode;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.knowledge.RetrievalRecord;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import com.devcontext.ports.graph.ProjectGraphRepository;
import com.devcontext.ports.knowledge.EmbeddingClient;
import com.devcontext.ports.knowledge.KeywordSearchEngine;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import com.devcontext.ports.knowledge.VectorStore;
import com.devcontext.ports.profile.ProjectProfileRepository;
import com.devcontext.ports.project.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeSearchApplicationService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 20;

    private final KeywordSearchEngine keywordSearchEngine;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final KnowledgeChunkRepository chunkRepository;
    private final RetrievalRecordRepository retrievalRecordRepository;
    private final KnowledgeQueryPlanner queryPlanner;
    private final KnowledgeEvidenceClassifier evidenceClassifier;
    private final KnowledgeFusionScoringService fusionScoringService;
    private final ProjectRepository projectRepository;
    private final ProjectGraphRepository projectGraphRepository;
    private final ProjectProfileRepository projectProfileRepository;
    private final ObjectMapper objectMapper;
    private final ObservationCaptureService observationCaptureService;

    public KnowledgeSearchApplicationService(
            KeywordSearchEngine keywordSearchEngine,
            EmbeddingClient embeddingClient,
            VectorStore vectorStore,
            KnowledgeChunkRepository chunkRepository,
            RetrievalRecordRepository retrievalRecordRepository,
            KnowledgeQueryPlanner queryPlanner,
            KnowledgeEvidenceClassifier evidenceClassifier,
            KnowledgeFusionScoringService fusionScoringService,
            ProjectRepository projectRepository,
            ProjectGraphRepository projectGraphRepository,
            ProjectProfileRepository projectProfileRepository,
            ObjectMapper objectMapper,
            ObservationCaptureService observationCaptureService
    ) {
        this.keywordSearchEngine = keywordSearchEngine;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.chunkRepository = chunkRepository;
        this.retrievalRecordRepository = retrievalRecordRepository;
        this.queryPlanner = queryPlanner;
        this.evidenceClassifier = evidenceClassifier;
        this.fusionScoringService = fusionScoringService;
        this.projectRepository = projectRepository;
        this.projectGraphRepository = projectGraphRepository;
        this.projectProfileRepository = projectProfileRepository;
        this.objectMapper = objectMapper;
        this.observationCaptureService = observationCaptureService;
    }

    public KnowledgeSearchResponse search(KnowledgeSearchCommand command) {
        return search(command, null);
    }

    public KnowledgeSearchResponse search(KnowledgeSearchCommand command, Long runId) {
        String query = requireQuery(command.query());
        KnowledgeQueryPlan queryPlan = queryPlanner.plan(query);
        String rewrittenQuery = queryPlan.rewrittenQuery();
        int topK = normalizeTopK(command.topK());
        int candidateLimit = Math.min(MAX_TOP_K * 4, Math.max(topK * 4, topK));

        List<KeywordSearchHit> keywordHits = keywordSearchEngine.search(rewrittenQuery, command.sourceId(), candidateLimit);
        EmbeddingVector queryEmbedding = embeddingClient.embed(rewrittenQuery);
        List<VectorSearchHit> vectorHits = vectorStore.search(new VectorQuery(
                KnowledgeIndexApplicationService.VECTOR_COLLECTION,
                command.sourceId(),
                queryEmbedding,
                candidateLimit
        ));

        List<KnowledgeSearchResult> results = fuse(keywordHits, vectorHits, topK, queryPlan);
        RetrievalRecord record = retrievalRecordRepository.save(new RetrievalRecord(
                null,
                runId,
                query,
                rewrittenQuery,
                topK,
                writeJson(results),
                Instant.now()
        ));
        observationCaptureService.captureRetrievalRecord(record);
        return new KnowledgeSearchResponse(record.id(), query, rewrittenQuery, queryPlan, results);
    }

    private List<KnowledgeSearchResult> fuse(
            List<KeywordSearchHit> keywordHits,
            List<VectorSearchHit> vectorHits,
            int topK,
            KnowledgeQueryPlan queryPlan
    ) {
        double maxKeywordScore = keywordHits.stream().mapToDouble(KeywordSearchHit::score).max().orElse(0);
        double maxVectorScore = vectorHits.stream().mapToDouble(VectorSearchHit::score).max().orElse(0);
        Map<Long, Double> keywordScores = new LinkedHashMap<>();
        for (KeywordSearchHit hit : keywordHits) {
            keywordScores.put(hit.chunkId(), normalizeScore(hit.score(), maxKeywordScore));
        }

        Map<String, Double> vectorScoresByVectorId = new LinkedHashMap<>();
        for (VectorSearchHit hit : vectorHits) {
            vectorScoresByVectorId.put(hit.vectorId(), normalizeScore(hit.score(), maxVectorScore));
        }

        Map<Long, KnowledgeChunkView> keywordViews = chunkRepository.findViewsByChunkIds(keywordScores.keySet());
        Map<String, KnowledgeChunkView> vectorViews = chunkRepository.findViewsByVectorIds(vectorScoresByVectorId.keySet());
        Map<Long, FusedCandidate> candidates = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> entry : keywordScores.entrySet()) {
            KnowledgeChunkView view = keywordViews.get(entry.getKey());
            if (view != null) {
                candidates.computeIfAbsent(view.chunk().id(), id -> new FusedCandidate(view))
                        .keywordScore = entry.getValue();
            }
        }
        for (Map.Entry<String, Double> entry : vectorScoresByVectorId.entrySet()) {
            KnowledgeChunkView view = vectorViews.get(entry.getKey());
            if (view != null) {
                candidates.computeIfAbsent(view.chunk().id(), id -> new FusedCandidate(view))
                        .vectorScore = entry.getValue();
            }
        }

        Map<Long, ProjectContext> projectContextsBySourceId = new LinkedHashMap<>();
        return candidates.values().stream()
                .map(candidate -> candidate.toResult(queryPlan, projectContextsBySourceId))
                .sorted(Comparator.comparingDouble(KnowledgeSearchResult::fusedScore).reversed())
                .limit(topK)
                .toList();
    }

    private double normalizeScore(double score, double maxScore) {
        if (maxScore <= 0) {
            return 0;
        }
        return score / maxScore;
    }

    private String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new ApiException("KNOWLEDGE_QUERY_REQUIRED", "query is required", HttpStatus.BAD_REQUEST);
        }
        return query.trim();
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null) {
            return DEFAULT_TOP_K;
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new ApiException("KNOWLEDGE_TOP_K_INVALID", "topK must be between 1 and 20", HttpStatus.BAD_REQUEST);
        }
        return topK;
    }

    private String writeJson(List<KnowledgeSearchResult> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize retrieval results", e);
        }
    }

    private KnowledgeProjectContextSignal projectContextSignal(
            KnowledgeChunkView view,
            Map<Long, ProjectContext> projectContextsBySourceId
    ) {
        Long sourceId = view.source().id();
        ProjectContext context = projectContextsBySourceId.computeIfAbsent(
                sourceId,
                ignored -> projectContextForSource(view.source())
        );
        if (!context.hasProject()) {
            return KnowledgeProjectContextSignal.none();
        }
        return new KnowledgeProjectContextSignal(
                context.projectId(),
                graphMatches(view, context.graphNodes()),
                profileMatches(view, context.profile())
        );
    }

    private ProjectContext projectContextForSource(KnowledgeSource source) {
        Optional<Project> project = resolveProject(source);
        if (project.isEmpty()) {
            return ProjectContext.none();
        }
        Long projectId = project.get().id();
        return new ProjectContext(
                projectId,
                projectGraphRepository.findNodesByProjectId(projectId),
                projectProfileRepository.findByProjectId(projectId).orElse(null)
        );
    }

    private Optional<Project> resolveProject(KnowledgeSource source) {
        String storedSourceRoot = normalizeStoredAbsolutePath(source.rootPath());
        String sourceRoot = normalizePath(storedSourceRoot);
        if (sourceRoot.isBlank()) {
            return Optional.empty();
        }
        Optional<Project> exact = projectRepository.findByRootPath(storedSourceRoot);
        if (exact.isPresent()) {
            return exact;
        }
        return projectRepository.findAll().stream()
                .filter(project -> isSameOrChildPath(sourceRoot, normalizeAbsolutePath(project.rootPath())))
                .max(Comparator.comparingInt(project -> normalizeAbsolutePath(project.rootPath()).length()));
    }

    private List<String> graphMatches(KnowledgeChunkView view, List<ProjectGraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        String documentPath = normalizeRelativePath(view.document().filePath());
        String searchableText = searchableText(view);
        for (ProjectGraphNode node : nodes) {
            if (pathMatches(node.sourcePath(), documentPath)) {
                matches.add("source_path:" + documentPath);
            } else if (containsContextText(searchableText, node.label())) {
                matches.add("label:" + reasonValue(node.label()));
            } else if (containsContextText(searchableText, node.stableKey())) {
                matches.add("stable_key:" + reasonValue(node.stableKey()));
            }
            if (matches.size() >= 3) {
                break;
            }
        }
        return List.copyOf(matches);
    }

    private List<String> profileMatches(KnowledgeChunkView view, ProjectProfile profile) {
        if (profile == null || profile.facts() == null || profile.facts().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> matches = new LinkedHashSet<>();
        String documentPath = normalizeRelativePath(view.document().filePath());
        String searchableText = searchableText(view);
        for (ProjectProfileFact fact : profile.facts()) {
            for (ProjectProfileSourceReference reference : safeList(fact.sourceReferences())) {
                if (pathMatches(reference.sourcePath(), documentPath)) {
                    matches.add("source_path:" + documentPath);
                    break;
                }
            }
            if (containsContextText(searchableText, fact.name())) {
                matches.add("fact:" + reasonValue(fact.name()));
            }
            if (matches.size() >= 3) {
                break;
            }
        }
        return List.copyOf(matches);
    }

    private boolean pathMatches(String sourcePath, String documentPath) {
        String left = pathKey(sourcePath);
        String right = pathKey(documentPath);
        if (left.isBlank() || right.isBlank()) {
            return false;
        }
        return left.equals(right)
                || left.endsWith("/" + right)
                || right.endsWith("/" + left);
    }

    private boolean isSameOrChildPath(String sourceRoot, String projectRoot) {
        String source = pathKey(sourceRoot);
        String project = pathKey(projectRoot);
        return !source.isBlank() && !project.isBlank()
                && (source.equals(project) || source.startsWith(project + "/"));
    }

    private String searchableText(KnowledgeChunkView view) {
        return normalizeText(String.join("\n",
                safe(view.document().filePath()),
                safe(view.document().title()),
                safe(view.chunk().headingPath()),
                safe(view.chunk().content())
        ));
    }

    private boolean containsContextText(String searchableText, String contextText) {
        String needle = normalizeText(contextText);
        return needle.length() >= 4 && searchableText.contains(needle);
    }

    private String normalizeAbsolutePath(String value) {
        return normalizePath(normalizeStoredAbsolutePath(value));
    }

    private String normalizeStoredAbsolutePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (InvalidPathException e) {
            return value.trim();
        }
    }

    private String normalizeRelativePath(String value) {
        return normalizePath(value);
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

    private String normalizeText(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replace('\\', '/');
    }

    private String reasonValue(String value) {
        String normalized = normalizePath(value)
                .replace(',', ';')
                .replaceAll("\\s+", "_");
        if (normalized.length() > 80) {
            return normalized.substring(0, 80);
        }
        return normalized;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private class FusedCandidate {

        private final KnowledgeChunkView view;
        private double keywordScore;
        private double vectorScore;

        FusedCandidate(KnowledgeChunkView view) {
            this.view = view;
        }

        KnowledgeSearchResult toResult(KnowledgeQueryPlan queryPlan, Map<Long, ProjectContext> projectContextsBySourceId) {
            List<KnowledgeEvidenceType> evidenceTypes = evidenceClassifier.classify(view);
            KnowledgeProjectContextSignal projectContextSignal = projectContextSignal(view, projectContextsBySourceId);
            KnowledgeFusionScore fusionScore = fusionScoringService.score(
                    keywordScore,
                    vectorScore,
                    queryPlan,
                    evidenceTypes,
                    view,
                    projectContextSignal
            );
            return new KnowledgeSearchResult(
                    view.chunk().id(),
                    view.document().id(),
                    view.source().id(),
                    view.source().name(),
                    view.document().filePath(),
                    view.document().title(),
                    view.chunk().headingPath(),
                    view.chunk().content(),
                    keywordScore,
                    vectorScore,
                    fusionScore.fusedScore(),
                    evidenceTypes,
                    fusionScore.reasons()
            );
        }
    }

    private record ProjectContext(
            Long projectId,
            List<ProjectGraphNode> graphNodes,
            ProjectProfile profile
    ) {

        static ProjectContext none() {
            return new ProjectContext(null, List.of(), null);
        }

        ProjectContext {
            graphNodes = graphNodes == null ? List.of() : List.copyOf(graphNodes);
        }

        boolean hasProject() {
            return projectId != null;
        }
    }
}
