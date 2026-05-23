package com.devcontext.domain.knowledge;

public record VectorSearchHit(
        String vectorId,
        double score
) {
}
