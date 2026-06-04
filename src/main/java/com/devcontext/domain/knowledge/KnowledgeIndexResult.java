package com.devcontext.domain.knowledge;

public record KnowledgeIndexResult(
        Long sourceId,
        int documentsIndexed,
        int chunksIndexed,
        EvidenceCoverageReport coverageReport
) {
}
