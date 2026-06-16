package com.devcontext.domain.codemap;

public record CodeMapTestRelation(
        String testFile,
        String targetFile,
        String relationType
) {
}
