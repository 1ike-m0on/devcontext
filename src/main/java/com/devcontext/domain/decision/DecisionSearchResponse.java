package com.devcontext.domain.decision;

import java.util.List;

public record DecisionSearchResponse(
        String query,
        List<DecisionSearchResult> matches
) {
}
