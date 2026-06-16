package com.devcontext.domain.evidence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProjectEvidenceCoverageSummary(
        Long projectId,
        Instant generatedAt,
        int totalEvidenceCount,
        int primaryEvidenceCount,
        int secondaryEvidenceCount,
        int derivedEvidenceCount,
        Map<String, Integer> countsByEvidenceType,
        Map<String, Integer> countsBySourceKind,
        Map<String, Integer> countsBySourceReliability,
        List<ProjectEvidenceSourceGroupSummary> sourceGroups,
        List<ProjectEvidenceCategorySummary> missingCategories,
        List<ProjectEvidenceCategorySummary> skippedCategories
) {
    public ProjectEvidenceCoverageSummary {
        countsByEvidenceType = countsByEvidenceType == null ? Map.of() : Map.copyOf(countsByEvidenceType);
        countsBySourceKind = countsBySourceKind == null ? Map.of() : Map.copyOf(countsBySourceKind);
        countsBySourceReliability = countsBySourceReliability == null ? Map.of() : Map.copyOf(countsBySourceReliability);
        sourceGroups = sourceGroups == null ? List.of() : List.copyOf(sourceGroups);
        missingCategories = missingCategories == null ? List.of() : List.copyOf(missingCategories);
        skippedCategories = skippedCategories == null ? List.of() : List.copyOf(skippedCategories);
    }
}
