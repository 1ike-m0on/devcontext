package com.devcontext.domain.context;

import java.util.List;

public record QuestionContextCandidate(
        String sourceType,
        String title,
        String file,
        double score,
        List<String> matchedTerms,
        List<String> reasons,
        List<String> relatedSymbols
) {
}
