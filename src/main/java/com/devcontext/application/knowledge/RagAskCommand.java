package com.devcontext.application.knowledge;

public record RagAskCommand(
        String query,
        Long sourceId,
        Integer topK
) {
}
