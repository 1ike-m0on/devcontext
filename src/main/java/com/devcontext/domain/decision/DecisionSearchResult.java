package com.devcontext.domain.decision;

import java.util.List;

public record DecisionSearchResult(
        DecisionCard decision,
        double score,
        List<String> matchedTags,
        List<String> matchedTerms,
        double tagScore,
        double keywordScore,
        double vectorScore,
        List<String> matchReasons
) {
    public DecisionSearchResult(
            DecisionCard decision,
            double score,
            List<String> matchedTags,
            List<String> matchedTerms
    ) {
        this(decision, score, matchedTags, matchedTerms, 0, score, 0, List.of());
    }
}
