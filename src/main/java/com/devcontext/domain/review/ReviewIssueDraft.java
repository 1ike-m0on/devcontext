package com.devcontext.domain.review;

public record ReviewIssueDraft(
        String severity,
        String title,
        String filePath,
        Integer lineNumber,
        String description,
        String impact,
        String suggestion,
        String confidence
) {
}
