package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeSearchResponse(
        Long retrievalRecordId,
        String query,
        String rewrittenQuery,
        List<KnowledgeSearchResult> results
) {
}
