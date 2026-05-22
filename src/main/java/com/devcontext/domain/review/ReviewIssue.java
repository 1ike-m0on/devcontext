package com.devcontext.domain.review;

import java.time.Instant;

public record ReviewIssue(
        Long id,
        Long reviewId,
        String severity,
        String title,
        String filePath,
        Integer lineNumber,
        String description,
        String impact,
        String suggestion,
        String confidence,
        String status,
        String note,
        Instant createdAt,
        Instant updatedAt
) {
}
