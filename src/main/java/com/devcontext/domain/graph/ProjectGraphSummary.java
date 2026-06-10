package com.devcontext.domain.graph;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProjectGraphSummary(
        Long projectId,
        String status,
        int nodeCount,
        int edgeCount,
        Map<String, Integer> nodeTypeCounts,
        Map<String, Integer> edgeTypeCounts,
        List<String> warnings,
        Instant updatedAt
) {
}
