package com.devcontext.ports.graph;

import com.devcontext.domain.graph.ProjectGraphEdge;
import com.devcontext.domain.graph.ProjectGraphNode;
import java.util.List;
import java.util.Optional;

public interface ProjectGraphRepository {

    void replaceProjectGraph(Long projectId, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges);

    List<ProjectGraphNode> findNodesByProjectId(Long projectId);

    List<ProjectGraphEdge> findEdgesByProjectId(Long projectId);

    Optional<ProjectGraphNode> findNodeByStableKey(Long projectId, String stableKey);
}
