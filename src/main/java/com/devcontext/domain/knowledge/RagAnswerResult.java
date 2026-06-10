package com.devcontext.domain.knowledge;

import java.util.List;

public record RagAnswerResult(
        Long runId,
        Long retrievalRecordId,
        String query,
        String rewrittenQuery,
        KnowledgeQueryPlan queryPlan,
        EvidenceEvaluation evidenceEvaluation,
        String answer,
        List<KnowledgeSearchResult> citations
) {
}
