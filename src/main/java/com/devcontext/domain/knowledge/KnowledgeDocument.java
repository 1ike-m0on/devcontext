package com.devcontext.domain.knowledge;

import java.time.Instant;

public record KnowledgeDocument(
        Long id,
        Long sourceId,
        String filePath,
        String title,
        String contentHash,
        String status,
        Instant indexedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
