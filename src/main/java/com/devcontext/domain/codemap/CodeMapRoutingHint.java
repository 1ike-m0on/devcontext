package com.devcontext.domain.codemap;

public record CodeMapRoutingHint(
        String kind,
        String name,
        String file,
        String owner
) {
}
