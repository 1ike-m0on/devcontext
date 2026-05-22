package com.devcontext.domain.review;

import java.time.Instant;

public record ReviewRecord(
        Long id,
        Long projectId,
        Long runId,
        String baseBranch,
        String compareBranch,
        String diffHash,
        double score,
        String summary,
        String reportPath,
        Instant createdAt
) {
}
