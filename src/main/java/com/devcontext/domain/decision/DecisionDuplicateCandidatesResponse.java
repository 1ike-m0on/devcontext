package com.devcontext.domain.decision;

import java.util.List;

public record DecisionDuplicateCandidatesResponse(
        double minScore,
        List<DecisionDuplicatePair> pairs
) {
}
