package com.devcontext.domain.profile;

import java.time.Instant;
import java.util.List;

public record ProjectProfileFreshnessSummary(
        String freshnessStatus,
        Instant lastBuiltAt,
        int sourceCount,
        List<String> staleReasons,
        List<String> warnings
) {

    public ProjectProfileFreshnessSummary {
        staleReasons = staleReasons == null ? List.of() : List.copyOf(staleReasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
