package com.devcontext.domain.graph;

import java.time.Instant;

public record ProjectGraphNode(
        Long id,
        Long projectId,
        String nodeType,
        String stableKey,
        String label,
        String sourcePath,
        String evidenceType,
        String sourceKind,
        String sourceReliability,
        Instant createdAt,
        Instant updatedAt
) {
}
