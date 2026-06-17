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
        List<String> reasons,
        String answerGuardDecision,
        List<KnowledgeEvidenceType> weakEvidenceTypes,
        List<String> weakEvidenceReasons
) {
    public EvidenceEvaluation {
        requiredEvidenceTypes = requiredEvidenceTypes == null ? List.of() : List.copyOf(requiredEvidenceTypes);
        matchedRequiredEvidenceTypes = matchedRequiredEvidenceTypes == null ? List.of() : List.copyOf(matchedRequiredEvidenceTypes);
        missingRequiredEvidenceTypes = missingRequiredEvidenceTypes == null ? List.of() : List.copyOf(missingRequiredEvidenceTypes);
        preferredEvidenceTypes = preferredEvidenceTypes == null ? List.of() : List.copyOf(preferredEvidenceTypes);
        matchedPreferredEvidenceTypes = matchedPreferredEvidenceTypes == null ? List.of() : List.copyOf(matchedPreferredEvidenceTypes);
        missingPreferredEvidenceTypes = missingPreferredEvidenceTypes == null ? List.of() : List.copyOf(missingPreferredEvidenceTypes);
        observedEvidenceTypes = observedEvidenceTypes == null ? List.of() : List.copyOf(observedEvidenceTypes);
        citationAssessments = citationAssessments == null ? List.of() : List.copyOf(citationAssessments);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        answerGuardDecision = answerGuardDecision == null || answerGuardDecision.isBlank()
                ? "insufficient_evidence"
                : answerGuardDecision;
        weakEvidenceTypes = weakEvidenceTypes == null ? List.of() : List.copyOf(weakEvidenceTypes);
        weakEvidenceReasons = weakEvidenceReasons == null ? List.of() : List.copyOf(weakEvidenceReasons);
    }
}
