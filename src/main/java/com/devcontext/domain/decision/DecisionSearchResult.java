package com.devcontext.domain.decision;

import java.util.List;

public record DecisionSearchResult(
        DecisionCard decision,
        double score,
        List<String> matchedTags,
        List<String> matchedTerms
) {
}
