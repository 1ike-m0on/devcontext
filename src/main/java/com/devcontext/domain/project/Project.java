package com.devcontext.domain.project;

import java.time.Instant;

public record Project(
        Long id,
        String name,
        String rootPath,
        String language,
        String framework,
        String defaultBranch,
        Instant createdAt,
        Instant updatedAt
) {
}

