package com.devcontext.domain.knowledge;

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
        double fusedScore
) {
}
