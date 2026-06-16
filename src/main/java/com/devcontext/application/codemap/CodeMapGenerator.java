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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class CodeMapGenerator {

    public CodeMap generate(Project project, ProjectScan scan) {
        List<CodeModule> modules = modules(scan);
        List<CodeEntrypoint> entrypoints = entrypoints(scan);
        List<CodeSymbol> symbols = symbols(scan);
        List<CodeEndpoint> endpoints = endpoints(scan);
        List<CodeDependency> dependencies = dependencies(scan);
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
                List.<CodeMapConfigKey>of(),
                List.<CodeMapRoutingHint>of(),
                routingHints(symbols, "mapper"),
                routingHints(symbols, "entity"),
                List.<CodeMapTestRelation>of(),
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
                    List.of(symbolRole(file))
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
        if (file.annotations().contains("@Service") || lowerPath.contains("/service/")) {
            return "service";
        }
        if (className.endsWith("Mapper") || lowerPath.contains("/mapper/")) {
            return "mapper";
        }
        if (className.endsWith("Entity") || lowerPath.contains("/entity/")) {
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
            "controller", "service", "impl", "mapper", "entity", "dto", "request", "response",
            "config", "configuration", "common", "infrastructure", "application", "domain",
            "get", "post", "put", "delete", "patch", "api", "class", "method", "handler",
            "list", "page", "query", "create", "update", "find", "execute", "path", "string"
    );
}
