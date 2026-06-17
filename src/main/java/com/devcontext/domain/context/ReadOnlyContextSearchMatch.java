package com.devcontext.domain.context;

public record ReadOnlyContextSearchMatch(
        String relativePath,
        int lineNumber,
        String snippet
) {
    public ReadOnlyContextSearchMatch {
        relativePath = relativePath == null ? "" : relativePath;
        snippet = snippet == null ? "" : snippet;
    }
}
