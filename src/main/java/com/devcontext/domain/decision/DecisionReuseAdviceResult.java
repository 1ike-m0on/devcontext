package com.devcontext.domain.decision;

import java.util.List;

public record DecisionReuseAdviceResult(
        Long runId,
        Long reuseRecordId,
        String query,
        List<DecisionSearchResult> matchedDecisions,
        String advice
) {
}
