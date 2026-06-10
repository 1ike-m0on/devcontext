package com.devcontext.adapters.web;

import com.devcontext.application.graph.ProjectGraphApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.graph.ProjectGraphNeighborhood;
import com.devcontext.domain.graph.ProjectGraphSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/graph")
public class ProjectGraphController {

    private final ProjectGraphApplicationService graphService;

    public ProjectGraphController(ProjectGraphApplicationService graphService) {
        this.graphService = graphService;
    }

    @GetMapping
    public ApiResponse<ProjectGraphSummary> summary(@PathVariable Long projectId) {
        return ApiResponse.ok(graphService.getSummary(projectId));
    }

    @GetMapping("/neighbors")
    public ApiResponse<ProjectGraphNeighborhood> neighbors(
            @PathVariable Long projectId,
            @RequestParam(required = false) String stableKey,
            @RequestParam(required = false) String sourcePath,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(graphService.neighbors(projectId, stableKey, sourcePath, limit));
    }
}
