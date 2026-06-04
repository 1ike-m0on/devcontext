package com.devcontext.domain.knowledge;

import java.util.List;

public record RagAnswerResult(
        Long runId,
        Long retrievalRecordId,
        String query,
        String rewrittenQuery,
        KnowledgeQueryPlan queryPlan,
        String answer,
        List<KnowledgeSearchResult> citations
) {
}
