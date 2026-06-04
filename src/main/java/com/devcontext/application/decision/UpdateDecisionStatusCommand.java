package com.devcontext.application.decision;

public record UpdateDecisionStatusCommand(
        Long decisionId,
        String status
) {
}
