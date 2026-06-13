package com.devcontext.domain.review;

import java.util.List;

public record ReviewDetail(
        ReviewRecord review,
        List<ReviewIssue> issues,
        List<ReviewMemorySignal> reviewMemorySignals,
        ReviewOutcomeSummary outcomeSummary
) {
    public ReviewDetail(ReviewRecord review, List<ReviewIssue> issues) {
        this(review, issues, List.of());
    }

    public ReviewDetail(ReviewRecord review, List<ReviewIssue> issues, List<ReviewMemorySignal> reviewMemorySignals) {
        this(review, issues, reviewMemorySignals, ReviewOutcomeSummary.from(issues));
    }
}
