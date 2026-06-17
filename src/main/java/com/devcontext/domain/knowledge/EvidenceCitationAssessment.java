package com.devcontext.domain.knowledge;

import java.util.List;

public record EvidenceCitationAssessment(
        int citationIndex,
        String sourcePath,
        List<KnowledgeEvidenceType> evidenceTypes,
        List<String> sourceReliabilities,
        boolean supportsRequiredEvidence,
        boolean supportsPreferredEvidence,
        List<String> sourceKinds,
        boolean weakEvidence,
        List<String> weaknessReasons,
        List<String> scoreReasons
) {
    public EvidenceCitationAssessment {
        evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
        sourceReliabilities = sourceReliabilities == null ? List.of() : List.copyOf(sourceReliabilities);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
        weaknessReasons = weaknessReasons == null ? List.of() : List.copyOf(weaknessReasons);
        scoreReasons = scoreReasons == null ? List.of() : List.copyOf(scoreReasons);
    }
}
