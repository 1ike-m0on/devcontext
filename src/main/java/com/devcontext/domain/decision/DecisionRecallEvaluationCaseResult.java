package com.devcontext.domain.decision;

import java.util.List;

public record DecisionRecallEvaluationCaseResult(
        String name,
        String query,
        int topK,
        List<Long> expectedDecisionIds,
        List<Long> returnedDecisionIds,
        List<Long> hitDecisionIds,
        List<Long> missingExpectedDecisionIds,
        List<Long> unexpectedDecisionIds,
        List<Long> forbiddenDecisionIds,
        List<Long> forbiddenHitDecisionIds,
        boolean hit,
        boolean forbiddenPass,
        Integer firstHitRank,
        double reciprocalRank,
        double precisionAtK,
        double recallAtK,
        double falsePositiveAtK,
        List<DecisionSearchResult> matches
) {
}
