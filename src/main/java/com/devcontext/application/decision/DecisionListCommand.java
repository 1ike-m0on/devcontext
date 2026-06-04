package com.devcontext.application.decision;

public record DecisionListCommand(
        String status,
        Long projectId,
        String tag,
        String query
) {
}
