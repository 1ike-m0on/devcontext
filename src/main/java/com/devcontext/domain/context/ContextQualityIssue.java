package com.devcontext.domain.context;

public record ContextQualityIssue(
        String severity,
        String documentType,
        String path,
        String title,
        String message,
        String suggestion
) {
}
