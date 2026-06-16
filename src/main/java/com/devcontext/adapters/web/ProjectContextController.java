package com.devcontext.adapters.web;

import com.devcontext.application.context.ProjectContextAssetApplicationService;
import com.devcontext.application.context.QuestionContextResolveCommand;
import com.devcontext.application.context.QuestionContextResolver;
import com.devcontext.application.evidence.ProjectEvidenceCatalogApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.context.ContextGenerationResult;
import com.devcontext.domain.context.ProjectContextStatus;
import com.devcontext.domain.context.QuestionContextResolveResult;
import com.devcontext.domain.evidence.ProjectEvidenceCoverageSummary;
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
    private final QuestionContextResolver questionContextResolver;
    private final ProjectEvidenceCatalogApplicationService evidenceCatalogService;

    public ProjectContextController(
            ProjectContextAssetApplicationService contextAssetService,
            QuestionContextResolver questionContextResolver,
            ProjectEvidenceCatalogApplicationService evidenceCatalogService
    ) {
        this.contextAssetService = contextAssetService;
        this.questionContextResolver = questionContextResolver;
        this.evidenceCatalogService = evidenceCatalogService;
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

    @GetMapping("/evidence-coverage")
    public ApiResponse<ProjectEvidenceCoverageSummary> getEvidenceCoverage(@PathVariable Long projectId) {
        return ApiResponse.ok(evidenceCatalogService.buildSummary(projectId));
    }

    @PostMapping("/resolve")
    public ApiResponse<QuestionContextResolveResult> resolve(
            @PathVariable Long projectId,
            @RequestBody ResolveQuestionContextRequest request
    ) {
        return ApiResponse.ok(questionContextResolver.resolve(projectId, new QuestionContextResolveCommand(
                request.question(),
                request.maxItems()
        )));
    }

    public record GenerateContextRequest(
            Boolean overwriteGenerated,
            Boolean overwriteManual
    ) {
    }

    public record ResolveQuestionContextRequest(
            String question,
            Integer maxItems
    ) {
    }
}
