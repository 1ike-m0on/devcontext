package com.devcontext.domain.knowledge;

import java.util.List;
import java.util.Map;

public record EvidenceCoverageReport(
        Long sourceId,
        int documentsIndexed,
        int chunksIndexed,
        Map<KnowledgeEvidenceType, Integer> coverage,
        List<String> warnings
) {
}
