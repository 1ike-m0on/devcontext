package com.devcontext.domain.context;

import java.time.Instant;

public record ContextItem(
        Long id,
        Long runId,
        Long projectId,
        String type,
        String title,
        String content,
        String source,
        int priority,
        int tokenEstimate,
        String hash,
        Instant createdAt
) {
}

