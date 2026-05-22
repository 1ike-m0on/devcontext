package com.devcontext.domain.context;

import java.time.Instant;

public record ContextDocumentStatus(
        String type,
        String path,
        boolean exists,
        boolean generated,
        String status,
        String sourceCommit,
        Instant updatedAt
) {
}
