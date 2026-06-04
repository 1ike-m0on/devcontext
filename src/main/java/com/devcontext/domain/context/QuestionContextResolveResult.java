package com.devcontext.domain.context;

import java.util.List;

public record QuestionContextResolveResult(
        Long projectId,
        String question,
        String status,
        boolean needsDeepScan,
        List<String> queryTerms,
        List<QuestionContextCandidate> candidates,
        List<ContextItem> items,
        List<String> notes
) {
}
