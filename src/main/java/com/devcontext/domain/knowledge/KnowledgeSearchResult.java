package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeSearchResult(
        Long chunkId,
        Long documentId,
        Long sourceId,
        String sourceName,
        String filePath,
        String title,
        String headingPath,
        String content,
        double keywordScore,
        double vectorScore,
        double fusedScore,
        List<KnowledgeEvidenceType> evidenceTypes,
        List<String> scoreReasons
) {
}
