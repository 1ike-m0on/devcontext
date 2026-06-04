package com.devcontext.application.decision;

import java.util.List;

public record BatchUpdateDecisionStatusCommand(
        List<Long> decisionIds,
        String status
) {
}
