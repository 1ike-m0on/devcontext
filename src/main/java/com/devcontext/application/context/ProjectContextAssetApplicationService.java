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
import com.devcontext.domain.context.ContextQualityIssue;
import com.devcontext.domain.context.ContextQualitySummary;
import com.devcontext.domain.context.ProjectContextStatus;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.devcontext.domain.project.ScannedJavaFile;
import com.devcontext.ports.context.ContextAssetStore;
import com.devcontext.ports.context.ContextDocumentRepository;
import com.devcontext.ports.project.ProjectScanner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        ContextQualitySummary quality = evaluateQuality(project.rootPath(), documents);
        return new ProjectContextStatus(projectId, documents, quality);
    }

    private ContextQualitySummary evaluateQuality(String rootPath, List<ContextDocumentStatus> documents) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        List<ContextQualityIssue> issues = new ArrayList<>();
        int existingDocuments = 0;
        int missingCount = 0;
        int todoCount = 0;
        int score = 100;
        boolean coreFlowsUnconfirmed = false;

        for (ContextDocumentStatus document : documents) {
            if (!document.exists()) {
                missingCount++;
                score -= document.generated() ? 18 : 8;
                issues.add(new ContextQualityIssue(
                        document.generated() ? "warning" : "info",
                        document.type(),
                        document.path(),
                        document.generated() ? "上下文资产缺失" : "人工资料未创建",
                        document.generated()
                                ? "自动生成文档不存在，后续问答和审查会缺少项目事实。"
                                : "人工维护文档尚未创建，系统只能依赖代码结构做推断。",
                        document.generated()
                                ? "重新生成上下文资产。"
                                : "需要沉淀业务背景、偏好、决策或踩坑经验时再补充。"
                ));
                continue;
            }

            existingDocuments++;
            String content = readContextAsset(root, document.path());
            int documentTodoCount = countTodoLines(content);
            todoCount += documentTodoCount;

            if ("CORE_FLOWS".equals(document.type())) {
                boolean hasEvidence = hasCoreFlowEvidence(content);
                if (documentTodoCount > 0 || !hasEvidence) {
                    coreFlowsUnconfirmed = true;
                    score -= 28;
                    issues.add(new ContextQualityIssue(
                            "warning",
                            document.type(),
                            document.path(),
                            "核心流程未确认",
                            "系统目前只能从入口类、代码地图或历史评审里推断核心流程，不能把回答当成已确认业务事实。",
                            "补充 1-3 条真实核心流程，并标注对应 Controller、Service、Job 或关键文件。"
                    ));
                }
                continue;
            }

            if (documentTodoCount > 0) {
                score -= document.generated() ? Math.min(16, documentTodoCount * 4) : Math.min(8, documentTodoCount * 2);
                issues.add(new ContextQualityIssue(
                        document.generated() ? "warning" : "info",
                        document.type(),
                        document.path(),
                        document.generated() ? "生成文档仍有待确认项" : "人工文档仍是模板",
                        document.generated()
                                ? "生成文档包含 TODO，说明该部分不是可靠事实。"
                                : "人工文档还没有沉淀业务背景或个人偏好。",
                        document.generated()
                                ? "打开该文件补齐 TODO，或重新扫描后人工确认。"
                                : "按需补充后再把它纳入知识库索引。"
                ));
            }
        }

        score = Math.max(0, Math.min(100, score));
        String level;
        if (coreFlowsUnconfirmed || score < 55) {
            level = "low";
        } else if (!issues.isEmpty() || score < 85) {
            level = "medium";
        } else {
            level = "high";
        }

        return new ContextQualitySummary(
                level,
                score,
                existingDocuments,
                documents.size(),
                missingCount,
                todoCount,
                issues
        );
    }

    private String readContextAsset(Path root, String relativePath) {
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return "";
        }
        try {
            return Files.readString(target);
        } catch (IOException e) {
            return "";
        }
    }

    private int countTodoLines(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return (int) content.lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains("todo"))
                .count();
    }

    private boolean hasCoreFlowEvidence(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        long evidenceLines = content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("- ") && !line.toLowerCase(Locale.ROOT).contains("todo"))
                .filter(line -> line.contains(".java")
                        || line.matches("^-\\s+(GET|POST|PUT|DELETE|PATCH|ANY)\\s+/.+"))
                .count();
        String lowerContent = content.toLowerCase(Locale.ROOT);
        boolean hasCodeAnchor = lowerContent.contains(".java")
                || lowerContent.contains("controller")
                || lowerContent.contains("service")
                || lowerContent.contains("runner")
                || lowerContent.contains("job");
        boolean hasReadmeFlow = content.contains("## Product Flow From README")
                && content.lines().map(String::trim).anyMatch(line -> line.matches("^\\d+\\.\\s+.+"));
        return evidenceLines >= 2 || (evidenceLines >= 1 && hasCodeAnchor) || (hasReadmeFlow && hasCodeAnchor);
    }

    private List<ContextAsset> buildAssets(Project project, ProjectScan scan, CodeMap codeMap) {
        ProjectDocSummary docSummary = summarizeProjectDocs(scan);
        List<ContextAsset> assets = new ArrayList<>();
        assets.add(generated("AGENTS", "AGENTS.md", renderAgents(project, scan, docSummary)));
        assets.add(generated("AI_README", ".ai/AI_README.md", renderAiReadme(project, scan, docSummary)));
        assets.add(generated("CODE_MAP", ".ai/code-map.json", renderCodeMap(codeMap)));
        assets.add(generated("PROJECT_STRUCTURE", ".ai/generated/project-structure.md", renderProjectStructure(project, scan)));
        assets.add(generated("TECH_ARCHITECTURE", ".ai/generated/tech-architecture.md", renderTechArchitecture(project, scan, docSummary)));
        assets.add(generated("DEV_GUIDE", ".ai/generated/dev-guide.md", renderDevGuide(scan, docSummary)));
        assets.add(generated("CORE_FLOWS", ".ai/generated/core-flows.md", renderCoreFlows(project, scan, docSummary)));
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

    private String renderAgents(Project project, ProjectScan scan, ProjectDocSummary docSummary) {
        return """
                # Project AI Context Entry

                ## Project Overview

                %s

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

                %s
                """.formatted(
                docSummary.overviewOrTodo(),
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                renderCommandBullets(scan.commands()),
                renderBulletList(scan.directories()),
                docSummary.scopeOrDefault()
        );
    }

    private String renderAiReadme(Project project, ProjectScan scan, ProjectDocSummary docSummary) {
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

                ## Project Summary

                %s

                ## Detected Capabilities

                %s

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
                docSummary.overviewOrTodo(),
                renderBulletList(docSummary.features()),
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

    private String renderTechArchitecture(Project project, ProjectScan scan, ProjectDocSummary docSummary) {
        return """
                # Tech Architecture

                ## Detected Stack

                - Language: %s
                - Framework: %s
                - Build tool: %s
                - Spring Boot: %s

                ## Config Files

                %s

                ## README Tech Stack

                %s

                ## Detected Modules

                %s

                ## Runtime Components

                %s

                ## External Infrastructure Signals

                %s

                ## HTTP API Entrypoints

                %s

                ## Java Files

                %s

                ## Notes

                This document is generated from README, repository structure, Spring annotations, route mappings, dependencies, and file markers. Confirm business semantics manually when a flow affects product or money movement.
                """.formatted(
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                scan.springBoot() ? "yes" : "no",
                renderBulletList(scan.configFiles()),
                renderBulletList(docSummary.techStack()),
                renderModuleSummary(scan),
                renderRuntimeComponents(scan),
                renderTechnologySignals(scan),
                renderEndpointBullets(scan),
                renderBulletList(scan.javaFiles().stream().map(file -> file.path() + " (" + file.className() + ")").toList())
        );
    }

    private String renderDevGuide(ProjectScan scan, ProjectDocSummary docSummary) {
        return """
                # Dev Guide

                ## Commands

                %s

                ## README Quick Start

                %s

                ## Config Files

                %s

                ## Test Roots

                %s

                ## Notes

                Commands are inferred from project files and README quick-start sections. Confirm profile-specific credentials and ports before running destructive reset commands.
                """.formatted(
                renderCommandBullets(scan.commands()),
                renderBulletList(docSummary.quickStartCommands()),
                renderBulletList(scan.configFiles()),
                renderBulletList(scan.testRoots())
        );
    }

    private String renderCoreFlows(Project project, ProjectScan scan, ProjectDocSummary docSummary) {
        List<String> inferredFlows = inferCoreFlows(scan);
        List<String> dataEvidence = findDataAndResourceEvidence(scan);
        String confidenceNote = inferredFlows.isEmpty() && dataEvidence.isEmpty()
                ? """
                ## Manual Confirmation Needed

                TODO: Describe the most important user-facing or system-facing workflows.
                TODO: Link each flow to controllers, services, jobs, commands, or scheduled tasks.
                """
                : """
                ## Confidence

                - README/demo steps are treated as documented product intent.
                - Code traces are deterministic heuristics from Spring endpoints, class names, dependencies, schedulers, and messaging components.
                - Manually confirm business wording before using this file as final product documentation.
                """;
        return """
                # Core Flows

                ## Product Flow From README

                %s

                ## Inferred Backend Flow Candidates

                %s

                ## Data Access And Resource Evidence

                %s

                ## HTTP Entrypoints

                %s

                ## Background And Async Flows

                %s

                ## Technology Signals

                %s

                %s
                """.formatted(
                renderNumberedListOrFallback(docSummary.demoFlow(), "- No README demo flow was found. Use the code-trace candidates below as generated evidence, then confirm product wording manually."),
                renderBulletListOrFallback(inferredFlows, "- No HTTP endpoint or runtime flow candidate was detected from code."),
                renderBulletListOrFallback(dataEvidence, "- No SQL, mapper, repository, entity, Lua, or resource evidence was detected."),
                renderBulletListOrFallback(endpointBullets(scan), "- No HTTP endpoint was detected."),
                renderBulletListOrFallback(runtimeComponentBullets(scan), "- No background, async, scheduler, runner, aspect, filter, or interceptor component was detected."),
                renderTechnologySignalsOrFallback(scan, "- No external infrastructure technology signal was detected."),
                confidenceNote
        );
    }

    private ProjectDocSummary summarizeProjectDocs(ProjectScan scan) {
        String readme = readFirstExisting(scan.rootPath(), "README.zh-CN.md", "README.md", "README.en.md");
        return new ProjectDocSummary(
                extractOverview(readme),
                extractBulletsUnderHeadings(readme, List.of("功能概览", "What It Provides", "Features")),
                extractNumberedUnderHeadings(readme, List.of("Demo Flow", "演示流程")),
                extractCommands(readme),
                extractTableRowsUnderHeadings(readme, List.of("技术栈", "Tech Stack")),
                extractParagraphUnderHeadings(readme, List.of("项目定位", "Scope"))
        );
    }

    private String readFirstExisting(String rootPath, String... candidates) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        for (String candidate : candidates) {
            Path path = root.resolve(candidate).toAbsolutePath().normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            try {
                return Files.readString(path);
            } catch (IOException ignored) {
                // Try the next candidate.
            }
        }
        return "";
    }

    private String extractOverview(String markdown) {
        if (markdown.isBlank()) {
            return "";
        }
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : markdown.lines().toList()) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                break;
            }
            if (line.isBlank()) {
                if (!current.isEmpty()) {
                    paragraphs.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            if (line.startsWith("#")
                    || line.startsWith("[")
                    || line.startsWith("![")
                    || line.startsWith("|")
                    || line.startsWith("[![")) {
                continue;
            }
            if (!current.isEmpty()) {
                current.append(" ");
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            paragraphs.add(current.toString().trim());
        }
        return paragraphs.stream().limit(2).collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
    }

    private List<String> extractBulletsUnderHeadings(String markdown, List<String> headingNames) {
        return extractSectionLines(markdown, headingNames).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).trim())
                .filter(line -> !line.isBlank())
                .limit(20)
                .toList();
    }

    private List<String> extractNumberedUnderHeadings(String markdown, List<String> headingNames) {
        return extractSectionLines(markdown, headingNames).stream()
                .map(String::trim)
                .filter(line -> line.matches("\\d+\\.\\s+.*"))
                .map(line -> line.replaceFirst("\\d+\\.\\s+", "").trim())
                .filter(line -> !line.isBlank())
                .limit(20)
                .toList();
    }

    private List<String> extractCommands(String markdown) {
        List<String> commands = new ArrayList<>();
        boolean inFence = false;
        for (String rawLine : extractSectionLines(markdown, List.of("快速启动", "Quick Start"))) {
            String line = rawLine.trim();
            if (line.startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence && !line.isBlank()) {
                commands.add(line);
            }
        }
        return commands.stream().distinct().limit(12).toList();
    }

    private List<String> extractTableRowsUnderHeadings(String markdown, List<String> headingNames) {
        return extractSectionLines(markdown, headingNames).stream()
                .map(String::trim)
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .filter(line -> !line.contains("---"))
                .skip(1)
                .map(line -> line.replaceAll("^\\|", "").replaceAll("\\|$", "").replace("|", ":").trim())
                .filter(line -> !line.isBlank())
                .limit(20)
                .toList();
    }

    private String extractParagraphUnderHeadings(String markdown, List<String> headingNames) {
        return extractSectionLines(markdown, headingNames).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))
                .limit(3)
                .collect(Collectors.joining(" "));
    }

    private List<String> extractSectionLines(String markdown, List<String> headingNames) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        Set<String> normalizedHeadings = headingNames.stream()
                .map(this::normalizeHeading)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> lines = new ArrayList<>();
        boolean capturing = false;
        for (String rawLine : markdown.lines().toList()) {
            String line = rawLine.trim();
            if (line.startsWith("## ")) {
                String heading = normalizeHeading(line.replaceFirst("^#+\\s+", ""));
                capturing = normalizedHeadings.contains(heading);
                continue;
            }
            if (capturing && line.startsWith("## ")) {
                break;
            }
            if (capturing) {
                lines.add(rawLine);
            }
        }
        return lines;
    }

    private String normalizeHeading(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", "");
    }

    private String renderModuleSummary(ProjectScan scan) {
        Map<String, List<String>> modules = scan.javaFiles().stream()
                .collect(Collectors.groupingBy(this::businessModule, LinkedHashMap::new,
                        Collectors.mapping(file -> file.className() + " (" + classRole(file) + ")", Collectors.toList())));
        if (modules.isEmpty()) {
            return "- TODO: Confirm module structure manually.";
        }
        return modules.entrySet().stream()
                .map(entry -> "- `" + entry.getKey() + "`: " + entry.getValue().stream().limit(8).collect(Collectors.joining(", ")))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String businessModule(com.devcontext.domain.project.ScannedJavaFile file) {
        String path = file.path().replace('\\', '/').replace("src/main/java/", "");
        String[] parts = path.split("/");
        if (parts.length <= 1) {
            return "root";
        }
        Set<String> roleSegments = Set.of(
                "controller", "web", "service", "repository", "mapper", "dao", "entity",
                "model", "domain", "config", "configuration", "job", "task", "scheduler",
                "consumer", "publisher", "producer", "listener", "aspect", "filter", "interceptor"
        );
        for (int i = parts.length - 2; i >= 0; i--) {
            String segment = parts[i].toLowerCase(Locale.ROOT);
            if (!roleSegments.contains(segment) && !segment.isBlank()) {
                return segment;
            }
        }
        return "root";
    }

    private String classRole(com.devcontext.domain.project.ScannedJavaFile file) {
        String lowerPath = file.path().toLowerCase(Locale.ROOT);
        String className = file.className();
        if (file.annotations().contains("@RestController") || className.endsWith("Controller")) {
            return "HTTP API";
        }
        if (lowerPath.contains("/service/") || file.annotations().contains("@Service")) {
            return "service";
        }
        if (lowerPath.contains("/mapper/") || className.endsWith("Mapper")) {
            return "persistence mapper";
        }
        if (lowerPath.contains("/entity/") || className.endsWith("Entity")) {
            return "domain data";
        }
        if (file.annotations().contains("@Component")) {
            return "component";
        }
        return "support";
    }

    private String renderRuntimeComponents(ProjectScan scan) {
        return renderBulletList(runtimeComponentBullets(scan));
    }

    private List<String> runtimeComponentBullets(ProjectScan scan) {
        List<String> components = scan.javaFiles().stream()
                .filter(file -> file.annotations().contains("@Scheduled")
                        || file.className().endsWith("Scheduler")
                        || file.className().endsWith("Runner")
                        || file.className().endsWith("Consumer")
                        || file.className().endsWith("Publisher")
                        || file.className().endsWith("Aspect")
                        || file.className().endsWith("Filter")
                        || file.className().endsWith("Interceptor"))
                .map(file -> "`" + file.className() + "` (" + file.path() + ")"
                        + (file.dependencies().isEmpty() ? "" : " -> " + String.join(", ", file.dependencies())))
                .limit(40)
                .toList();
        return components;
    }

    private String renderTechnologySignals(ProjectScan scan) {
        return renderTechnologySignalsOrFallback(scan, "- TODO: Confirm infrastructure dependencies manually.");
    }

    private String renderTechnologySignalsOrFallback(ProjectScan scan, String fallback) {
        Map<String, List<String>> signals = new LinkedHashMap<>();
        for (com.devcontext.domain.project.ScannedJavaFile file : scan.javaFiles()) {
            for (String technology : file.technologies()) {
                signals.computeIfAbsent(technology, ignored -> new ArrayList<>()).add(file.className());
            }
        }
        if (signals.isEmpty()) {
            return fallback;
        }
        return signals.entrySet().stream()
                .map(entry -> "- `" + entry.getKey() + "`: " + entry.getValue().stream().distinct().limit(10).collect(Collectors.joining(", ")))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private String renderEndpointBullets(ProjectScan scan) {
        return renderBulletList(endpointBullets(scan));
    }

    private List<String> endpointBullets(ProjectScan scan) {
        List<String> endpoints = scan.javaFiles().stream()
                .flatMap(file -> file.endpoints().stream().map(endpoint -> endpoint + " (" + file.path() + ")"))
                .limit(80)
                .toList();
        return endpoints;
    }

    private List<String> inferCoreFlows(ProjectScan scan) {
        Map<String, ScannedJavaFile> byClassName = scan.javaFiles().stream()
                .collect(Collectors.toMap(ScannedJavaFile::className, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<ScannedJavaFile> entrypoints = scan.javaFiles().stream()
                .filter(file -> !file.endpoints().isEmpty()
                        || file.annotations().contains("@RestController")
                        || file.annotations().contains("@Controller")
                        || file.className().endsWith("Controller"))
                .sorted(Comparator.comparing(ScannedJavaFile::path))
                .limit(40)
                .toList();
        List<String> flows = new ArrayList<>();
        for (ScannedJavaFile entrypoint : entrypoints) {
            LinkedHashSet<ScannedJavaFile> trace = traceDependencies(entrypoint, byClassName, 2);
            addSameModuleOperationalEvidence(trace, scan, entrypoint);
            List<String> endpoints = entrypoint.endpoints().isEmpty()
                    ? List.of("no explicit HTTP route detected")
                    : entrypoint.endpoints();
            String traceText = trace.stream()
                    .map(file -> "`" + file.className() + "` [" + classRole(file) + "] `" + file.path() + "`")
                    .limit(12)
                    .collect(Collectors.joining(" -> "));
            String technologyText = trace.stream()
                    .flatMap(file -> file.technologies().stream())
                    .distinct()
                    .collect(Collectors.joining(", "));
            flows.add("Flow candidate `" + businessModule(entrypoint) + "` from `" + entrypoint.className() + "`: "
                    + "Entrypoints: " + String.join("; ", endpoints)
                    + ". Code trace: " + traceText
                    + (technologyText.isBlank() ? "." : ". Technologies: " + technologyText + "."));
        }
        if (!flows.isEmpty()) {
            return flows;
        }
        return scan.javaFiles().stream()
                .filter(file -> file.className().endsWith("Scheduler")
                        || file.className().endsWith("Runner")
                        || file.className().endsWith("Consumer")
                        || file.className().endsWith("Publisher")
                        || file.className().endsWith("Aspect")
                        || file.className().endsWith("Filter")
                        || file.className().endsWith("Interceptor"))
                .sorted(Comparator.comparing(ScannedJavaFile::path))
                .limit(30)
                .map(file -> "Runtime flow candidate `" + businessModule(file) + "`: `" + file.className()
                        + "` [" + classRole(file) + "] `" + file.path() + "`"
                        + (file.dependencies().isEmpty() ? "" : " depends on " + String.join(", ", file.dependencies()))
                        + (file.technologies().isEmpty() ? "" : ". Technologies: " + String.join(", ", file.technologies())))
                .toList();
    }

    private LinkedHashSet<ScannedJavaFile> traceDependencies(
            ScannedJavaFile root,
            Map<String, ScannedJavaFile> byClassName,
            int maxDepth
    ) {
        LinkedHashSet<ScannedJavaFile> trace = new LinkedHashSet<>();
        trace.add(root);
        LinkedHashSet<ScannedJavaFile> frontier = new LinkedHashSet<>();
        frontier.add(root);
        for (int depth = 0; depth < maxDepth; depth++) {
            LinkedHashSet<ScannedJavaFile> next = new LinkedHashSet<>();
            for (ScannedJavaFile file : frontier) {
                for (String dependency : file.dependencies()) {
                    ScannedJavaFile dependencyFile = byClassName.get(dependency);
                    if (dependencyFile != null && trace.add(dependencyFile)) {
                        next.add(dependencyFile);
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            frontier = next;
        }
        return trace;
    }

    private void addSameModuleOperationalEvidence(LinkedHashSet<ScannedJavaFile> trace, ProjectScan scan, ScannedJavaFile entrypoint) {
        String module = businessModule(entrypoint);
        scan.javaFiles().stream()
                .filter(file -> businessModule(file).equals(module))
                .filter(file -> isOperationalEvidence(file))
                .sorted(Comparator.comparing(ScannedJavaFile::path))
                .limit(8)
                .forEach(trace::add);
    }

    private boolean isOperationalEvidence(ScannedJavaFile file) {
        String role = classRole(file);
        return role.contains("mapper")
                || role.contains("data")
                || role.contains("component")
                || role.contains("service")
                || file.className().endsWith("Repository")
                || file.className().endsWith("Dao")
                || file.className().endsWith("Scheduler")
                || file.className().endsWith("Runner")
                || file.className().endsWith("Consumer")
                || file.className().endsWith("Publisher");
    }

    private List<String> findDataAndResourceEvidence(ProjectScan scan) {
        List<String> evidence = new ArrayList<>();
        scan.javaFiles().stream()
                .filter(file -> isDataAccessJavaFile(file))
                .sorted(Comparator.comparing(ScannedJavaFile::path))
                .limit(80)
                .map(file -> "`" + file.className() + "` [" + classRole(file) + "] `" + file.path() + "`"
                        + (file.dependencies().isEmpty() ? "" : " -> " + String.join(", ", file.dependencies())))
                .forEach(evidence::add);
        evidence.addAll(findProjectResourceEvidence(scan));
        return evidence.stream().distinct().limit(120).toList();
    }

    private boolean isDataAccessJavaFile(ScannedJavaFile file) {
        String lowerPath = file.path().toLowerCase(Locale.ROOT);
        String className = file.className();
        return lowerPath.contains("/mapper/")
                || lowerPath.contains("/repository/")
                || lowerPath.contains("/dao/")
                || lowerPath.contains("/entity/")
                || className.endsWith("Mapper")
                || className.endsWith("Repository")
                || className.endsWith("Dao")
                || className.endsWith("Entity");
    }

    private List<String> findProjectResourceEvidence(ProjectScan scan) {
        Path root = Path.of(scan.rootPath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var paths = Files.walk(root, 8)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(path -> Map.entry(path, normalizeRelativePath(root, path)))
                    .filter(entry -> !entry.getValue().isBlank())
                    .filter(entry -> isResourceEvidence(entry.getValue()))
                    .sorted(Map.Entry.comparingByValue())
                    .limit(80)
                    .map(entry -> resourceEvidenceKind(entry.getValue()) + " `" + entry.getValue() + "`")
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String normalizeRelativePath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return "";
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private boolean isResourceEvidence(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".git/") || lower.startsWith("target/") || lower.startsWith("build/")
                || lower.startsWith("node_modules/") || lower.startsWith("data/") || lower.startsWith("logs/")) {
            return false;
        }
        return lower.endsWith(".sql")
                || lower.endsWith(".lua")
                || (lower.endsWith(".xml") && (lower.contains("/mapper/") || lower.startsWith("src/main/resources/")))
                || (lower.startsWith("src/main/resources/")
                && (lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties")));
    }

    private String resourceEvidenceKind(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".sql")) {
            return "SQL/schema evidence";
        }
        if (lower.endsWith(".lua")) {
            return "Lua/script evidence";
        }
        if (lower.endsWith(".xml")) {
            return "Mapper/config XML evidence";
        }
        return "Config evidence";
    }

    private String renderNumberedList(List<String> values) {
        if (values.isEmpty()) {
            return "- TODO: Confirm manually.";
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            lines.add((i + 1) + ". " + values.get(i));
        }
        return String.join(System.lineSeparator(), lines);
    }

    private String renderNumberedListOrFallback(List<String> values, String fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        return renderNumberedList(values);
    }

    private String renderBulletListOrFallback(List<String> values, String fallback) {
        if (values.isEmpty()) {
            return fallback;
        }
        return renderBulletList(values);
    }

    private record ProjectDocSummary(
            String overview,
            List<String> features,
            List<String> demoFlow,
            List<String> quickStartCommands,
            List<String> techStack,
            String scope
    ) {

        private String overviewOrTodo() {
            if (overview == null || overview.isBlank()) {
                return "TODO: Summarize the project purpose in one sentence.";
            }
            return overview;
        }

        private String scopeOrDefault() {
            if (scope == null || scope.isBlank()) {
                return "No explicit project boundary was found in README. Confirm risky operations manually.";
            }
            return scope;
        }
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
                .map(value -> "- " + value)
                .collect(Collectors.joining(System.lineSeparator()));
    }
}
