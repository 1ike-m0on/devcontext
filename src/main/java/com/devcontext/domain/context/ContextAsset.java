package com.devcontext.domain.context;

public record ContextAsset(
        String type,
        String relativePath,
        String content,
        boolean generated,
        boolean manual
) {
}
