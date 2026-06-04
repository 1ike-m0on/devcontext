package com.devcontext.domain.decision;

public record DecisionStatusUpdateResult(
        Long runId,
        DecisionCard decision
) {
}
