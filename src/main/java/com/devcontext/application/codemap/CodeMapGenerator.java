package com.devcontext.application.codemap;

import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeModule;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.devcontext.domain.project.ScannedJavaFile;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CodeMapGenerator {

    public CodeMap generate(Project project, ProjectScan scan) {
        return new CodeMap(
                Instant.now().toString(),
                project.name(),
                project.rootPath(),
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                scan.gitBranch(),
                scan.gitCommit(),
                modules(scan),
                entrypoints(scan),
                scan.commands(),
                scan.configFiles(),
                scan.testRoots(),
                scan.docs(),
                scan.todos()
        );
    }

    private List<CodeModule> modules(ProjectScan scan) {
        Map<String, List<ScannedJavaFile>> grouped = scan.javaFiles().stream()
                .collect(Collectors.groupingBy(this::modulePath, java.util.LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> new CodeModule(
                        moduleName(entry.getKey()),
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(ScannedJavaFile::className)
                                .sorted()
                                .limit(30)
                                .toList(),
                        inferResponsibility(entry.getKey())
                ))
                .sorted(Comparator.comparing(CodeModule::path))
                .limit(50)
                .toList();
    }

    private List<CodeEntrypoint> entrypoints(ProjectScan scan) {
        return scan.javaFiles().stream()
                .filter(this::isEntrypoint)
                .map(file -> new CodeEntrypoint(entrypointType(file), file.path(), file.methods()))
                .sorted(Comparator.comparing(CodeEntrypoint::file))
                .limit(50)
                .toList();
    }

    private boolean isEntrypoint(ScannedJavaFile file) {
        return file.annotations().contains("@RestController")
                || file.annotations().contains("@Controller")
                || file.annotations().contains("@SpringBootApplication")
                || file.className().endsWith("Controller")
                || file.className().endsWith("Application");
    }

    private String entrypointType(ScannedJavaFile file) {
        if (file.annotations().contains("@SpringBootApplication") || file.className().endsWith("Application")) {
            return "application";
        }
        if (file.annotations().contains("@RestController") || file.annotations().contains("@Controller")
                || file.className().endsWith("Controller")) {
            return "controller";
        }
        return "entrypoint";
    }

    private String modulePath(ScannedJavaFile file) {
        int lastSlash = file.path().lastIndexOf('/');
        return lastSlash < 0 ? "." : file.path().substring(0, lastSlash);
    }

    private String moduleName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    private String inferResponsibility(String path) {
        String lower = path.toLowerCase();
        if (lower.contains("controller") || lower.contains("web")) {
            return "HTTP/API entrypoints";
        }
        if (lower.contains("service") || lower.contains("application")) {
            return "Use case orchestration";
        }
        if (lower.contains("repository") || lower.contains("persistence")) {
            return "Data persistence";
        }
        if (lower.contains("config")) {
            return "Application configuration";
        }
        if (lower.contains("domain")) {
            return "Domain model";
        }
        return "TODO: Confirm module responsibility.";
    }
}
