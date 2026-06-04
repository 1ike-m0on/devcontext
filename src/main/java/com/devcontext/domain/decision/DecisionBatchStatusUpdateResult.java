package com.devcontext.domain.decision;

import java.util.List;

public record DecisionBatchStatusUpdateResult(
        Long runId,
        int updatedCount,
        List<DecisionCard> decisions
) {
}
