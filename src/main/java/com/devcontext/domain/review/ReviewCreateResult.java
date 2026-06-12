package com.devcontext.domain.review;

import java.util.List;

public record ReviewCreateResult(
        Long reviewId,
        Long runId,
        double score,
        String summary,
        String reportPath,
        boolean diffTruncated,
        List<ReviewMemorySignal> reviewMemorySignals,
        ReviewContextCoverage contextCoverage
) {
    public ReviewCreateResult(Long reviewId, Long runId, double score, String summary, String reportPath) {
        this(reviewId, runId, score, summary, reportPath, false);
    }

    public ReviewCreateResult(
            Long reviewId,
            Long runId,
            double score,
            String summary,
            String reportPath,
            boolean diffTruncated
    ) {
        this(reviewId, runId, score, summary, reportPath, diffTruncated, List.of(), ReviewContextCoverage.empty());
    }
}
