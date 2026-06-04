package com.devcontext.domain.decision;

import java.util.List;

public record DecisionRecallEvaluationResult(
        Long runId,
        int caseCount,
        int hitCount,
        double hitRate,
        double meanReciprocalRank,
        double averagePrecisionAtK,
        double averageRecallAtK,
        double averageFalsePositiveAtK,
        double forbiddenPassRate,
        List<DecisionRecallEvaluationCaseResult> cases
) {
}
