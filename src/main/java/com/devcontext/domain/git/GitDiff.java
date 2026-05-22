package com.devcontext.domain.git;

import java.util.List;

public record GitDiff(
        String text,
        List<String> changedFiles,
        String hash,
        boolean truncated
) {
}
