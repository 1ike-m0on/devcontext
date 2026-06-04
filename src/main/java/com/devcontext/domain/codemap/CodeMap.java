package com.devcontext.domain.codemap;

import java.util.List;
import java.util.Map;

public record CodeMap(
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
}
