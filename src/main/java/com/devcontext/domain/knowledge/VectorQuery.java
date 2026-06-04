package com.devcontext.domain.knowledge;

import java.util.Map;

public record VectorQuery(
        String collection,
        Long sourceId,
        EmbeddingVector embedding,
        int topK,
        Map<String, Object> filters
) {

    public VectorQuery(String collection, Long sourceId, EmbeddingVector embedding, int topK) {
        this(collection, sourceId, embedding, topK, Map.of());
    }
}
