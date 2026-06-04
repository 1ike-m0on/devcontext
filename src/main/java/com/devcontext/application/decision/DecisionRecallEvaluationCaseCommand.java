package com.devcontext.application.decision;

import java.util.List;

public record DecisionRecallEvaluationCaseCommand(
        String name,
        String query,
        Long projectId,
        List<String> tags,
        Integer topK,
        List<Long> expectedDecisionIds,
        List<Long> forbiddenDecisionIds
) {
}
