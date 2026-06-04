package com.devcontext.domain.decision;

import java.time.Instant;
import java.util.List;

public record DecisionCard(
        Long id,
        Long projectId,
        String title,
        String scenario,
        List<String> options,
        String decision,
        List<String> reasons,
        List<String> tradeOffs,
        List<String> applicableWhen,
        List<String> notApplicableWhen,
        String outcome,
        List<DecisionEvidence> evidence,
        String status,
        List<String> tags,
        String embeddingStatus,
        Instant embeddingUpdatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
