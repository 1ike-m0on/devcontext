package com.devcontext.domain.graph;

import java.time.Instant;

public record ProjectGraphEdge(
        Long id,
        Long projectId,
        String edgeType,
        String stableKey,
        String fromNodeKey,
        String toNodeKey,
        String label,
        String sourcePath,
        String evidenceType,
        String sourceKind,
        String sourceReliability,
        Instant createdAt,
        Instant updatedAt
) {
}
