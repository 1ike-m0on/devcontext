package com.devcontext.application.decision;

public record RebuildDecisionEmbeddingsCommand(
        String status,
        Long projectId,
        String tag,
        String query
) {
}
