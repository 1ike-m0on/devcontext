package com.devcontext.domain.knowledge;

import java.time.Instant;

public record KnowledgeSource(
        Long id,
        String name,
        String rootPath,
        String sourceType,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
