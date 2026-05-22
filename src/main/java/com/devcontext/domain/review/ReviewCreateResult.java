package com.devcontext.domain.review;

public record ReviewCreateResult(
        Long reviewId,
        Long runId,
        double score,
        String summary,
        String reportPath
) {
}
