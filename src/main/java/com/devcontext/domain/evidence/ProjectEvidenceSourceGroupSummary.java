package com.devcontext.domain.evidence;

import java.util.List;

public record ProjectEvidenceSourceGroupSummary(
        String groupKey,
        String label,
        int count,
        boolean present,
        boolean primaryEvidence,
        List<String> evidenceTypes,
        List<String> sourceKinds,
        List<String> sourceReliabilities,
        List<String> samplePaths
) {
    public ProjectEvidenceSourceGroupSummary {
        evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
        sourceKinds = sourceKinds == null ? List.of() : List.copyOf(sourceKinds);
        sourceReliabilities = sourceReliabilities == null ? List.of() : List.copyOf(sourceReliabilities);
        samplePaths = samplePaths == null ? List.of() : List.copyOf(samplePaths);
    }
}
