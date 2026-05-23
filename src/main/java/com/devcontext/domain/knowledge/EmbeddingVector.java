package com.devcontext.domain.knowledge;

import java.util.List;

public record EmbeddingVector(
        List<Double> values
) {
}
