package com.devcontext.adapters.web;

import com.devcontext.application.profile.ProjectProfileApplicationService;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.profile.ProjectProfileResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/profile")
public class ProjectProfileController {

    private final ProjectProfileApplicationService profileService;

    public ProjectProfileController(ProjectProfileApplicationService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<ProjectProfileResponse> getProfile(@PathVariable Long projectId) {
        return ApiResponse.ok(profileService.getProfileResponse(projectId));
    }
}
