package com.devcontext.domain.profile;

import java.time.Instant;
import java.util.List;

public record ProjectProfile(
        Long id,
        Long projectId,
        String status,
        String summary,
        List<ProjectProfileFact> facts,
        List<String> warnings,
        Instant generatedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
