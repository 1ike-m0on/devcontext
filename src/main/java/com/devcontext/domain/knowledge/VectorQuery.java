package com.devcontext.domain.knowledge;

public record VectorQuery(
        String collection,
        Long sourceId,
        EmbeddingVector embedding,
        int topK
) {
}
