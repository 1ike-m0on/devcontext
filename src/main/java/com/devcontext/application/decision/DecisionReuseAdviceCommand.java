package com.devcontext.application.decision;

import java.util.List;

public record DecisionReuseAdviceCommand(
        String query,
        Long projectId,
        List<String> tags,
        Integer topK
) {
}
