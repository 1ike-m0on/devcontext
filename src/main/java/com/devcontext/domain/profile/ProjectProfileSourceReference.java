package com.devcontext.domain.profile;

public record ProjectProfileSourceReference(
        String sourcePath,
        String evidenceType,
        String sourceKind,
        String sourceReliability
) {
}
