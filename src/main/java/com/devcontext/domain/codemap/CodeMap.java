package com.devcontext.domain.codemap;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record CodeMap(
        String schemaVersion,
        String generatedAt,
        String projectName,
        String rootPath,
        String language,
        String framework,
        String buildTool,
        String gitBranch,
        String gitCommit,
        List<CodeModule> modules,
        List<CodeEntrypoint> entrypoints,
        List<CodeSymbol> symbols,
        List<CodeEndpoint> endpoints,
        List<CodeDependency> dependencies,
        List<CodeTechnologySignal> technologies,
        List<CodeRuntimeComponent> runtimeComponents,
        List<CodeDomainTerm> domainTerms,
        Map<String, String> commands,
        List<String> configs,
        List<String> testRoots,
        List<String> docs,
        List<String> todos,
        List<CodeMapFileEntry> files,
        List<CodeMapConfigKey> configKeys,
        List<CodeMapRoutingHint> sqlHints,
        List<CodeMapRoutingHint> mapperHints,
        List<CodeMapRoutingHint> entityHints,
        List<CodeMapTestRelation> testRelations,
        List<CodeMapDependencyEdge> dependencyEdges
) {
    public static final String CURRENT_SCHEMA_VERSION = "2";
    public static final String LEGACY_SCHEMA_VERSION = "1";

    public CodeMap {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? LEGACY_SCHEMA_VERSION
                : schemaVersion.trim();
        modules = copyList(modules);
        entrypoints = copyList(entrypoints);
        symbols = copyList(symbols);
        endpoints = copyList(endpoints);
        dependencies = copyList(dependencies);
        technologies = copyList(technologies);
        runtimeComponents = copyList(runtimeComponents);
        domainTerms = copyList(domainTerms);
        commands = commands == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(commands));
        configs = copyList(configs);
        testRoots = copyList(testRoots);
        docs = copyList(docs);
        todos = copyList(todos);
        files = copyList(files);
        configKeys = copyList(configKeys);
        sqlHints = copyList(sqlHints);
        mapperHints = copyList(mapperHints);
        entityHints = copyList(entityHints);
        testRelations = copyList(testRelations);
        dependencyEdges = copyList(dependencyEdges);
    }

    public CodeMap(
            String generatedAt,
            String projectName,
            String rootPath,
            String language,
            String framework,
            String buildTool,
            String gitBranch,
            String gitCommit,
            List<CodeModule> modules,
            List<CodeEntrypoint> entrypoints,
            List<CodeSymbol> symbols,
            List<CodeEndpoint> endpoints,
            List<CodeDependency> dependencies,
            List<CodeTechnologySignal> technologies,
            List<CodeRuntimeComponent> runtimeComponents,
            List<CodeDomainTerm> domainTerms,
            Map<String, String> commands,
            List<String> configs,
            List<String> testRoots,
            List<String> docs,
            List<String> todos
    ) {
        this(
                LEGACY_SCHEMA_VERSION,
                generatedAt,
                projectName,
                rootPath,
                language,
                framework,
                buildTool,
                gitBranch,
                gitCommit,
                modules,
                entrypoints,
                symbols,
                endpoints,
                dependencies,
                technologies,
                runtimeComponents,
                domainTerms,
                commands,
                configs,
                testRoots,
                docs,
                todos,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @JsonIgnore
    public boolean isV2() {
        return CURRENT_SCHEMA_VERSION.equals(schemaVersion);
    }

    private static <T> List<T> copyList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
