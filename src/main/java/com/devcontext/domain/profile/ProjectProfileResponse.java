package com.devcontext.domain.profile;

import java.time.Instant;
import java.util.List;

public record ProjectProfileResponse(
        Long id,
        Long projectId,
        String status,
        String summary,
        List<ProjectProfileFact> facts,
        List<String> warnings,
        Instant generatedAt,
        Instant createdAt,
        Instant updatedAt,
        ProjectProfileFreshnessSummary freshness
) {

    public static ProjectProfileResponse from(ProjectProfile profile, ProjectProfileFreshnessSummary freshness) {
        return new ProjectProfileResponse(
                profile.id(),
                profile.projectId(),
                profile.status(),
                profile.summary(),
                profile.facts(),
                profile.warnings(),
                profile.generatedAt(),
                profile.createdAt(),
                profile.updatedAt(),
                freshness
        );
    }
}
