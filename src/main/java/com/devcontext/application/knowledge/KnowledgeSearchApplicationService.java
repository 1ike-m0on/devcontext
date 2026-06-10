package com.devcontext.application.knowledge;

import com.devcontext.application.memory.ObservationCaptureService;
import com.devcontext.common.error.ApiException;
import com.devcontext.domain.knowledge.EmbeddingVector;
import com.devcontext.domain.knowledge.KeywordSearchHit;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import com.devcontext.domain.knowledge.RetrievalRecord;
import com.devcontext.domain.knowledge.VectorQuery;
import com.devcontext.domain.knowledge.VectorSearchHit;
import com.devcontext.ports.knowledge.EmbeddingClient;
import com.devcontext.ports.knowledge.KeywordSearchEngine;
import com.devcontext.ports.knowledge.KnowledgeChunkRepository;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import com.devcontext.ports.knowledge.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private List<KnowledgeSearchResult> fuse(List<KeywordSearchHit> keywordHits, List<VectorSearchHit> vectorHits, int topK, KnowledgeQueryPlan queryPlan) {
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

        return candidates.values().stream()
                .map(candidate -> candidate.toResult(queryPlan, evidenceClassifier))
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

    private static class FusedCandidate {

        private final KnowledgeChunkView view;
        private double keywordScore;
        private double vectorScore;

        FusedCandidate(KnowledgeChunkView view) {
            this.view = view;
        }

        KnowledgeSearchResult toResult(KnowledgeQueryPlan queryPlan, KnowledgeEvidenceClassifier evidenceClassifier) {
            List<KnowledgeEvidenceType> evidenceTypes = evidenceClassifier.classify(view);
            double fusedScore = keywordScore * 0.55
                    + vectorScore * 0.45
                    + evidenceBoost(queryPlan, evidenceTypes, view)
                    - genericDocPenalty(queryPlan, evidenceTypes, view);
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
                    fusedScore,
                    evidenceTypes
            );
        }

        private double evidenceBoost(KnowledgeQueryPlan queryPlan, List<KnowledgeEvidenceType> evidenceTypes, KnowledgeChunkView view) {
            double boost = 0;
            for (KnowledgeEvidenceType requiredType : queryPlan.requiredEvidenceTypes()) {
                if (evidenceTypes.contains(requiredType)) {
                    boost = Math.max(boost, 0.55);
                }
            }
            List<KnowledgeEvidenceType> preferred = queryPlan.preferredEvidenceTypes();
            for (int i = 0; i < preferred.size(); i++) {
                KnowledgeEvidenceType preferredType = preferred.get(i);
                if (evidenceTypes.contains(preferredType)) {
                    boost = Math.max(boost, Math.max(0.12, 0.40 - i * 0.05));
                }
            }
            if (evidenceTypes.contains(KnowledgeEvidenceType.CODE_MAP) && queryPlan.preferredEvidenceTypes().contains(KnowledgeEvidenceType.CODE_MAP)) {
                boost = Math.max(boost, 0.25);
            }
            return boost + specificEvidencePathBoost(view, evidenceTypes);
        }

        private double specificEvidencePathBoost(KnowledgeChunkView view, List<KnowledgeEvidenceType> evidenceTypes) {
            String path = view.document().filePath().toLowerCase(java.util.Locale.ROOT);
            if (evidenceTypes.contains(KnowledgeEvidenceType.SQL_SCHEMA) && path.endsWith(".sql")) {
                return 0.08;
            }
            if (evidenceTypes.contains(KnowledgeEvidenceType.CACHE) && path.endsWith(".lua")) {
                return 0.08;
            }
            if (evidenceTypes.contains(KnowledgeEvidenceType.OBSERVABILITY)
                    && (path.contains("prometheus") || path.contains("grafana") || path.contains("metrics"))) {
                return 0.08;
            }
            return 0;
        }

        private double genericDocPenalty(KnowledgeQueryPlan queryPlan, List<KnowledgeEvidenceType> evidenceTypes, KnowledgeChunkView view) {
            if (queryPlan.preferredEvidenceTypes().isEmpty()) {
                return 0;
            }
            boolean matchesPreferred = evidenceTypes.stream().anyMatch(queryPlan.preferredEvidenceTypes()::contains);
            if (matchesPreferred) {
                return todoPenalty(view);
            }
            boolean onlyGenericDoc = evidenceTypes.stream().allMatch(type ->
                    type == KnowledgeEvidenceType.GENERATED_DOC || type == KnowledgeEvidenceType.MANUAL_DOC);
            return onlyGenericDoc ? 0.20 + todoPenalty(view) : todoPenalty(view);
        }

        private double todoPenalty(KnowledgeChunkView view) {
            String text = (view.chunk().headingPath() + "\n" + view.chunk().content()).toLowerCase(java.util.Locale.ROOT);
            return text.contains("todo") || text.contains("待补充") ? 0.12 : 0;
        }
    }
}
