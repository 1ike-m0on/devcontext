package com.devcontext.domain.codemap;

import java.util.List;

public record CodeSymbol(
        String name,
        String role,
        String module,
        String file,
        List<String> methods,
        List<String> endpoints,
        List<String> dependencies,
        List<String> technologies,
        List<String> domainTerms
) {
}
