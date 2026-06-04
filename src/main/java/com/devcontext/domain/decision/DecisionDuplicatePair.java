package com.devcontext.domain.decision;

import java.util.List;

public record DecisionDuplicatePair(
        DecisionCard left,
        DecisionCard right,
        double score,
        List<String> reasons
) {
}
