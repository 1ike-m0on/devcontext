package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeQueryPlan(
        String originalQuery,
        String rewrittenQuery,
        List<String> normalizedTerms,
        String intent,
        List<KnowledgeEvidenceType> requiredEvidenceTypes,
        List<KnowledgeEvidenceType> preferredEvidenceTypes,
        List<KnowledgeEvidenceType> forbiddenEvidenceTypes,
        List<String> requiredSourceKinds,
        List<String> preferredSourceKinds,
        List<String> forbiddenSourceKinds,
        String answerMode,
        String noAnswerPolicy,
        String fallbackStrategy,
        List<String> planningReasons
) {
    public KnowledgeQueryPlan {
        normalizedTerms = normalizedTerms == null ? List.of() : List.copyOf(normalizedTerms);
        requiredEvidenceTypes = requiredEvidenceTypes == null ? List.of() : List.copyOf(requiredEvidenceTypes);
        preferredEvidenceTypes = preferredEvidenceTypes == null ? List.of() : List.copyOf(preferredEvidenceTypes);
        forbiddenEvidenceTypes = forbiddenEvidenceTypes == null ? List.of() : List.copyOf(forbiddenEvidenceTypes);
        requiredSourceKinds = requiredSourceKinds == null ? List.of() : List.copyOf(requiredSourceKinds);
        preferredSourceKinds = preferredSourceKinds == null ? List.of() : List.copyOf(preferredSourceKinds);
        forbiddenSourceKinds = forbiddenSourceKinds == null ? List.of() : List.copyOf(forbiddenSourceKinds);
        planningReasons = planningReasons == null ? List.of() : List.copyOf(planningReasons);
    }
}
