package com.devcontext.application.context;

import com.devcontext.application.codemap.CodeMapGenerator;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.context.ContextAsset;
import com.devcontext.domain.context.ContextAssetDefinition;
import com.devcontext.domain.context.ContextAssetWriteReport;
import com.devcontext.domain.context.ContextDocument;
import com.devcontext.domain.context.ContextDocumentStatus;
import com.devcontext.domain.context.ContextGenerationResult;
import com.devcontext.domain.context.ProjectContextStatus;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.devcontext.ports.context.ContextAssetStore;
import com.devcontext.ports.context.ContextDocumentRepository;
import com.devcontext.ports.project.ProjectScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ProjectContextAssetApplicationService {

    private static final List<ContextAssetDefinition> ASSET_DEFINITIONS = List.of(
            new ContextAssetDefinition("AGENTS", "AGENTS.md", true, false),
            new ContextAssetDefinition("AI_README", ".ai/AI_README.md", true, false),
            new ContextAssetDefinition("CODE_MAP", ".ai/code-map.json", true, false),
            new ContextAssetDefinition("PROJECT_STRUCTURE", ".ai/generated/project-structure.md", true, false),
            new ContextAssetDefinition("TECH_ARCHITECTURE", ".ai/generated/tech-architecture.md", true, false),
            new ContextAssetDefinition("DEV_GUIDE", ".ai/generated/dev-guide.md", true, false),
            new ContextAssetDefinition("CORE_FLOWS", ".ai/generated/core-flows.md", true, false),
            new ContextAssetDefinition("BUSINESS_CONTEXT", ".ai/manual/business-context.md", false, true),
            new ContextAssetDefinition("CODING_PREFERENCES", ".ai/manual/coding-preferences.md", false, true),
            new ContextAssetDefinition("DECISIONS", ".ai/manual/decisions.md", false, true),
            new ContextAssetDefinition("PITFALLS", ".ai/manual/pitfalls.md", false, true)
    );

    private final ProjectApplicationService projectService;
    private final ProjectScanner projectScanner;
    private final CodeMapGenerator codeMapGenerator;
    private final ContextAssetStore contextAssetStore;
    private final ContextDocumentRepository contextDocumentRepository;
    private final ObjectMapper objectMapper;

    public ProjectContextAssetApplicationService(
            ProjectApplicationService projectService,
            ProjectScanner projectScanner,
            CodeMapGenerator codeMapGenerator,
            ContextAssetStore contextAssetStore,
            ContextDocumentRepository contextDocumentRepository,
            ObjectMapper objectMapper
    ) {
        this.projectService = projectService;
        this.projectScanner = projectScanner;
        this.codeMapGenerator = codeMapGenerator;
        this.contextAssetStore = contextAssetStore;
        this.contextDocumentRepository = contextDocumentRepository;
        this.objectMapper = objectMapper;
    }

    public ContextGenerationResult generate(Long projectId, boolean overwriteGenerated, boolean overwriteManual) {
        Project project = projectService.getProject(projectId);
        ProjectScan scan = projectScanner.scan(project.rootPath());
        CodeMap codeMap = codeMapGenerator.generate(project, scan);
        List<ContextAsset> assets = buildAssets(project, scan, codeMap);
        List<ContextAssetWriteReport> reports = contextAssetStore.writeAssets(
                project.rootPath(),
                assets,
                overwriteGenerated,
                overwriteManual
        );
        reports.forEach(report -> saveDocument(projectId, report, scan.gitCommit()));
        return new ContextGenerationResult(
                projectId,
                reports.stream()
                        .filter(report -> report.generated() && report.written())
                        .map(ContextAssetWriteReport::relativePath)
                        .toList(),
                reports.stream()
                        .filter(report -> report.generated() && report.skipped())
                        .map(ContextAssetWriteReport::relativePath)
                        .toList(),
                reports.stream()
                        .filter(report -> report.manual() && report.written())
                        .map(ContextAssetWriteReport::relativePath)
                        .toList(),
                reports.stream()
                        .filter(report -> report.manual() && report.skipped())
                        .map(ContextAssetWriteReport::relativePath)
                        .toList(),
                scan.todos()
        );
    }

    public ProjectContextStatus getStatus(Long projectId) {
        Project project = projectService.getProject(projectId);
        List<ContextDocumentStatus> filesystemStatuses = contextAssetStore.inspect(project.rootPath(), ASSET_DEFINITIONS);
        Map<String, ContextDocument> records = contextDocumentRepository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ContextDocument::filePath, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<ContextDocumentStatus> documents = filesystemStatuses.stream()
                .map(status -> merge(status, records.get(status.path())))
                .sorted(Comparator.comparing(ContextDocumentStatus::path))
                .toList();
        return new ProjectContextStatus(projectId, documents);
    }

    private List<ContextAsset> buildAssets(Project project, ProjectScan scan, CodeMap codeMap) {
        List<ContextAsset> assets = new ArrayList<>();
        assets.add(generated("AGENTS", "AGENTS.md", renderAgents(project, scan)));
        assets.add(generated("AI_README", ".ai/AI_README.md", renderAiReadme(project, scan)));
        assets.add(generated("CODE_MAP", ".ai/code-map.json", renderCodeMap(codeMap)));
        assets.add(generated("PROJECT_STRUCTURE", ".ai/generated/project-structure.md", renderProjectStructure(project, scan)));
        assets.add(generated("TECH_ARCHITECTURE", ".ai/generated/tech-architecture.md", renderTechArchitecture(project, scan)));
        assets.add(generated("DEV_GUIDE", ".ai/generated/dev-guide.md", renderDevGuide(scan)));
        assets.add(generated("CORE_FLOWS", ".ai/generated/core-flows.md", renderCoreFlows(project, scan)));
        assets.add(manual("BUSINESS_CONTEXT", ".ai/manual/business-context.md", """
                # Business Context

                TODO: Describe what this project does, who uses it, and what outcomes matter.
                """));
        assets.add(manual("CODING_PREFERENCES", ".ai/manual/coding-preferences.md", """
                # Coding Preferences

                TODO: Record project-specific coding style, naming rules, testing habits, and AI preferences.
                """));
        assets.add(manual("DECISIONS", ".ai/manual/decisions.md", """
                # Engineering Decisions

                TODO: Record important trade-offs, alternatives, evidence, and current status.
                """));
        assets.add(manual("PITFALLS", ".ai/manual/pitfalls.md", """
                # Pitfalls

                TODO: Record bugs, fragile areas, migration notes, and things AI should be careful with.
                """));
        return assets;
    }

    private ContextAsset generated(String type, String path, String content) {
        return new ContextAsset(type, path, content, true, false);
    }

    private ContextAsset manual(String type, String path, String content) {
        return new ContextAsset(type, path, content, false, true);
    }

    private void saveDocument(Long projectId, ContextAssetWriteReport report, String sourceCommit) {
        Instant now = Instant.now();
        contextDocumentRepository.upsert(new ContextDocument(
                null,
                projectId,
                report.type(),
                report.relativePath(),
                report.generated(),
                report.status(),
                "unknown".equals(sourceCommit) ? null : sourceCommit,
                now,
                now
        ));
    }

    private ContextDocumentStatus merge(ContextDocumentStatus status, ContextDocument document) {
        if (document == null) {
            return status;
        }
        return new ContextDocumentStatus(
                status.type(),
                status.path(),
                status.exists(),
                document.generated(),
                status.exists() ? document.status() : "missing",
                document.sourceCommit(),
                document.updatedAt()
        );
    }

    private String renderAgents(Project project, ProjectScan scan) {
        return """
                # Project AI Context Entry

                ## Project Overview

                TODO: Summarize the project purpose in one sentence.

                ## Tech Stack

                - Language: %s
                - Framework: %s
                - Build tool: %s

                ## Common Commands

                %s

                ## Key Directories

                %s

                ## AI Context

                Read `.ai/AI_README.md` for detailed generated and manually maintained context.

                ## Boundaries

                TODO: Add project rules, forbidden changes, and areas that require extra care.
                """.formatted(
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                renderCommandBullets(scan.commands()),
                renderBulletList(scan.directories())
        );
    }

    private String renderAiReadme(Project project, ProjectScan scan) {
        return """
                # AI_README

                ## Project Overview

                - Name: %s
                - Root path: `%s`
                - Language: %s
                - Framework: %s
                - Git branch: %s
                - Git commit: %s
                - Generated at: %s

                ## Generated Documents

                - [x] [Project structure](./generated/project-structure.md)
                - [x] [Tech architecture](./generated/tech-architecture.md)
                - [x] [Dev guide](./generated/dev-guide.md)
                - [x] [Core flows](./generated/core-flows.md)
                - [x] [Code map](./code-map.json)

                ## Manual Documents

                - [ ] [Business context](./manual/business-context.md)
                - [ ] [Coding preferences](./manual/coding-preferences.md)
                - [ ] [Engineering decisions](./manual/decisions.md)
                - [ ] [Pitfalls](./manual/pitfalls.md)

                ## TODO

                %s
                """.formatted(
                project.name(),
                project.rootPath(),
                scan.language(),
                scan.framework(),
                scan.gitBranch(),
                scan.gitCommit(),
                Instant.now(),
                renderBulletList(scan.todos())
        );
    }

    private String renderProjectStructure(Project project, ProjectScan scan) {
        return """
                # Project Structure

                ## Project

                - Name: %s
                - Root path: `%s`

                ## Directories

                %s

                ## Source Roots

                %s

                ## Resource Roots

                %s

                ## Test Roots

                %s

                ## Docs

                %s
                """.formatted(
                project.name(),
                project.rootPath(),
                renderBulletList(scan.directories()),
                renderBulletList(scan.sourceRoots()),
                renderBulletList(scan.resourceRoots()),
                renderBulletList(scan.testRoots()),
                renderBulletList(scan.docs())
        );
    }

    private String renderTechArchitecture(Project project, ProjectScan scan) {
        return """
                # Tech Architecture

                ## Detected Stack

                - Language: %s
                - Framework: %s
                - Build tool: %s
                - Spring Boot: %s

                ## Config Files

                %s

                ## Java Files

                %s

                ## Notes

                This document is generated from repository structure and file markers. Confirm business architecture manually.
                """.formatted(
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                scan.springBoot() ? "yes" : "no",
                renderBulletList(scan.configFiles()),
                renderBulletList(scan.javaFiles().stream().map(file -> file.path() + " (" + file.className() + ")").toList())
        );
    }

    private String renderDevGuide(ProjectScan scan) {
        return """
                # Dev Guide

                ## Commands

                %s

                ## Config Files

                %s

                ## Test Roots

                %s

                ## Notes

                Commands are inferred from project files. Confirm them when the project has custom scripts or profiles.
                """.formatted(
                renderCommandBullets(scan.commands()),
                renderBulletList(scan.configFiles()),
                renderBulletList(scan.testRoots())
        );
    }

    private String renderCoreFlows(Project project, ProjectScan scan) {
        return """
                # Core Flows

                ## Detected Entrypoints

                %s

                ## Manual Confirmation Needed

                TODO: Describe the most important user-facing or system-facing workflows.
                TODO: Link each flow to controllers, services, jobs, commands, or scheduled tasks.
                """.formatted(
                renderBulletList(scan.javaFiles().stream()
                        .filter(file -> file.annotations().contains("@RestController")
                                || file.annotations().contains("@Controller")
                                || file.annotations().contains("@SpringBootApplication")
                                || file.className().endsWith("Controller")
                                || file.className().endsWith("Application"))
                        .map(file -> file.path() + " - " + String.join(", ", file.methods()))
                        .toList())
        );
    }

    private String renderCodeMap(CodeMap codeMap) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(codeMap) + System.lineSeparator();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to render code map", e);
        }
    }

    private String renderCommandBullets(Map<String, String> commands) {
        if (commands.isEmpty()) {
            return "- TODO: Confirm build, test, and run commands.";
        }
        return commands.entrySet().stream()
                .map(entry -> "- " + entry.getKey() + ": `" + entry.getValue() + "`")
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String renderBulletList(List<String> values) {
        if (values.isEmpty()) {
            return "- TODO: Confirm manually.";
        }
        return values.stream()
                .map(value -> "- `" + value + "`")
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
