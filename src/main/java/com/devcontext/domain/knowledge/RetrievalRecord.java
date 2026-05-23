package com.devcontext.domain.knowledge;

import java.time.Instant;

public record RetrievalRecord(
        Long id,
        Long runId,
        String query,
        String rewrittenQuery,
        Integer topK,
        String resultJson,
        Instant createdAt
) {
}
