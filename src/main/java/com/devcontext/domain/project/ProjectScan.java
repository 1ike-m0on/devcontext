package com.devcontext.domain.project;

import java.util.List;
import java.util.Map;

public record ProjectScan(
        String rootPath,
        String language,
        String framework,
        String buildTool,
        boolean springBoot,
        String gitBranch,
        String gitCommit,
        List<String> directories,
        List<String> sourceRoots,
        List<String> resourceRoots,
        List<String> testRoots,
        List<String> configFiles,
        List<String> docs,
        List<ScannedJavaFile> javaFiles,
        Map<String, String> commands,
        List<String> todos
) {
}
