package com.devcontext.application.codemap;

import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMapConfigKey;
import com.devcontext.domain.codemap.CodeDependency;
import com.devcontext.domain.codemap.CodeDomainTerm;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeMapDependencyEdge;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeMapRoutingHint;
import com.devcontext.domain.codemap.CodeMapTestRelation;
import com.devcontext.domain.codemap.CodeModule;
import com.devcontext.domain.codemap.CodeRuntimeComponent;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.codemap.CodeTechnologySignal;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.devcontext.domain.project.ScannedJavaFile;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CodeMapGenerator {

    private static final Pattern YAML_KEY_PATTERN = Pattern.compile("^([A-Za-z0-9_.-]+)\\s*:\\s*(.*)$");
    private static final Pattern PROPERTY_KEY_PATTERN = Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*[:=].*$");
    private static final Pattern ENV_KEY_PATTERN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=.*$");
    private static final String SQL_IDENTIFIER = "[`\"\\[]?[A-Za-z][\\w$]*[`\"\\]]?(?:\\.[`\"\\[]?[A-Za-z][\\w$]*[`\"\\]]?)?";
    private static final Pattern SQL_CREATE_TABLE_PATTERN = Pattern.compile(
            "\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?(" + SQL_IDENTIFIER + ")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQL_CREATE_INDEX_PATTERN = Pattern.compile(
            "\\bcreate\\s+(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?(" + SQL_IDENTIFIER + ")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQL_TABLE_REFERENCE_PATTERN = Pattern.compile(
            "\\b(from|join|into|update|table)\\s+(" + SQL_IDENTIFIER + ")",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MAPPER_NAMESPACE_PATTERN = Pattern.compile(
            "<mapper\\b[^>]*\\bnamespace\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MAPPER_STATEMENT_PATTERN = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*\\bid\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ENTITY_TABLE_PATTERN = Pattern.compile(
            "@(?:Table|TableName)\\s*\\((?:[^)]*?name\\s*=\\s*)?\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQL_ANNOTATION_PATTERN = Pattern.compile(
            "@(?:Select|Insert|Update|Delete|Query)\\s*\\(([^)]*)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("\"([^\"]+)\"");

    public CodeMap generate(Project project, ProjectScan scan) {
        List<CodeModule> modules = modules(scan);
        List<CodeEntrypoint> entrypoints = entrypoints(scan);
        List<CodeSymbol> symbols = symbols(scan);
        List<CodeEndpoint> endpoints = endpoints(scan);
        List<CodeDependency> dependencies = dependencies(scan);
        List<CodeMapRoutingHint> mapperHints = mergeHints(
                routingHints(symbols, "mapper"),
                mapperFileHints(scan),
                mapperAnnotationHints(scan)
        );
        List<CodeMapRoutingHint> entityHints = mergeHints(
                routingHints(symbols, "entity"),
                entityTableHints(scan)
        );
        return new CodeMap(
                CodeMap.CURRENT_SCHEMA_VERSION,
                Instant.now().toString(),
                project.name(),
                project.rootPath(),
                scan.language(),
                scan.framework(),
                scan.buildTool(),
                scan.gitBranch(),
                scan.gitCommit(),
                modules,
                entrypoints,
                symbols,
                endpoints,
                dependencies,
                technologies(scan),
                runtimeComponents(scan),
                domainTerms(scan),
                scan.commands(),
                scan.configFiles(),
                scan.testRoots(),
                scan.docs(),
                scan.todos(),
                files(scan),
                configKeys(scan),
                sqlHints(scan),
                mapperHints,
                entityHints,
                testRelations(scan),
                dependencyEdges(dependencies)
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

    private List<CodeSymbol> symbols(ProjectScan scan) {
        return scan.javaFiles().stream()
                .map(file -> new CodeSymbol(
                        file.className(),
                        symbolRole(file),
                        businessModule(file),
                        file.path(),
                        file.methods(),
                        file.endpoints(),
                        file.dependencies(),
                        file.technologies(),
                        domainTermsForFile(file)
                ))
                .sorted(Comparator.comparing(CodeSymbol::file))
                .limit(500)
                .toList();
    }

    private List<CodeEndpoint> endpoints(ProjectScan scan) {
        return scan.javaFiles().stream()
                .flatMap(file -> file.endpoints().stream()
                        .map(endpoint -> parseEndpoint(file, endpoint)))
                .sorted(Comparator.comparing(CodeEndpoint::path).thenComparing(CodeEndpoint::httpMethod))
                .limit(300)
                .toList();
    }

    private CodeEndpoint parseEndpoint(ScannedJavaFile file, String endpoint) {
        String[] handlerParts = endpoint.split("\\s*->\\s*", 2);
        String left = handlerParts[0].trim();
        String handlerMethod = handlerParts.length > 1 ? handlerParts[1].trim() : "";
        int firstSpace = left.indexOf(' ');
        String httpMethod = firstSpace < 0 ? "ANY" : left.substring(0, firstSpace).trim();
        String path = firstSpace < 0 ? left : left.substring(firstSpace + 1).trim();
        return new CodeEndpoint(
                httpMethod,
                path,
                handlerMethod,
                file.className(),
                businessModule(file),
                file.path(),
                domainTermsFromValues(List.of(path, handlerMethod, file.className()))
        );
    }

    private List<CodeDependency> dependencies(ProjectScan scan) {
        return scan.javaFiles().stream()
                .flatMap(file -> file.dependencies().stream()
                        .map(dependency -> new CodeDependency(
                                file.className(),
                                file.path(),
                                dependency,
                                businessModule(file)
                        )))
                .sorted(Comparator.comparing(CodeDependency::fromFile).thenComparing(CodeDependency::toType))
                .limit(1_000)
                .toList();
    }

    private List<CodeMapFileEntry> files(ProjectScan scan) {
        Map<String, CodeMapFileEntry> filesByPath = new LinkedHashMap<>();
        for (ScannedJavaFile file : scan.javaFiles()) {
            filesByPath.put(file.path(), new CodeMapFileEntry(
                    file.path(),
                    "source",
                    "Java",
                    businessModule(file),
                    fileRoles(file)
            ));
        }
        for (String sqlFile : scan.sqlFiles()) {
            filesByPath.put(sqlFile, new CodeMapFileEntry(
                    sqlFile,
                    "database_schema",
                    languageForPath(sqlFile),
                    moduleFromPath(sqlFile),
                    List.of("sql", "database-schema")
            ));
        }
        for (String mapperFile : scan.mapperFiles()) {
            filesByPath.put(mapperFile, new CodeMapFileEntry(
                    mapperFile,
                    "mapper",
                    languageForPath(mapperFile),
                    moduleFromPath(mapperFile),
                    List.of("mapper", "data-access")
            ));
        }
        for (String testFile : scan.testFiles()) {
            filesByPath.put(testFile, new CodeMapFileEntry(
                    testFile,
                    "test",
                    languageForPath(testFile),
                    moduleFromPath(testFile),
                    List.of("test")
            ));
        }
        for (String configFile : scan.configFiles()) {
            filesByPath.putIfAbsent(configFile, new CodeMapFileEntry(
                    configFile,
                    "configuration",
                    languageForPath(configFile),
                    moduleFromPath(configFile),
                    List.of("configuration")
            ));
        }
        for (String doc : scan.docs()) {
            filesByPath.putIfAbsent(doc, new CodeMapFileEntry(
                    doc,
                    "documentation",
                    languageForPath(doc),
                    moduleFromPath(doc),
                    List.of("documentation")
            ));
        }
        return filesByPath.values().stream()
                .sorted(Comparator.comparing(CodeMapFileEntry::path))
                .limit(1_000)
                .toList();
    }

    private List<CodeMapConfigKey> configKeys(ProjectScan scan) {
        Map<String, CodeMapConfigKey> keysByFileAndName = new LinkedHashMap<>();
        for (String configFile : scan.configFiles()) {
            Optional<String> content = readProjectFile(scan, configFile);
            if (content.isEmpty()) {
                continue;
            }
            for (String key : extractConfigKeys(configFile, content.get())) {
                keysByFileAndName.putIfAbsent(
                        configFile + "|" + key,
                        new CodeMapConfigKey(key, configFile, moduleFromPath(configFile))
                );
            }
        }
        return keysByFileAndName.values().stream()
                .sorted(Comparator.comparing(CodeMapConfigKey::file)
                        .thenComparing(CodeMapConfigKey::key))
                .limit(500)
                .toList();
    }

    private List<CodeMapRoutingHint> sqlHints(ProjectScan scan) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        for (String sqlFile : scan.sqlFiles()) {
            readProjectFile(scan, sqlFile)
                    .ifPresent(content -> hints.addAll(sqlHintsFromSqlContent(sqlFile, content, moduleFromPath(sqlFile))));
        }
        for (String mapperFile : scan.mapperFiles()) {
            readProjectFile(scan, mapperFile)
                    .ifPresent(content -> hints.addAll(sqlTableReferenceHints(mapperFile, content, moduleFromPath(mapperFile))));
        }
        for (ScannedJavaFile file : scan.javaFiles()) {
            readProjectFile(scan, file.path())
                    .ifPresent(content -> hints.addAll(sqlAnnotationTableHints(file, content)));
        }
        return mergeHints(hints);
    }

    private List<CodeMapRoutingHint> routingHints(List<CodeSymbol> symbols, String role) {
        return symbols.stream()
                .filter(symbol -> role.equals(symbol.role()))
                .map(symbol -> new CodeMapRoutingHint(
                        role,
                        symbol.name(),
                        symbol.file(),
                        symbol.name()
                ))
                .sorted(Comparator.comparing(CodeMapRoutingHint::file)
                        .thenComparing(CodeMapRoutingHint::name))
                .limit(300)
                .toList();
    }

    private List<CodeMapRoutingHint> mapperFileHints(ProjectScan scan) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        for (String mapperFile : scan.mapperFiles()) {
            Optional<String> content = readProjectFile(scan, mapperFile);
            if (content.isEmpty()) {
                continue;
            }
            String namespace = extractFirst(MAPPER_NAMESPACE_PATTERN, content.get(), 1).orElse("");
            String owner = namespace.isBlank() ? moduleFromPath(mapperFile) : namespace;
            String mapperName = namespace.isBlank() ? fileNameWithoutExtension(mapperFile) : simpleName(namespace);
            if (!mapperName.isBlank()) {
                hints.add(new CodeMapRoutingHint("mapper_xml", mapperName, mapperFile, owner));
            }

            Matcher statementMatcher = MAPPER_STATEMENT_PATTERN.matcher(content.get());
            while (statementMatcher.find() && hints.size() < 200) {
                hints.add(new CodeMapRoutingHint("mapper_statement", statementMatcher.group(2), mapperFile, mapperName));
            }
        }
        return mergeHints(hints);
    }

    private List<CodeMapRoutingHint> mapperAnnotationHints(ProjectScan scan) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        for (ScannedJavaFile file : scan.javaFiles()) {
            String role = symbolRole(file);
            if (!role.equals("mapper") && !role.equals("repository")) {
                continue;
            }
            readProjectFile(scan, file.path())
                    .filter(content -> SQL_ANNOTATION_PATTERN.matcher(content).find())
                    .ifPresent(ignored -> hints.add(new CodeMapRoutingHint(
                            "mapper_annotation",
                            file.className(),
                            file.path(),
                            role
                    )));
        }
        return mergeHints(hints);
    }

    private List<CodeMapRoutingHint> entityTableHints(ProjectScan scan) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        for (ScannedJavaFile file : scan.javaFiles()) {
            if (!symbolRole(file).equals("entity")) {
                continue;
            }
            Optional<String> content = readProjectFile(scan, file.path());
            if (content.isEmpty()) {
                continue;
            }
            Matcher tableMatcher = ENTITY_TABLE_PATTERN.matcher(content.get());
            while (tableMatcher.find() && hints.size() < 200) {
                String tableName = cleanSqlIdentifier(tableMatcher.group(1));
                if (!tableName.isBlank()) {
                    hints.add(new CodeMapRoutingHint("entity_table", tableName, file.path(), file.className()));
                }
            }
        }
        return mergeHints(hints);
    }

    private List<CodeMapTestRelation> testRelations(ProjectScan scan) {
        Map<String, String> productionFilesByClass = scan.javaFiles().stream()
                .collect(Collectors.toMap(
                        ScannedJavaFile::className,
                        ScannedJavaFile::path,
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        List<CodeMapTestRelation> relations = new ArrayList<>();
        for (String testFile : scan.testFiles()) {
            String targetClass = testTargetClass(fileNameWithoutExtension(testFile));
            if (targetClass.isBlank()) {
                continue;
            }
            String targetFile = productionFilesByClass.get(targetClass);
            if (targetFile != null) {
                relations.add(new CodeMapTestRelation(testFile, targetFile, "name_convention"));
            }
        }
        return relations.stream()
                .sorted(Comparator.comparing(CodeMapTestRelation::testFile)
                        .thenComparing(CodeMapTestRelation::targetFile))
                .limit(300)
                .toList();
    }

    private List<CodeMapDependencyEdge> dependencyEdges(List<CodeDependency> dependencies) {
        return dependencies.stream()
                .map(dependency -> new CodeMapDependencyEdge(
                        dependency.fromFile(),
                        dependency.fromClass(),
                        null,
                        dependency.toType(),
                        "type_dependency"
                ))
                .sorted(Comparator.comparing(CodeMapDependencyEdge::fromFile)
                        .thenComparing(CodeMapDependencyEdge::toSymbol))
                .limit(1_000)
                .toList();
    }

    private List<CodeTechnologySignal> technologies(ProjectScan scan) {
        Map<String, List<ScannedJavaFile>> grouped = new LinkedHashMap<>();
        for (ScannedJavaFile file : scan.javaFiles()) {
            for (String technology : file.technologies()) {
                grouped.computeIfAbsent(technology, ignored -> new ArrayList<>()).add(file);
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new CodeTechnologySignal(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(ScannedJavaFile::className)
                                .distinct()
                                .sorted()
                                .limit(80)
                                .toList(),
                        entry.getValue().stream()
                                .map(ScannedJavaFile::path)
                                .distinct()
                                .sorted()
                                .limit(80)
                                .toList()
                ))
                .sorted(Comparator.comparing(CodeTechnologySignal::technology))
                .toList();
    }

    private List<CodeRuntimeComponent> runtimeComponents(ProjectScan scan) {
        return scan.javaFiles().stream()
                .map(file -> Map.entry(file, runtimeComponentType(file)))
                .filter(entry -> !entry.getValue().isBlank())
                .map(entry -> new CodeRuntimeComponent(
                        entry.getValue(),
                        entry.getKey().className(),
                        businessModule(entry.getKey()),
                        entry.getKey().path(),
                        entry.getKey().dependencies(),
                        entry.getKey().technologies()
                ))
                .sorted(Comparator.comparing(CodeRuntimeComponent::type)
                        .thenComparing(CodeRuntimeComponent::file))
                .limit(200)
                .toList();
    }

    private List<CodeDomainTerm> domainTerms(ProjectScan scan) {
        Map<String, List<ScannedJavaFile>> grouped = new LinkedHashMap<>();
        for (ScannedJavaFile file : scan.javaFiles()) {
            for (String term : domainTermsForFile(file)) {
                grouped.computeIfAbsent(term, ignored -> new ArrayList<>()).add(file);
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new CodeDomainTerm(
                        entry.getKey(),
                        entry.getValue().stream()
                                .map(ScannedJavaFile::className)
                                .distinct()
                                .sorted()
                                .limit(30)
                                .toList(),
                        entry.getValue().stream()
                                .map(ScannedJavaFile::path)
                                .distinct()
                                .sorted()
                                .limit(30)
                                .toList()
                ))
                .sorted(Comparator.comparing(CodeDomainTerm::term))
                .limit(300)
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

    private String symbolRole(ScannedJavaFile file) {
        String className = file.className();
        String lowerPath = file.path().toLowerCase(Locale.ROOT);
        if (file.annotations().contains("@RestController") || file.annotations().contains("@Controller")
                || className.endsWith("Controller")) {
            return "controller";
        }
        if (file.annotations().contains("@Service") || className.endsWith("Service")
                || className.endsWith("ServiceImpl") || lowerPath.contains("/service/")) {
            return "service";
        }
        if (file.annotations().contains("@Repository") || className.endsWith("Repository")
                || className.endsWith("Dao") || lowerPath.contains("/repository/")
                || lowerPath.contains("/dao/")) {
            return "repository";
        }
        if (className.endsWith("Mapper") || lowerPath.contains("/mapper/")) {
            return "mapper";
        }
        if (file.annotations().contains("@Entity") || file.annotations().contains("@Table")
                || className.endsWith("Entity") || lowerPath.contains("/entity/")) {
            return "entity";
        }
        if (file.annotations().contains("@Configuration") || lowerPath.contains("/config/")) {
            return "configuration";
        }
        String runtimeType = runtimeComponentType(file);
        if (!runtimeType.isBlank()) {
            return runtimeType;
        }
        if (file.annotations().contains("@Component")) {
            return "component";
        }
        return "support";
    }

    private List<String> fileRoles(ScannedJavaFile file) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        String primaryRole = symbolRole(file);
        roles.add(primaryRole);
        if (!file.endpoints().isEmpty()) {
            roles.add("endpoint");
        }
        if (isSpringComponent(file)) {
            roles.add("spring-component");
        }
        if (primaryRole.equals("repository") || primaryRole.equals("mapper")) {
            roles.add("data-access");
        }
        if (primaryRole.equals("entity")) {
            roles.add("domain-entity");
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .toList();
    }

    private boolean isSpringComponent(ScannedJavaFile file) {
        return file.annotations().contains("@RestController")
                || file.annotations().contains("@Controller")
                || file.annotations().contains("@Service")
                || file.annotations().contains("@Repository")
                || file.annotations().contains("@Component")
                || file.annotations().contains("@Configuration");
    }

    private String runtimeComponentType(ScannedJavaFile file) {
        String className = file.className();
        if (file.annotations().contains("@SpringBootApplication") || className.endsWith("Application")) {
            return "application";
        }
        if (file.annotations().contains("@Scheduled") || className.endsWith("Scheduler")) {
            return "scheduler";
        }
        if (className.endsWith("Consumer") || className.contains("Listener")) {
            return "message-consumer";
        }
        if (className.endsWith("Publisher") || className.endsWith("Producer")) {
            return "message-publisher";
        }
        if (className.endsWith("Runner")) {
            return "startup-runner";
        }
        if (className.endsWith("Filter")) {
            return "filter";
        }
        if (className.endsWith("Interceptor")) {
            return "interceptor";
        }
        if (className.endsWith("Aspect")) {
            return "aspect";
        }
        return "";
    }

    private String modulePath(ScannedJavaFile file) {
        int lastSlash = file.path().lastIndexOf('/');
        return lastSlash < 0 ? "." : file.path().substring(0, lastSlash);
    }

    private String businessModule(ScannedJavaFile file) {
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

    private String languageForPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            return "Java";
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return "YAML";
        }
        if (lower.endsWith(".properties")) {
            return "Properties";
        }
        if (lower.endsWith(".xml") || lower.equals("pom.xml")) {
            return "XML";
        }
        if (lower.endsWith(".json")) {
            return "JSON";
        }
        if (lower.endsWith(".md")) {
            return "Markdown";
        }
        return "";
    }

    private String moduleFromPath(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) {
            return "root";
        }
        String parent = normalized.substring(0, slash);
        int parentSlash = parent.lastIndexOf('/');
        return parentSlash < 0 ? parent : parent.substring(parentSlash + 1);
    }

    private List<String> extractConfigKeys(String path, String content) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".properties")) {
            return extractPropertyKeys(content);
        }
        if (lower.endsWith(".env.example") || lower.endsWith(".env.sample") || lower.endsWith("env.example")) {
            return extractEnvKeys(content);
        }
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
            return extractYamlKeys(content);
        }
        return List.of();
    }

    private List<String> extractPropertyKeys(String content) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            Matcher matcher = PROPERTY_KEY_PATTERN.matcher(line);
            if (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys.stream().limit(200).toList();
    }

    private List<String> extractEnvKeys(String content) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (String line : content.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue;
            }
            Matcher matcher = ENV_KEY_PATTERN.matcher(line);
            if (matcher.find()) {
                keys.add(matcher.group(1));
            }
        }
        return keys.stream().limit(200).toList();
    }

    private List<String> extractYamlKeys(String content) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        List<YamlSegment> stack = new ArrayList<>();
        for (String rawLine : content.lines().toList()) {
            String trimmed = rawLine.trim();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.equals("---")) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                addYamlListConfigKey(keys, stack, trimmed.substring(2).trim());
                continue;
            }
            Matcher matcher = YAML_KEY_PATTERN.matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }
            int indent = leadingSpaces(rawLine);
            while (!stack.isEmpty() && stack.getLast().indent() >= indent) {
                stack.removeLast();
            }
            String key = cleanConfigKey(matcher.group(1));
            if (key.isBlank()) {
                continue;
            }
            String fullKey = joinYamlKey(stack, key);
            String value = matcher.group(2).trim();
            if (!value.isBlank() && !value.equals("|") && !value.equals(">")) {
                keys.add(fullKey);
            }
            if (value.isBlank() || value.equals("|") || value.equals(">")) {
                stack.add(new YamlSegment(indent, key));
            }
        }
        return keys.stream().limit(300).toList();
    }

    private void addYamlListConfigKey(Set<String> keys, List<YamlSegment> stack, String item) {
        Matcher matcher = ENV_KEY_PATTERN.matcher(item);
        if (matcher.find()) {
            keys.add(joinYamlKey(stack, matcher.group(1)));
        }
    }

    private List<CodeMapRoutingHint> sqlHintsFromSqlContent(String file, String content, String owner) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        addSqlMatches(hints, SQL_CREATE_TABLE_PATTERN, "sql_table", file, owner, content);
        addSqlMatches(hints, SQL_CREATE_INDEX_PATTERN, "sql_index", file, owner, content);
        return mergeHints(hints);
    }

    private List<CodeMapRoutingHint> sqlTableReferenceHints(String file, String content, String owner) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        Matcher matcher = SQL_TABLE_REFERENCE_PATTERN.matcher(content);
        while (matcher.find() && hints.size() < 200) {
            String tableName = cleanSqlIdentifier(matcher.group(2));
            if (isUsefulSqlName(tableName)) {
                hints.add(new CodeMapRoutingHint("sql_table", tableName, file, owner));
            }
        }
        return mergeHints(hints);
    }

    private List<CodeMapRoutingHint> sqlAnnotationTableHints(ScannedJavaFile file, String content) {
        List<CodeMapRoutingHint> hints = new ArrayList<>();
        Matcher annotationMatcher = SQL_ANNOTATION_PATTERN.matcher(content);
        while (annotationMatcher.find() && hints.size() < 200) {
            String sqlText = quotedText(annotationMatcher.group(1));
            hints.addAll(sqlTableReferenceHints(file.path(), sqlText, file.className()));
        }
        return mergeHints(hints);
    }

    private void addSqlMatches(
            List<CodeMapRoutingHint> hints,
            Pattern pattern,
            String kind,
            String file,
            String owner,
            String content
    ) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && hints.size() < 200) {
            String name = cleanSqlIdentifier(matcher.group(1));
            if (isUsefulSqlName(name)) {
                hints.add(new CodeMapRoutingHint(kind, name, file, owner));
            }
        }
    }

    private String quotedText(String value) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(value);
        while (matcher.find() && parts.size() < 20) {
            parts.add(matcher.group(1));
        }
        return String.join(" ", parts);
    }

    private Optional<String> readProjectFile(ProjectScan scan, String relativePath) {
        try {
            Path root = Path.of(scan.rootPath()).toAbsolutePath().normalize();
            Path file = root.resolve(relativePath).toAbsolutePath().normalize();
            if (!file.startsWith(root) || !Files.exists(file) || !Files.isRegularFile(file)) {
                return Optional.empty();
            }
            if (Files.size(file) > 500_000) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @SafeVarargs
    private final List<CodeMapRoutingHint> mergeHints(List<CodeMapRoutingHint>... hintGroups) {
        Map<String, CodeMapRoutingHint> hintsByKey = new LinkedHashMap<>();
        for (List<CodeMapRoutingHint> hintGroup : hintGroups) {
            for (CodeMapRoutingHint hint : hintGroup) {
                if (hint == null || hint.name() == null || hint.name().isBlank()) {
                    continue;
                }
                hintsByKey.putIfAbsent(
                        hint.kind() + "|" + hint.name() + "|" + hint.file() + "|" + hint.owner(),
                        hint
                );
            }
        }
        return hintsByKey.values().stream()
                .sorted(Comparator.comparing(CodeMapRoutingHint::file)
                        .thenComparing(CodeMapRoutingHint::kind)
                        .thenComparing(CodeMapRoutingHint::name))
                .limit(500)
                .toList();
    }

    private Optional<String> extractFirst(Pattern pattern, String content, int group) {
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(group));
        }
        return Optional.empty();
    }

    private String testTargetClass(String testClassName) {
        for (String suffix : List.of("Tests", "Test", "IT", "Spec")) {
            if (testClassName.endsWith(suffix) && testClassName.length() > suffix.length()) {
                return testClassName.substring(0, testClassName.length() - suffix.length());
            }
        }
        return "";
    }

    private String fileNameWithoutExtension(String path) {
        String name = Path.of(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private String simpleName(String value) {
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private String cleanConfigKey(String key) {
        return key.replaceAll("^['\"]|['\"]$", "").trim();
    }

    private int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private String joinYamlKey(List<YamlSegment> stack, String key) {
        List<String> parts = new ArrayList<>();
        for (YamlSegment segment : stack) {
            parts.add(segment.name());
        }
        parts.add(key);
        return String.join(".", parts);
    }

    private String cleanSqlIdentifier(String value) {
        String cleaned = value == null ? "" : value.trim();
        cleaned = cleaned.replaceAll("[`\"\\[\\]]", "");
        int dot = cleaned.lastIndexOf('.');
        if (dot >= 0) {
            cleaned = cleaned.substring(dot + 1);
        }
        return cleaned;
    }

    private boolean isUsefulSqlName(String value) {
        return value != null
                && !value.isBlank()
                && !Set.of("select", "where", "values", "set").contains(value.toLowerCase(Locale.ROOT));
    }

    private List<String> domainTermsForFile(ScannedJavaFile file) {
        List<String> values = new ArrayList<>();
        values.add(file.className());
        values.add(file.path());
        values.addAll(file.methods());
        values.addAll(file.endpoints());
        values.addAll(file.dependencies());
        values.addAll(file.technologies());
        return domainTermsFromValues(values);
    }

    private List<String> domainTermsFromValues(List<String> values) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String value : values) {
            terms.addAll(tokenize(value));
        }
        addCompoundTerms(terms);
        return terms.stream().limit(60).toList();
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String separated = value
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("([a-z\\d])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .toLowerCase(Locale.ROOT);
        return java.util.Arrays.stream(separated.split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> token.length() >= 3 || token.equals("mq") || token.equals("id"))
                .filter(token -> !STOP_WORDS.contains(token))
                .distinct()
                .toList();
    }

    private void addCompoundTerms(Set<String> terms) {
        addIfAllPresent(terms, "flash-sale", "flash", "sale");
        addIfAllPresent(terms, "voucher-order", "voucher", "order");
        addIfAllPresent(terms, "rate-limit", "rate", "limit");
        addIfAllPresent(terms, "stock-release", "stock", "release");
        addIfAllPresent(terms, "order-close", "order", "close");
        addIfAllPresent(terms, "auth-token", "auth", "token");
        addIfAllPresent(terms, "redis-lua", "redis", "lua");
        if (terms.contains("rocketmq") || (terms.contains("rocket") && terms.contains("mq"))) {
            terms.add("rocketmq");
        }
    }

    private void addIfAllPresent(Set<String> terms, String compound, String first, String second) {
        if (terms.contains(first) && terms.contains(second)) {
            terms.add(compound);
        }
    }

    private static final Set<String> STOP_WORDS = Set.of(
            "src", "main", "java", "test", "github", "com", "org", "net", "io",
            "acme", "lifeservice",
            "controller", "service", "impl", "repository", "dao", "mapper", "entity", "dto", "request", "response",
            "config", "configuration", "common", "infrastructure", "application", "domain",
            "get", "post", "put", "delete", "patch", "api", "class", "method", "handler",
            "list", "page", "query", "create", "update", "find", "execute", "path", "string"
    );

    private record YamlSegment(int indent, String name) {
    }
}
