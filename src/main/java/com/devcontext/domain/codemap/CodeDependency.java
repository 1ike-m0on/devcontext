package com.devcontext.domain.codemap;

public record CodeDependency(
        String fromClass,
        String fromFile,
        String toType,
        String module
) {
}
