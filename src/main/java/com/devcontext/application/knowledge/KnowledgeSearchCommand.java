package com.devcontext.application.knowledge;

public record KnowledgeSearchCommand(
        String query,
        Long sourceId,
        Integer topK
) {
}
