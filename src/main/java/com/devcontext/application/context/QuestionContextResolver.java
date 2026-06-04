package com.devcontext.application.context;

import com.devcontext.application.codemap.CodeMapGenerator;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.codemap.CodeDependency;
import com.devcontext.domain.codemap.CodeDomainTerm;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeRuntimeComponent;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.codemap.CodeTechnologySignal;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.context.QuestionContextCandidate;
import com.devcontext.domain.context.QuestionContextResolveResult;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.project.ProjectScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QuestionContextResolver {

    private static final int DEFAULT_MAX_ITEMS = 6;
    private static final int MAX_SOURCE_CHARS = 6_000;
    private static final int MAX_DEEP_SCAN_FILE_CHARS = 80_000;
    private static final int MAX_DEEP_SCAN_FILES = 700;
    private static final int MAX_DEEP_SCAN_RESULTS = 12;
    private static final Set<String> DEEP_SCAN_EXTENSIONS = Set.of(
            ".lua", ".sql", ".yml", ".yaml", ".properties", ".xml", ".md", ".json", ".kt", ".kts", ".ts", ".tsx", ".js", ".jsx"
    );
    private static final Set<String> IGNORED_DEEP_SCAN_DIRS = Set.of(
            ".git", ".ai", ".idea", ".vscode", ".gradle", "target", "build", "out", "dist", "node_modules", "coverage", "data", "logs"
    );

    private final ProjectApplicationService projectService;
    private final ProjectScanner projectScanner;
    private final CodeMapGenerator codeMapGenerator;
    private final QueryTermNormalizer queryTermNormalizer;
    private final ObjectMapper objectMapper;

    public QuestionContextResolver(
            ProjectApplicationService projectService,
            ProjectScanner projectScanner,
            CodeMapGenerator codeMapGenerator,
            QueryTermNormalizer queryTermNormalizer,
            ObjectMapper objectMapper
    ) {
        this.projectService = projectService;
        this.projectScanner = projectScanner;
        this.codeMapGenerator = codeMapGenerator;
        this.queryTermNormalizer = queryTermNormalizer;
        this.objectMapper = objectMapper;
    }

    public QuestionContextResolveResult resolve(Long projectId, QuestionContextResolveCommand command) {
        String question = command == null ? "" : safe(command.question());
        int maxItems = command != null && command.maxItems() != null
                ? Math.max(1, Math.min(12, command.maxItems()))
                : DEFAULT_MAX_ITEMS;
        Project project = projectService.getProject(projectId);
        Path root = Path.of(project.rootPath()).toAbsolutePath().normalize();

        List<String> notes = new ArrayList<>();
        CodeMap codeMap = loadCodeMap(root, project, notes);
        List<String> queryTerms = expandQueryTerms(question);
        Map<String, MutableCandidate> candidateMap = collectCandidates(codeMap, queryTerms);
        int codeMapCandidateCount = candidateMap.size();
        int deepScanAdded = collectDeepScanCandidates(root, queryTerms, candidateMap, codeMapCandidateCount > 0);
        List<QuestionContextCandidate> candidates = candidateMap.values().stream()
                .sorted(Comparator.comparingDouble(MutableCandidate::score).reversed()
                        .thenComparing(MutableCandidate::file))
                .limit(12)
                .map(MutableCandidate::toCandidate)
                .toList();

        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        boolean evidenceQuestion = queryTerms.contains("performance")
                || lowerQuestion.contains("qps")
                || lowerQuestion.contains("p95")
                || lowerQuestion.contains("latency")
                || lowerQuestion.contains("throughput");
        if (evidenceQuestion) {
            notes.add("该问题需要运行证据；当前解析器只返回代码设计上下文，不把代码结构推断当成真实性能结论。");
        }

        if (deepScanAdded > 0) {
            notes.add("已按问题触发受控源码/资源深扫，补充 " + deepScanAdded + " 个代码地图之外的候选文件。");
        }

        boolean needsDeepScan = candidates.isEmpty();
        String status = needsDeepScan
                ? "needs_deep_scan"
                : codeMapCandidateCount == 0 && deepScanAdded > 0 ? "resolved_deep_scan" : "resolved";
        if (needsDeepScan) {
            notes.add("代码地图和受控源码/资源深扫都没有命中足够上下文，需要补充人工上下文或外部证据。");
        }

        List<ContextItem> items = buildContextItems(projectId, root, question, queryTerms, candidates, maxItems, notes);
        return new QuestionContextResolveResult(
                projectId,
                question,
                status,
                needsDeepScan,
                queryTerms,
                candidates,
                items,
                notes
        );
    }

    private CodeMap loadCodeMap(Path root, Project project, List<String> notes) {
        Path codeMapPath = root.resolve(".ai/code-map.json").toAbsolutePath().normalize();
        if (codeMapPath.startsWith(root) && Files.isRegularFile(codeMapPath)) {
            try {
                return objectMapper.readValue(codeMapPath.toFile(), CodeMap.class);
            } catch (IOException e) {
                notes.add(".ai/code-map.json 解析失败，已临时重新扫描项目生成内存代码地图。");
            }
        } else {
            notes.add(".ai/code-map.json 不存在，已临时扫描项目生成内存代码地图；建议重新生成上下文资产。");
        }
        return codeMapGenerator.generate(project, projectScanner.scan(project.rootPath()));
    }

    private Map<String, MutableCandidate> collectCandidates(CodeMap codeMap, List<String> queryTerms) {
        Map<String, MutableCandidate> candidates = new LinkedHashMap<>();
        for (CodeDomainTerm term : nullToEmpty(codeMap.domainTerms())) {
            if (term == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(term.term()), queryTerms);
            if (matched.isEmpty()) {
                continue;
            }
            for (String file : nullToEmpty(term.files())) {
                addCandidate(candidates, file, "domain-term", term.term(), matched, List.of("domainTerms"), nullToEmpty(term.classes()), 12.0);
            }
        }
        for (CodeEndpoint endpoint : nullToEmpty(codeMap.endpoints())) {
            if (endpoint == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(
                    endpoint.path(),
                    endpoint.handlerMethod(),
                    endpoint.className(),
                    String.join(" ", nullToEmpty(endpoint.domainTerms()))
            ), queryTerms);
            if (!matched.isEmpty()) {
                addCandidate(candidates, endpoint.file(), "endpoint", endpoint.httpMethod() + " " + endpoint.path(), matched,
                        List.of("endpoints"), List.of(endpoint.className()), 10.0);
            }
        }
        for (CodeSymbol symbol : nullToEmpty(codeMap.symbols())) {
            if (symbol == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(
                    symbol.name(),
                    symbol.role(),
                    symbol.module(),
                    String.join(" ", nullToEmpty(symbol.methods())),
                    String.join(" ", nullToEmpty(symbol.endpoints())),
                    String.join(" ", nullToEmpty(symbol.dependencies())),
                    String.join(" ", nullToEmpty(symbol.technologies())),
                    String.join(" ", nullToEmpty(symbol.domainTerms()))
            ), queryTerms);
            if (!matched.isEmpty()) {
                addCandidate(candidates, symbol.file(), "symbol", symbol.name(), matched,
                        List.of("symbols", symbol.role()), List.of(symbol.name()), 8.0);
            }
        }
        for (CodeDependency dependency : nullToEmpty(codeMap.dependencies())) {
            if (dependency == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(
                    dependency.fromClass(),
                    dependency.toType(),
                    dependency.module()
            ), queryTerms);
            if (!matched.isEmpty()) {
                addCandidate(candidates, dependency.fromFile(), "dependency", dependency.fromClass() + " -> " + dependency.toType(),
                        matched, List.of("dependencies"), List.of(dependency.fromClass(), dependency.toType()), 6.0);
            }
        }
        for (CodeTechnologySignal technology : nullToEmpty(codeMap.technologies())) {
            if (technology == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(
                    technology.technology(),
                    String.join(" ", nullToEmpty(technology.classes()))
            ), queryTerms);
            if (!matched.isEmpty()) {
                for (String file : nullToEmpty(technology.files())) {
                    addCandidate(candidates, file, "technology", technology.technology(), matched,
                            List.of("technologies"), nullToEmpty(technology.classes()), 5.0);
                }
            }
        }
        for (CodeRuntimeComponent component : nullToEmpty(codeMap.runtimeComponents())) {
            if (component == null) {
                continue;
            }
            List<String> matched = matchedTerms(List.of(
                    component.type(),
                    component.className(),
                    component.module(),
                    String.join(" ", nullToEmpty(component.dependencies())),
                    String.join(" ", nullToEmpty(component.technologies()))
            ), queryTerms);
            if (!matched.isEmpty()) {
                addCandidate(candidates, component.file(), "runtime-component", component.className(), matched,
                        List.of("runtimeComponents", component.type()), List.of(component.className()), 5.0);
            }
        }
        return candidates;
    }

    private int collectDeepScanCandidates(
            Path root,
            List<String> queryTerms,
            Map<String, MutableCandidate> candidates,
            boolean codeMapHasCandidates
    ) {
        if (queryTerms.isEmpty() || !Files.isDirectory(root)) {
            return 0;
        }
        int added = 0;
        try (var paths = Files.walk(root, 8)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isDeepScanFile(root, path))
                    .limit(MAX_DEEP_SCAN_FILES)
                    .toList();
            for (Path path : files) {
                if (added >= MAX_DEEP_SCAN_RESULTS) {
                    break;
                }
                String relativePath = normalizeRelativePath(root, path);
                if (relativePath.isBlank()) {
                    continue;
                }
                String content = readDeepScanContent(path);
                if (content.isBlank()) {
                    continue;
                }
                List<String> pathMatches = matchedTerms(List.of(relativePath), queryTerms);
                List<String> contentMatches = matchedTerms(List.of(content), queryTerms);
                if (!isStrongDeepScanMatch(pathMatches, contentMatches, codeMapHasCandidates)) {
                    continue;
                }
                LinkedHashSet<String> mergedMatches = new LinkedHashSet<>();
                mergedMatches.addAll(pathMatches);
                mergedMatches.addAll(contentMatches);
                List<String> matched = new ArrayList<>(mergedMatches);
                MutableCandidate existing = candidates.get(relativePath);
                if (existing != null) {
                    existing.addScore(2.0 + matched.size());
                    existing.matchedTerms().addAll(matched);
                    existing.reasons().add("deepScan");
                    continue;
                }
                addCandidate(
                        candidates,
                        relativePath,
                        "deep-scan",
                        Path.of(relativePath).getFileName().toString(),
                        matched,
                        List.of("deepScan", fileExtension(relativePath)),
                        List.of(),
                        deepScanBaseScore(relativePath, pathMatches, queryTerms)
                );
                added++;
            }
        } catch (IOException ignored) {
            // Deep scan is best-effort; code-map routing should still work when a file cannot be scanned.
        }
        return added;
    }

    private double deepScanBaseScore(String relativePath, List<String> pathMatches, List<String> queryTerms) {
        String extension = fileExtension(relativePath);
        double score = 14.0 + pathMatches.size() * 4.0;
        if (queryTerms.contains(extension)) {
            score += 65.0;
        }
        if (queryTerms.contains("script") && Set.of("lua", "sql", "js", "ts").contains(extension)) {
            score += 20.0;
        }
        return score;
    }

    private boolean isDeepScanFile(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (IGNORED_DEEP_SCAN_DIRS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return DEEP_SCAN_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private String readDeepScanContent(Path path) {
        try {
            if (Files.size(path) > MAX_DEEP_SCAN_FILE_CHARS) {
                return "";
            }
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isStrongDeepScanMatch(List<String> pathMatches, List<String> contentMatches, boolean codeMapHasCandidates) {
        if (contentMatches.isEmpty()) {
            return false;
        }
        if (codeMapHasCandidates) {
            return !pathMatches.isEmpty();
        }
        return !pathMatches.isEmpty() || contentMatches.size() >= 2;
    }

    private String normalizeRelativePath(Path root, Path path) {
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(root)) {
            return "";
        }
        return root.relativize(normalizedPath).toString().replace('\\', '/');
    }

    private String fileExtension(String relativePath) {
        int dot = relativePath.lastIndexOf('.');
        if (dot < 0 || dot == relativePath.length() - 1) {
            return "file";
        }
        return relativePath.substring(dot + 1);
    }

    private void addCandidate(
            Map<String, MutableCandidate> candidates,
            String file,
            String sourceType,
            String title,
            List<String> matchedTerms,
            List<String> reasons,
            List<String> relatedSymbols,
            double baseScore
    ) {
        if (file == null || file.isBlank()) {
            return;
        }
        MutableCandidate candidate = candidates.computeIfAbsent(file, ignored -> new MutableCandidate(file));
        candidate.sourceType(sourceType);
        candidate.title(title);
        candidate.addScore(baseScore + matchedTerms.size());
        candidate.matchedTerms().addAll(matchedTerms);
        candidate.reasons().addAll(reasons);
        candidate.relatedSymbols().addAll(relatedSymbols);
    }

    private List<ContextItem> buildContextItems(
            Long projectId,
            Path root,
            String question,
            List<String> queryTerms,
            List<QuestionContextCandidate> candidates,
            int maxItems,
            List<String> notes
    ) {
        List<ContextItem> items = new ArrayList<>();
        String summary = renderSummary(question, queryTerms, candidates, notes);
        items.add(contextItem(projectId, "QUESTION_CONTEXT_SUMMARY", "问题相关上下文摘要", summary, ".ai/code-map.json", 100));

        int remaining = Math.max(0, maxItems - 1);
        for (QuestionContextCandidate candidate : candidates.stream().limit(remaining).toList()) {
            String content = readSourceExcerpt(root, candidate.file());
            if (content.isBlank()) {
                continue;
            }
            String type = "deep-scan".equals(candidate.sourceType()) ? "QUESTION_DEEP_SCAN_SOURCE" : "QUESTION_RELATED_SOURCE";
            items.add(contextItem(
                    projectId,
                    type,
                    candidate.title(),
                    content,
                    candidate.file(),
                    Math.max(50, 95 - items.size() * 5)
            ));
        }
        addGeneratedDocItem(items, projectId, root, ".ai/generated/core-flows.md", queryTerms, maxItems);
        addGeneratedDocItem(items, projectId, root, ".ai/generated/tech-architecture.md", queryTerms, maxItems);
        return items.stream().limit(maxItems).toList();
    }

    private void addGeneratedDocItem(List<ContextItem> items, Long projectId, Path root, String relativePath, List<String> queryTerms, int maxItems) {
        if (items.size() >= maxItems) {
            return;
        }
        Path path = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return;
        }
        try {
            String content = Files.readString(path);
            if (!matchedTerms(List.of(content), queryTerms).isEmpty()) {
                items.add(contextItem(projectId, "QUESTION_GENERATED_DOC", relativePath, trim(content, MAX_SOURCE_CHARS), relativePath, 70));
            }
        } catch (IOException ignored) {
            // Skip unreadable generated docs.
        }
    }

    private String readSourceExcerpt(Path root, String relativePath) {
        Path path = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return "";
        }
        try {
            return trim(Files.readString(path), MAX_SOURCE_CHARS);
        } catch (IOException e) {
            return "";
        }
    }

    private String renderSummary(String question, List<String> queryTerms, List<QuestionContextCandidate> candidates, List<String> notes) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question: ").append(question).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("Expanded query terms: ").append(String.join(", ", queryTerms)).append(System.lineSeparator()).append(System.lineSeparator());
        if (candidates.isEmpty()) {
            builder.append("No code-map candidate matched. A deep scan is required.").append(System.lineSeparator());
        } else {
            builder.append("Top candidates:").append(System.lineSeparator());
            for (QuestionContextCandidate candidate : candidates.stream().limit(8).toList()) {
                builder.append("- ")
                        .append(candidate.file())
                        .append(" | score=")
                        .append(candidate.score())
                        .append(" | matched=")
                        .append(String.join(", ", candidate.matchedTerms()))
                        .append(" | reasons=")
                        .append(String.join(", ", candidate.reasons()))
                        .append(System.lineSeparator());
            }
        }
        if (!notes.isEmpty()) {
            builder.append(System.lineSeparator()).append("Notes:").append(System.lineSeparator());
            for (String note : notes) {
                builder.append("- ").append(note).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private ContextItem contextItem(Long projectId, String type, String title, String content, String source, int priority) {
        return new ContextItem(
                null,
                null,
                projectId,
                type,
                title,
                content,
                source,
                priority,
                estimateTokens(content),
                sha256(content),
                Instant.now()
        );
    }

    private List<String> expandQueryTerms(String question) {
        return queryTermNormalizer.normalize(question);
    }

    private List<String> matchedTerms(Collection<String> values, List<String> queryTerms) {
        String raw = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        String normalized = raw.replaceAll("[^a-z0-9]+", " ");
        return queryTerms.stream()
                .filter(term -> matches(raw, normalized, term))
                .distinct()
                .toList();
    }

    private boolean matches(String raw, String normalized, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String lowerTerm = term.toLowerCase(Locale.ROOT);
        String normalizedTerm = lowerTerm.replaceAll("[^a-z0-9]+", " ").trim();
        return raw.contains(lowerTerm) || (!normalizedTerm.isBlank() && normalized.contains(normalizedTerm));
    }

    private <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String trim(String content, int maxChars) {
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, maxChars) + System.lineSeparator() + "... [truncated]";
    }

    private int estimateTokens(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safe(content).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static final class MutableCandidate {
        private final String file;
        private String sourceType = "code-map";
        private String title = "code-map match";
        private double score;
        private final LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final LinkedHashSet<String> relatedSymbols = new LinkedHashSet<>();

        private MutableCandidate(String file) {
            this.file = file;
        }

        private String file() {
            return file;
        }

        private double score() {
            return score;
        }

        private LinkedHashSet<String> matchedTerms() {
            return matchedTerms;
        }

        private LinkedHashSet<String> reasons() {
            return reasons;
        }

        private LinkedHashSet<String> relatedSymbols() {
            return relatedSymbols;
        }

        private void sourceType(String sourceType) {
            this.sourceType = sourceType;
        }

        private void title(String title) {
            this.title = title;
        }

        private void addScore(double score) {
            this.score += score;
        }

        private QuestionContextCandidate toCandidate() {
            return new QuestionContextCandidate(
                    sourceType,
                    title,
                    file,
                    Math.round(score * 100.0) / 100.0,
                    new ArrayList<>(matchedTerms),
                    new ArrayList<>(reasons),
                    new ArrayList<>(relatedSymbols)
            );
        }
    }
}
