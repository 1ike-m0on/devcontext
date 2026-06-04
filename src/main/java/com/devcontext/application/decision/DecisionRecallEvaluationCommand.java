package com.devcontext.application.decision;

import java.util.List;

public record DecisionRecallEvaluationCommand(
        List<DecisionRecallEvaluationCaseCommand> cases
) {
}
