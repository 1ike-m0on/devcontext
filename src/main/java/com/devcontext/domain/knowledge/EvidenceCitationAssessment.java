package com.devcontext.domain.knowledge;

import java.util.List;

public record EvidenceCitationAssessment(
        int citationIndex,
        String sourcePath,
        List<KnowledgeEvidenceType> evidenceTypes,
        List<String> sourceReliabilities,
        boolean supportsRequiredEvidence,
        boolean supportsPreferredEvidence
) {
}
