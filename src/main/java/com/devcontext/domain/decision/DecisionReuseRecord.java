package com.devcontext.domain.decision;

import java.time.Instant;
import java.util.List;

public record DecisionReuseRecord(
        Long id,
        Long runId,
        String query,
        Long projectId,
        List<Long> matchedDecisionIds,
        String advice,
        String status,
        Boolean accepted,
        String userFeedback,
        Instant createdAt
) {
}
