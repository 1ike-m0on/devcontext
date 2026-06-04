package com.devcontext.domain.git;

import java.util.List;

public record GitDiff(
        String text,
        List<String> changedFiles,
        String hash,
        boolean truncated,
        String sourceType,
        String baseRef,
        String compareRef
) {
    public GitDiff(String text, List<String> changedFiles, String hash, boolean truncated) {
        this(text, changedFiles, hash, truncated, "manual", "provided", "provided");
    }
}
