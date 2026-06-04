package com.devcontext.adapters.web;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.context.ContextAssembler;
import com.devcontext.context.ContextRequest;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.project.Project;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectApplicationService projectService;
    private final ContextAssembler contextAssembler;

    public ProjectController(ProjectApplicationService projectService, ContextAssembler contextAssembler) {
        this.projectService = projectService;
        this.contextAssembler = contextAssembler;
    }

    @PostMapping
    public ApiResponse<Project> createProject(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.createProject(request.name(), request.rootPath(), request.defaultBranch()));
    }

    @GetMapping
    public ApiResponse<List<Project>> listProjects() {
        return ApiResponse.ok(projectService.listProjects());
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Project> getProject(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.getProject(projectId));
    }

    @PatchMapping("/{projectId}")
    public ApiResponse<Project> updateProject(
            @PathVariable Long projectId,
            @RequestBody UpdateProjectRequest request
    ) {
        return ApiResponse.ok(projectService.updateProject(
                projectId,
                request.name(),
                request.rootPath(),
                request.defaultBranch()
        ));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<Map<String, Object>> deleteProject(@PathVariable Long projectId) {
        projectService.deleteProject(projectId);
        return ApiResponse.ok(Map.of(
                "projectId", projectId,
                "deleted", true
        ));
    }

    @GetMapping("/{projectId}/context-items")
    public ApiResponse<List<ContextItem>> getProjectContextItems(@PathVariable Long projectId) {
        return ApiResponse.ok(contextAssembler.assemble(new ContextRequest(projectId, "MVP0_INSPECT")));
    }

    public record CreateProjectRequest(
            @NotBlank String name,
            @NotBlank String rootPath,
            String defaultBranch
    ) {
    }

    public record UpdateProjectRequest(
            String name,
            String rootPath,
            String defaultBranch
    ) {
    }
}
