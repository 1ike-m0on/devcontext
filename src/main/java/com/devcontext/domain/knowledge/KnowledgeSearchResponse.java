package com.devcontext.domain.knowledge;

import java.util.List;

public record KnowledgeSearchResponse(
        Long retrievalRecordId,
        String query,
        String rewrittenQuery,
        KnowledgeQueryPlan queryPlan,
        List<KnowledgeSearchResult> results
) {
}
