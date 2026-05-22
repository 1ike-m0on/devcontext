package com.devcontext.domain.run;

import java.time.Instant;

public record AgentRun(
        Long id,
        Long projectId,
        String runType,
        String status,
        String modelName,
        String promptVersion,
        Integer inputTokenEstimate,
        Integer outputTokenEstimate,
        Long durationMs,
        String errorMessage,
        Instant createdAt,
        Instant finishedAt
) {
}

