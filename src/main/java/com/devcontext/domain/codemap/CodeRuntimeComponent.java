package com.devcontext.domain.codemap;

import java.util.List;

public record CodeRuntimeComponent(
        String type,
        String className,
        String module,
        String file,
        List<String> dependencies,
        List<String> technologies
) {
}
