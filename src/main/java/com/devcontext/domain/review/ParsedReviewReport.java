package com.devcontext.domain.review;

import java.util.List;

public record ParsedReviewReport(
        double score,
        String summary,
        String changeIntent,
        String impactScope,
        List<String> testGaps,
        List<String> recommendations,
        List<ReviewIssueDraft> issues,
        String markdown,
        String rawResponse
) {
}
