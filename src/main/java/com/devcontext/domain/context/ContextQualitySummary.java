package com.devcontext.domain.context;

import java.util.List;

public record ContextQualitySummary(
        String level,
        int score,
        int existingDocuments,
        int totalDocuments,
        int missingCount,
        int todoCount,
        List<ContextQualityIssue> issues
) {
}
