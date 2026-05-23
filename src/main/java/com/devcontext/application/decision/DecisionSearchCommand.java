package com.devcontext.application.decision;

import java.util.List;

public record DecisionSearchCommand(
        String query,
        Long projectId,
        List<String> tags,
        Integer topK
) {
}
