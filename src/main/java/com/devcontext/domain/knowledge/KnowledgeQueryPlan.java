package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeQueryPlan(
        String originalQuery,
        String rewrittenQuery,
        List<String> normalizedTerms,
        List<KnowledgeEvidenceType> requiredEvidenceTypes,
        List<KnowledgeEvidenceType> preferredEvidenceTypes,
        List<KnowledgeEvidenceType> forbiddenEvidenceTypes,
        String answerMode,
        String noAnswerPolicy
) {
}
