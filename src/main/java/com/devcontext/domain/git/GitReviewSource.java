package com.devcontext.domain.git;

import java.util.List;

public record GitReviewSource(
        String sourceType,
        String label,
        String description,
        boolean available,
        boolean recommended,
        String baseRef,
        String compareRef,
        String currentBranch,
        int changedFileCount,
        List<String> changedFiles,
        String reason,
        int untrackedFileCount,
        List<String> untrackedFiles,
        String warning
) {
    public GitReviewSource(
            String sourceType,
            String label,
            String description,
            boolean available,
            boolean recommended,
            String baseRef,
            String compareRef,
            String currentBranch,
            int changedFileCount,
            List<String> changedFiles,
            String reason
    ) {
        this(sourceType, label, description, available, recommended, baseRef, compareRef, currentBranch,
                changedFileCount, changedFiles, reason, 0, List.of(), null);
    }
}
