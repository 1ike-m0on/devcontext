package com.devcontext.domain.review;

import java.time.Instant;

public record ReviewMemorySignal(
        Long projectId,
        Long reviewId,
        Long issueId,
        ReviewMemorySignalType signalType,
        String feedbackStatus,
        String title,
        String filePath,
        Integer lineNumber,
        String description,
        String impact,
        String suggestion,
        String note,
        Instant updatedAt
) {
}
