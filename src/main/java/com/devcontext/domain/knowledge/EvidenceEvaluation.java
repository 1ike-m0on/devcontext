package com.devcontext.domain.knowledge;

import java.util.List;

public record EvidenceEvaluation(
        String status,
        boolean sufficient,
        boolean noAnswerRequired,
        List<KnowledgeEvidenceType> requiredEvidenceTypes,
        List<KnowledgeEvidenceType> matchedRequiredEvidenceTypes,
        List<KnowledgeEvidenceType> missingRequiredEvidenceTypes,
        List<KnowledgeEvidenceType> preferredEvidenceTypes,
        List<KnowledgeEvidenceType> matchedPreferredEvidenceTypes,
        List<KnowledgeEvidenceType> missingPreferredEvidenceTypes,
        List<KnowledgeEvidenceType> observedEvidenceTypes,
        List<EvidenceCitationAssessment> citationAssessments,
        List<String> reasons
) {
}
