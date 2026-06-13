package com.devcontext.domain.review;

import java.time.Instant;

public record ReviewHistoryItem(
        Long id,
        Long projectId,
        Long runId,
        String baseBranch,
        String compareBranch,
        String diffHash,
        double score,
        String summary,
        String reportPath,
        Instant createdAt,
        ReviewOutcomeSummary outcomeSummary
) {
    public static ReviewHistoryItem from(ReviewRecord record, ReviewOutcomeSummary outcomeSummary) {
        return new ReviewHistoryItem(
                record.id(),
                record.projectId(),
                record.runId(),
                record.baseBranch(),
                record.compareBranch(),
                record.diffHash(),
                record.score(),
                record.summary(),
                record.reportPath(),
                record.createdAt(),
                outcomeSummary
        );
    }
}
