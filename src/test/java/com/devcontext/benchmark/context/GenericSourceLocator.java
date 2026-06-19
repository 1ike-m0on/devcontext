package com.devcontext.benchmark.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class GenericSourceLocator {

    private static final Pattern TERM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*|[\\p{IsHan}]+");
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp",
            ".sql", ".yml", ".yaml", ".json", ".properties"
    );
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "how", "what", "where", "which", "should", "this", "that",
            "需求", "应该", "哪些", "源码", "文件", "根据", "实现", "入口", "需要", "修改", "怎么", "哪里"
    );

    List<String> locate(Path root, String question, int limit) {
        return locate(root, question, limit, List.of());
    }

    List<String> locate(Path root, String question, int limit, List<String> explicitProjectFiles) {
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        Set<String> terms = terms(question);
        Set<String> explicitFiles = explicitProjectFiles(explicitProjectFiles);
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> allowed(root, path, question, explicitFiles))
                    .filter(path -> !questionExplicitlyRejectsPath(root, path, question))
                    .map(path -> score(root, path, terms, explicitFiles))
                    .filter(scored -> scored.score() > 0.0d)
                    .sorted(Comparator.comparingDouble(ScoredPath::score).reversed()
                            .thenComparing(ScoredPath::relativePath))
                    .limit(limit <= 0 ? 8 : limit)
                    .map(ScoredPath::relativePath)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private ScoredPath score(Path root, Path file, Set<String> terms, Set<String> explicitProjectFiles) {
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        String lowerPath = relative.toLowerCase(Locale.ROOT);
        String content = read(file).toLowerCase(Locale.ROOT);
        String haystack = lowerPath + "\n" + content;
        double score = 0.0d;
        for (String term : terms) {
            String normalized = term.toLowerCase(Locale.ROOT);
            String compact = normalized.replaceAll("[^\\p{IsHan}a-z0-9]+", "");
            if (lowerPath.contains(normalized)) {
                score += normalized.length() >= 6 ? 24.0d : 8.0d;
            }
            if (!compact.isBlank() && lowerPath.replaceAll("[^a-z0-9]+", "").contains(compact)) {
                score += compact.length() >= 6 ? 18.0d : 5.0d;
            }
            if (content.contains(normalized)) {
                score += normalized.length() >= 6 ? 6.0d : 2.0d;
            }
            if (content.replaceAll("[^a-z0-9]+", "").contains(compact) && compact.length() >= 6) {
                score += 4.0d;
            }
        }
        if (explicitProjectFiles.contains(lowerPath)) {
            score += 120.0d;
        }
        score += roleBoost(lowerPath, terms);
        return new ScoredPath(relative, score);
    }

    private double roleBoost(String lowerPath, Set<String> terms) {
        double score = 0.0d;
        if (containsAny(terms, "api", "接口", "entrypoint", "route", "handler") && containsAny(lowerPath, "controller", "route", "api", "handler")) {
            score += 40.0d;
        }
        if (containsAny(terms, "service", "业务逻辑", "logic") && lowerPath.contains("service")) {
            score += 38.0d;
        }
        if (containsAny(terms, "repository", "store", "storage", "持久化", "存储", "query") && containsAny(lowerPath, "repository", "store", "storage", "db")) {
            score += 38.0d;
        }
        if (containsAny(terms, "model", "types", "schema", "数据模型") && containsAny(lowerPath, "model", "type", "schema")) {
            score += 36.0d;
        }
        if (containsAny(terms, "config", "settings", "配置") && containsAny(lowerPath, "config", "settings")) {
            score += 36.0d;
        }
        if (containsAny(terms, "test", "spec", "测试") && containsAny(lowerPath, "test", "spec")) {
            score += 36.0d;
        }
        return score;
    }

    private Set<String> terms(String question) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(question == null ? "" : question);
        while (matcher.find()) {
            String value = matcher.group();
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.length() >= 2 && !STOP_WORDS.contains(lower)) {
                terms.add(lower);
            }
            splitCamel(lower).forEach(terms::add);
        }
        addIfContains(question, terms, "创建接口", "create", "api", "route", "handler");
        addIfContains(question, terms, "核心业务逻辑", "service", "logic");
        addIfContains(question, terms, "业务逻辑", "service", "logic");
        addIfContains(question, terms, "数据模型", "model", "types", "schema");
        addIfContains(question, terms, "持久化", "repository", "storage", "store", "query");
        addIfContains(question, terms, "存储", "repository", "storage", "store");
        addIfContains(question, terms, "配置", "config", "settings");
        addIfContains(question, terms, "测试", "test", "spec");
        return terms;
    }

    private List<String> splitCamel(String value) {
        String separated = value
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^a-z0-9]+", " ");
        List<String> result = new ArrayList<>();
        for (String token : separated.split("\\s+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private void addIfContains(String question, Set<String> terms, String marker, String... additions) {
        if (question != null && question.contains(marker)) {
            terms.addAll(List.of(additions));
        }
    }

    private boolean allowed(Path root, Path file, String question, Set<String> explicitProjectFiles) {
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.startsWith(".git/")
                || lower.startsWith(".ai/")
                || lower.startsWith("docs/")
                || lower.startsWith(".docs/")
                || lower.startsWith("metadata/")
                || lower.contains("/metadata/")
                || lower.startsWith(".meta/")
                || lower.startsWith(".approaches/")
                || lower.startsWith("noise/")
                || lower.contains("/noise/")
                || lower.startsWith("src/test/resources/context-benchmark/")
                || lower.contains("/node_modules/")
                || lower.startsWith("target/")
                || lower.contains("readme")) {
            return false;
        }
        if (sourceFilesOnly(question) && nonImplementationFixturePath(lower) && !explicitProjectFiles.contains(lower)) {
            return false;
        }
        return SOURCE_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || explicitProjectFiles.contains(lower);
    }

    private Set<String> explicitProjectFiles(List<String> values) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (String value : values == null ? List.<String>of() : values) {
            String normalized = value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT).trim();
            if (!normalized.isBlank()) {
                files.add(normalized);
            }
        }
        return files;
    }

    private boolean sourceFilesOnly(String question) {
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return lower.contains("source files only")
                || lower.contains("implementation source files")
                || lower.contains("return source files");
    }

    private boolean nonImplementationFixturePath(String lowerPath) {
        String fileName = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        return lowerPath.startsWith("test/")
                || lowerPath.contains("/test/")
                || lowerPath.startsWith("tests/")
                || lowerPath.contains("/tests/")
                || lowerPath.startsWith("example/")
                || lowerPath.contains("/example/")
                || lowerPath.startsWith("examples/")
                || lowerPath.contains("/examples/")
                || fileName.contains("_test.")
                || fileName.contains("-test.")
                || fileName.contains(".test.")
                || fileName.contains("_spec.")
                || fileName.contains("-spec.")
                || fileName.contains(".spec.")
                || fileName.startsWith("test_")
                || fileName.startsWith("tests-")
                || fileName.equals("cases_test.go")
                || fileName.startsWith("example.")
                || fileName.startsWith("examples.")
                || fileName.contains("_example.")
                || fileName.contains("-example.")
                || fileName.contains(".example.");
    }

    private boolean questionExplicitlyRejectsPath(Path root, Path file, String question) {
        String safeQuestion = question == null ? "" : question;
        String normalizedQuestion = safeQuestion.toLowerCase(Locale.ROOT);
        String rejectedSegment = rejectedSegment(safeQuestion);
        if (rejectedSegment.isBlank()) {
            return false;
        }
        String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        String fileName = relative.substring(relative.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        String simpleName = dot > 0 ? fileName.substring(0, dot) : fileName;
        return !simpleName.isBlank() && compact(rejectedSegment).contains(compact(simpleName))
                && compact(safeQuestion).contains(compact(simpleName));
    }

    private String rejectedSegment(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        for (String marker : List.of("do not select", "don't select", "不要选择", "不要选", "不选")) {
            int index = lower.indexOf(marker.toLowerCase(Locale.ROOT));
            if (index >= 0) {
                int start = index + marker.length();
                String tail = question.substring(Math.min(start, question.length()));
                int end = firstBoundary(tail);
                return end >= 0 ? tail.substring(0, end) : tail;
            }
        }
        return "";
    }

    private int firstBoundary(String value) {
        int best = -1;
        for (String boundary : List.of(";", "；", ",", "，", ".", "。", "?", "？")) {
            int index = value.indexOf(boundary);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    private boolean containsAny(Set<String> terms, String... values) {
        for (String value : values) {
            if (terms.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String compact(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", "");
    }

    private String read(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.length() <= 50_000 ? content : content.substring(0, 50_000);
        } catch (IOException e) {
            return "";
        }
    }

    private record ScoredPath(String relativePath, double score) {
    }
}
