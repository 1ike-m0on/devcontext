package com.devcontext.domain.codemap;

public record CodeMapDependencyEdge(
        String fromFile,
        String fromSymbol,
        String toFile,
        String toSymbol,
        String edgeType
) {
}
