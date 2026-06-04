package com.devcontext.domain.review;

public record ReviewCreateResult(
        Long reviewId,
        Long runId,
        double score,
        String summary,
        String reportPath,
        boolean diffTruncated
) {
    public ReviewCreateResult(Long reviewId, Long runId, double score, String summary, String reportPath) {
        this(reviewId, runId, score, summary, reportPath, false);
    }
}
