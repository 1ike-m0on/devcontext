package com.devcontext.application.decision;

import com.devcontext.domain.decision.DecisionEvidence;
import java.util.List;

public record CreateDecisionCommand(
        Long projectId,
        String title,
        String scenario,
        List<String> options,
        String decision,
        List<String> reasons,
        List<String> tradeOffs,
        List<String> applicableWhen,
        List<String> notApplicableWhen,
        String outcome,
        List<DecisionEvidence> evidence,
        String status,
        List<String> tags
) {
}
