package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeFusionScore(
        double fusedScore,
        List<String> reasons
) {
}
