package com.devcontext.domain.knowledge;

import java.util.Map;

public record VectorDocument(
        String vectorId,
        String collection,
        Long sourceId,
        EmbeddingVector embedding,
        Map<String, String> metadata
) {
}
