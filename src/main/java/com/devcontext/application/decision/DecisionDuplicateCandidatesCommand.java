package com.devcontext.application.decision;

public record DecisionDuplicateCandidatesCommand(
        String status,
        Long projectId,
        String tag,
        String query,
        Double minScore
) {
}
