package com.devcontext.domain.project;

import java.util.List;

public record ScannedJavaFile(
        String path,
        String packageName,
        String className,
        List<String> annotations,
        List<String> methods
) {
}
