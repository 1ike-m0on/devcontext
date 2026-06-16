package com.devcontext.domain.project;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        List<String> sqlFiles,
        List<String> mapperFiles,
        List<String> testFiles,
        List<String> docs,
        List<ScannedJavaFile> javaFiles,
        Map<String, String> commands,
        List<String> todos
) {
    public ProjectScan {
        directories = copyList(directories);
        sourceRoots = copyList(sourceRoots);
        resourceRoots = copyList(resourceRoots);
        testRoots = copyList(testRoots);
        configFiles = copyList(configFiles);
        sqlFiles = copyList(sqlFiles);
        mapperFiles = copyList(mapperFiles);
        testFiles = copyList(testFiles);
        docs = copyList(docs);
        javaFiles = copyList(javaFiles);
        commands = commands == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
        todos = copyList(todos);
    }

    public ProjectScan(
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
        this(
                rootPath,
                language,
                framework,
                buildTool,
                springBoot,
                gitBranch,
                gitCommit,
                directories,
                sourceRoots,
                resourceRoots,
                testRoots,
                configFiles,
                List.of(),
                List.of(),
                List.of(),
                docs,
                javaFiles,
                commands,
                todos
        );
    }

    private static <T> List<T> copyList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
