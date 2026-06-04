package com.devcontext.domain.decision;

import java.util.List;

public record DecisionEmbeddingRebuildResult(
        Long runId,
        int requestedCount,
        int indexedCount,
        int failedCount,
        List<Long> failedDecisionIds,
        List<DecisionCard> decisions
) {
}
