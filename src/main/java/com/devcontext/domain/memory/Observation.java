package com.devcontext.domain.memory;

import java.time.Instant;

public record Observation(
        Long id,
        Long projectId,
        String sourceType,
        String sourceRecordId,
        String sourceKey,
        String taskType,
        String lifecycle,
        String sourceStatus,
        String title,
        String summary,
        Instant occurredAt,
        String provider,
        String modelName,
        String errorType,
        String errorMessageSummary,
        Long runId,
        Long eventId,
        Long retrievalId,
        Long reviewId,
        Long issueId,
        Long decisionReuseRecordId,
        String reportRunId,
        String reportPath,
        String relationJson,
        String metadataJson,
        String privacyLevel,
        Instant createdAt,
        Instant updatedAt
) {
}
