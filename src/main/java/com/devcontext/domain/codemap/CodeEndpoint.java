package com.devcontext.domain.codemap;

import java.util.List;

public record CodeEndpoint(
        String httpMethod,
        String path,
        String handlerMethod,
        String className,
        String module,
        String file,
        List<String> domainTerms
) {
}
