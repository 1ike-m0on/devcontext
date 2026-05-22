package com.devcontext.domain.context;

import java.time.Instant;

public record ContextDocument(
        Long id,
        Long projectId,
        String type,
        String filePath,
        boolean generated,
        String status,
        String sourceCommit,
        Instant createdAt,
        Instant updatedAt
) {
}
