package com.devcontext.domain.context;

public record ContextAssetDefinition(
        String type,
        String relativePath,
        boolean generated,
        boolean manual
) {
}
