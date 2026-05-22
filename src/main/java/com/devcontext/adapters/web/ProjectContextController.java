package com.devcontext.adapters.web;

import com.devcontext.application.context.ProjectContextAssetApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.context.ContextGenerationResult;
import com.devcontext.domain.context.ProjectContextStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/context")
public class ProjectContextController {

    private final ProjectContextAssetApplicationService contextAssetService;

    public ProjectContextController(ProjectContextAssetApplicationService contextAssetService) {
        this.contextAssetService = contextAssetService;
    }

    @PostMapping("/generate")
    public ApiResponse<ContextGenerationResult> generate(
            @PathVariable Long projectId,
            @RequestBody(required = false) GenerateContextRequest request
    ) {
        boolean overwriteGenerated = request == null || request.overwriteGenerated() == null || request.overwriteGenerated();
        boolean overwriteManual = request != null && Boolean.TRUE.equals(request.overwriteManual());
        return ApiResponse.ok(contextAssetService.generate(projectId, overwriteGenerated, overwriteManual));
    }

    @GetMapping
    public ApiResponse<ProjectContextStatus> getStatus(@PathVariable Long projectId) {
        return ApiResponse.ok(contextAssetService.getStatus(projectId));
    }

    public record GenerateContextRequest(
            Boolean overwriteGenerated,
            Boolean overwriteManual
    ) {
    }
}
