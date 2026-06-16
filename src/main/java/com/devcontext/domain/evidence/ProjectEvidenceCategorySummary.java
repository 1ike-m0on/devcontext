package com.devcontext.domain.evidence;

import java.util.List;

public record ProjectEvidenceCategorySummary(
        String category,
        int count,
        String reason,
        List<String> paths
) {
    public ProjectEvidenceCategorySummary {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}
