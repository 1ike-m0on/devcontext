package com.devcontext.application.knowledge;

import com.devcontext.domain.evidence.EvidenceSourceReliability;
import com.devcontext.domain.knowledge.KnowledgeChunkView;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeFusionScore;
import com.devcontext.domain.knowledge.KnowledgeProjectContextSignal;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeFusionScoringService {

    private static final double KEYWORD_WEIGHT = 0.55;
    private static final double VECTOR_WEIGHT = 0.45;
    private static final double REQUIRED_EVIDENCE_BOOST = 0.70;
    private static final double PREFERRED_EVIDENCE_BOOST = 0.34;
    private static final double PRIMARY_SOURCE_BOOST = 0.18;
    private static final double SECONDARY_SOURCE_BOOST = 0.08;
    private static final double SPECIFIC_ENGINEERING_BOOST = 0.12;
    private static final double PROJECT_GRAPH_CONTEXT_BOOST = 0.34;
    private static final double PROJECT_PROFILE_CONTEXT_BOOST = 0.26;
    private static final double GENERIC_DOC_PENALTY = 0.22;
    private static final double TODO_PENALTY = 0.14;

    public KnowledgeFusionScore score(
            double keywordScore,
            double vectorScore,
            KnowledgeQueryPlan queryPlan,
            List<KnowledgeEvidenceType> evidenceTypes,
            KnowledgeChunkView view
    ) {
        return score(keywordScore, vectorScore, queryPlan, evidenceTypes, view, KnowledgeProjectContextSignal.none());
    }

    public KnowledgeFusionScore score(
            double keywordScore,
            double vectorScore,
            KnowledgeQueryPlan queryPlan,
            List<KnowledgeEvidenceType> evidenceTypes,
            KnowledgeChunkView view,
            KnowledgeProjectContextSignal projectContextSignal
    ) {
        List<KnowledgeEvidenceType> safeEvidenceTypes = evidenceTypes == null ? List.of() : evidenceTypes;
        KnowledgeProjectContextSignal safeProjectContextSignal = projectContextSignal == null
                ? KnowledgeProjectContextSignal.none()
                : projectContextSignal;
        List<KnowledgeEvidenceType> required = queryPlan.requiredEvidenceTypes() == null
                ? List.of()
                : queryPlan.requiredEvidenceTypes();
        List<KnowledgeEvidenceType> preferred = queryPlan.preferredEvidenceTypes() == null
                ? List.of()
                : queryPlan.preferredEvidenceTypes();

        List<String> reasons = new ArrayList<>();
        double score = keywordScore * KEYWORD_WEIGHT + vectorScore * VECTOR_WEIGHT;
        reasons.add("base:keyword_vector");

        List<KnowledgeEvidenceType> matchedRequired = intersection(required, safeEvidenceTypes);
        if (!matchedRequired.isEmpty()) {
            score += REQUIRED_EVIDENCE_BOOST;
            reasons.add("required_evidence:" + names(matchedRequired));
        }

        List<KnowledgeEvidenceType> matchedPreferred = intersection(preferred, safeEvidenceTypes);
        if (!matchedPreferred.isEmpty()) {
            score += preferredBoost(preferred, matchedPreferred);
            reasons.add("preferred_evidence:" + names(matchedPreferred));
        }

        EvidenceSourceReliability reliability = bestReliability(safeEvidenceTypes);
        if (reliability == EvidenceSourceReliability.PRIMARY) {
            score += PRIMARY_SOURCE_BOOST;
            reasons.add("source_reliability:primary");
        } else if (reliability == EvidenceSourceReliability.SECONDARY) {
            score += SECONDARY_SOURCE_BOOST;
            reasons.add("source_reliability:secondary");
        } else if (reliability == EvidenceSourceReliability.DERIVED) {
            reasons.add("source_reliability:derived");
        }

        String specificityReason = specificEngineeringReason(view, safeEvidenceTypes);
        if (specificityReason != null) {
            score += SPECIFIC_ENGINEERING_BOOST;
            reasons.add("specific_engineering_evidence:" + specificityReason);
        }

        if (safeProjectContextSignal.hasGraphMatches()) {
            score += PROJECT_GRAPH_CONTEXT_BOOST;
            reasons.add("project_graph_context:" + join(safeProjectContextSignal.graphMatches()));
        }

        if (safeProjectContextSignal.hasProfileMatches()) {
            score += PROJECT_PROFILE_CONTEXT_BOOST;
            reasons.add("project_profile_context:" + join(safeProjectContextSignal.profileMatches()));
        }

        if (isGenericDocOnly(safeEvidenceTypes) && matchedRequired.isEmpty() && matchedPreferred.isEmpty()
                && (!required.isEmpty() || hasSpecificPreferredEvidence(preferred))) {
            score -= GENERIC_DOC_PENALTY;
            reasons.add("generic_doc_penalty");
        }

        if (containsTodoMarker(view)) {
            score -= TODO_PENALTY;
            reasons.add("todo_penalty");
        }

        return new KnowledgeFusionScore(score, List.copyOf(reasons));
    }

    private double preferredBoost(List<KnowledgeEvidenceType> preferred, List<KnowledgeEvidenceType> matchedPreferred) {
        double boost = 0;
        for (KnowledgeEvidenceType type : matchedPreferred) {
            int index = preferred.indexOf(type);
            boost += Math.max(0.12, PREFERRED_EVIDENCE_BOOST - Math.max(index, 0) * 0.05);
        }
        return Math.min(0.48, boost);
    }

    private EvidenceSourceReliability bestReliability(List<KnowledgeEvidenceType> evidenceTypes) {
        Set<EvidenceSourceReliability> reliabilities = new LinkedHashSet<>();
        evidenceTypes.stream()
                .map(KnowledgeEvidenceType::sourceReliability)
                .forEach(reliabilities::add);
        if (reliabilities.contains(EvidenceSourceReliability.PRIMARY)) {
            return EvidenceSourceReliability.PRIMARY;
        }
        if (reliabilities.contains(EvidenceSourceReliability.SECONDARY)) {
            return EvidenceSourceReliability.SECONDARY;
        }
        if (reliabilities.contains(EvidenceSourceReliability.DERIVED)) {
            return EvidenceSourceReliability.DERIVED;
        }
        return EvidenceSourceReliability.UNKNOWN;
    }

    private String specificEngineeringReason(KnowledgeChunkView view, List<KnowledgeEvidenceType> evidenceTypes) {
        String path = safe(view.document().filePath()).toLowerCase(Locale.ROOT);
        if (evidenceTypes.contains(KnowledgeEvidenceType.SQL_SCHEMA) && path.endsWith(".sql")) {
            return "sql_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.MAPPER) && path.endsWith("mapper.xml")) {
            return "mapper_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.CONFIG)
                && (path.endsWith("application.yml") || path.endsWith("application.yaml") || path.endsWith("application.properties"))) {
            return "config_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.DEPLOYMENT)
                && (path.endsWith("compose.yml") || path.endsWith("compose.yaml") || path.contains("dockerfile")
                || path.startsWith("deploy/") || path.startsWith("k8s/"))) {
            return "deployment_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.OBSERVABILITY)
                && (path.contains("prometheus") || path.contains("grafana") || path.contains("metrics"))) {
            return "observability_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.TEST)
                && (path.contains("/test/") || path.endsWith("test.java") || path.endsWith("tests.java"))) {
            return "test_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.BENCHMARK)
                && (path.contains("benchmark") || path.contains("load-test") || path.contains("jmeter") || path.contains("k6"))) {
            return "benchmark_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.API_CONTROLLER)
                && (path.contains("controller") || path.contains("/api/") || path.contains("/web/"))) {
            return "api_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.SERVICE_CODE)
                && (path.contains("service") || path.contains("handler") || path.contains("usecase"))) {
            return "service_file";
        }
        if (evidenceTypes.contains(KnowledgeEvidenceType.CACHE)
                && (path.endsWith(".lua") || path.contains("redis") || path.contains("cache"))) {
            return "cache_file";
        }
        return null;
    }

    private boolean isGenericDocOnly(List<KnowledgeEvidenceType> evidenceTypes) {
        return !evidenceTypes.isEmpty()
                && evidenceTypes.stream().allMatch(type ->
                type == KnowledgeEvidenceType.GENERATED_DOC || type == KnowledgeEvidenceType.MANUAL_DOC);
    }

    private boolean hasSpecificPreferredEvidence(List<KnowledgeEvidenceType> preferred) {
        return preferred.stream().anyMatch(type ->
                type != KnowledgeEvidenceType.GENERATED_DOC && type != KnowledgeEvidenceType.MANUAL_DOC);
    }

    private boolean containsTodoMarker(KnowledgeChunkView view) {
        String text = safe(view.chunk().headingPath()) + "\n" + safe(view.chunk().content());
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("todo") || lower.contains("tbd") || lower.contains("placeholder") || lower.contains("coming soon");
    }

    private List<KnowledgeEvidenceType> intersection(List<KnowledgeEvidenceType> expected, List<KnowledgeEvidenceType> actual) {
        return expected.stream()
                .filter(actual::contains)
                .toList();
    }

    private String names(List<KnowledgeEvidenceType> evidenceTypes) {
        return String.join(",", evidenceTypes.stream().map(Enum::name).toList());
    }

    private String join(List<String> values) {
        return String.join(",", values);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
