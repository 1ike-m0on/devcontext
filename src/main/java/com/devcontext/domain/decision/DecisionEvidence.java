package com.devcontext.domain.decision;

public record DecisionEvidence(
        String type,
        String ref,
        String summary
) {
}
