package com.devcontext.domain.review;

import java.util.List;

public record ReviewDetail(
        ReviewRecord review,
        List<ReviewIssue> issues
) {
}
