package com.devcontext.domain.graph;

import java.util.List;

public record ProjectGraphNeighborhood(
        Long projectId,
        List<ProjectGraphNode> seedNodes,
        List<ProjectGraphNode> neighborNodes,
        List<ProjectGraphEdge> edges,
        List<String> warnings
) {
}
