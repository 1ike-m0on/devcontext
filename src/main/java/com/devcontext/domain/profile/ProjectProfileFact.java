package com.devcontext.domain.profile;

import java.util.List;

public record ProjectProfileFact(
        String factType,
        String name,
        String value,
        List<ProjectProfileSourceReference> sourceReferences
) {
}
