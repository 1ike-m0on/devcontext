package com.devcontext.domain.decision;

import java.time.Instant;
import java.util.List;

public record DecisionReuseRecord(
        Long id,
        String query,
        Long projectId,
        List<Long> matchedDecisionIds,
        String advice,
        Boolean accepted,
        Instant createdAt
) {
}
