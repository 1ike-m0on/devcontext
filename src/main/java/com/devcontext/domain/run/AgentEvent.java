package com.devcontext.domain.run;

import java.time.Instant;

public record AgentEvent(
        Long id,
        Long runId,
        String eventType,
        String inputSummary,
        String outputSummary,
        String status,
        Long durationMs,
        String errorMessage,
        Instant createdAt
) {
}

