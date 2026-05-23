package com.devcontext.domain.knowledge;

import java.time.Instant;

public record KnowledgeChunk(
        Long id,
        Long sourceId,
        Long documentId,
        Integer chunkIndex,
        String headingPath,
        String content,
        String contentHash,
        Integer tokenEstimate,
        String vectorId,
        Instant createdAt
) {
}
