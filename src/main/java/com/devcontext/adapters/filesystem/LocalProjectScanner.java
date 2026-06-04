package com.devcontext.adapters.filesystem;

import com.devcontext.domain.project.ProjectScan;
import com.devcontext.domain.project.ScannedJavaFile;
import com.devcontext.ports.project.ProjectScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class LocalProjectScanner implements ProjectScanner {

    private static final List<String> IGNORED_DIRS = List.of(
            ".git",
            ".ai",
            ".idea",
            ".vscode",
            ".gradle",
            "target",
            "build",
            "out",
            "dist",
            "node_modules",
            "coverage",
            "data",
            "logs"
    );
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b(public|protected|private)\\s+[^=;{}]+\\s+(\\w+)\\s*\\(");
    private static final Pattern FIELD_DEPENDENCY_PATTERN = Pattern.compile("(?m)^\\s*(?:@\\w+(?:\\([^\\n]*\\))?\\s*)*(?:private|protected|public)\\s+(?:final\\s+)?([A-Z][\\w.$]*(?:<[^;]+>)?)\\s+\\w+\\s*(?:=[^;]*)?;");
    private static final Pattern MAPPING_PATTERN = Pattern.compile("@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern CLASS_PATTERN = Pattern.compile("\\bclass\\s+(\\w+)\\b");

    @Override
    public ProjectScan scan(String rootPath) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        String pomContent = readIfExists(root.resolve("pom.xml")).orElse("");
        boolean hasPom = !pomContent.isBlank();
        boolean hasGradle = Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"));
        boolean hasPackageJson = Files.exists(root.resolve("package.json"));
        boolean hasPyproject = Files.exists(root.resolve("pyproject.toml"));
        boolean hasRequirements = Files.exists(root.resolve("requirements.txt"));
        boolean hasGoMod = Files.exists(root.resolve("go.mod"));
        boolean hasCargo = Files.exists(root.resolve("Cargo.toml"));
        boolean springBoot = pomContent.contains("spring-boot-starter") || containsSpringBootApplication(root);

        Map<String, String> commands = detectCommands(root, hasPom, hasGradle, hasPackageJson, hasPyproject, hasRequirements, hasGoMod, hasCargo);
        List<String> docs = findDocs(root);
        List<String> todos = buildTodos(root, commands, springBoot, docs);

        return new ProjectScan(
                normalize(root.toString()),
                detectLanguage(hasPom, hasGradle, hasPackageJson, hasPyproject, hasRequirements, hasGoMod, hasCargo),
                detectFramework(hasPom, hasGradle, hasPackageJson, springBoot),
                detectBuildTool(hasPom, hasGradle, hasPackageJson, hasPyproject, hasRequirements, hasGoMod, hasCargo),
                springBoot,
                readGitValue(root, "rev-parse", "--abbrev-ref", "HEAD").orElse("unknown"),
                readGitValue(root, "rev-parse", "--short", "HEAD").orElse("unknown"),
                findDirectories(root),
                existingPaths(root, "src/main/java", "src/main/kotlin", "src"),
                existingPaths(root, "src/main/resources", "resources"),
                existingPaths(root, "src/test/java", "src/test/kotlin", "test", "tests"),
                findConfigFiles(root),
                docs,
                findJavaFiles(root),
                commands,
                todos
        );
    }

    private boolean containsSpringBootApplication(Path root) {
        Path javaRoot = root.resolve("src/main/java");
        if (!Files.exists(javaRoot)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(javaRoot, 30)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .limit(300)
                    .anyMatch(path -> readIfExists(path).orElse("").contains("@SpringBootApplication"));
        } catch (IOException e) {
            return false;
        }
    }

    private List<String> findDirectories(Path root) {
        try (Stream<Path> stream = Files.walk(root, 2)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> !isIgnored(root, path))
                    .map(path -> relative(root, path))
                    .sorted()
                    .limit(100)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> findConfigFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root, 5)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(root, path))
                    .map(path -> relative(root, path))
                    .filter(this::isConfigFile)
                    .sorted()
                    .limit(100)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> findDocs(Path root) {
        try (Stream<Path> stream = Files.walk(root, 4)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(root, path))
                    .map(path -> relative(root, path))
                    .filter(path -> path.equalsIgnoreCase("README.md")
                            || path.startsWith("docs/")
                            || path.endsWith(".md"))
                    .sorted()
                    .limit(100)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<ScannedJavaFile> findJavaFiles(Path root) {
        Path javaRoot = root.resolve("src/main/java");
        if (!Files.exists(javaRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(javaRoot, 30)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !isIgnored(root, path))
                    .sorted()
                    .limit(200)
                    .map(path -> parseJavaFile(root, path))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private ScannedJavaFile parseJavaFile(Path root, Path file) {
        String content = readIfExists(file).orElse("");
        Matcher packageMatcher = PACKAGE_PATTERN.matcher(content);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        String fileName = file.getFileName().toString();
        String className = fileName.substring(0, fileName.length() - ".java".length());
        List<String> annotations = content.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("@"))
                .map(line -> line.split("\\(", 2)[0])
                .distinct()
                .limit(20)
                .toList();
        List<String> methods = extractMethods(content);
        return new ScannedJavaFile(
                relative(root, file),
                packageName,
                className,
                annotations,
                methods,
                extractEndpoints(content),
                extractDependencies(content),
                detectTechnologies(content)
        );
    }

    private List<String> extractMethods(String content) {
        List<String> methods = new ArrayList<>();
        Matcher matcher = METHOD_PATTERN.matcher(content);
        while (matcher.find() && methods.size() < 30) {
            String methodName = matcher.group(2);
            if (!methodName.equals("if") && !methodName.equals("for") && !methodName.equals("while")) {
                methods.add(methodName);
            }
        }
        return methods.stream().distinct().toList();
    }

    private List<String> extractEndpoints(String content) {
        List<String> endpoints = new ArrayList<>();
        List<String> pendingMappings = new ArrayList<>();
        String classPath = "";
        for (String rawLine : content.lines().toList()) {
            String line = rawLine.trim();
            Matcher mappingMatcher = MAPPING_PATTERN.matcher(line);
            while (mappingMatcher.find()) {
                pendingMappings.add(mappingMatcher.group(1) + " " + extractMappingPath(mappingMatcher.group(2)));
            }

            if (CLASS_PATTERN.matcher(line).find()) {
                classPath = firstMappingPath(pendingMappings);
                pendingMappings.clear();
                continue;
            }

            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.find()) {
                for (String mapping : pendingMappings) {
                    String[] parts = mapping.split(" ", 2);
                    String httpMethod = httpMethod(parts[0]);
                    String methodPath = parts.length > 1 ? parts[1] : "";
                    endpoints.add(httpMethod + " " + normalizeEndpointPath(classPath, methodPath) + " -> " + methodMatcher.group(2));
                }
                pendingMappings.clear();
            }
        }
        return endpoints.stream().distinct().limit(30).toList();
    }

    private String firstMappingPath(List<String> mappings) {
        return mappings.stream()
                .map(mapping -> mapping.split(" ", 2))
                .filter(parts -> parts.length > 1)
                .map(parts -> parts[1])
                .filter(path -> !path.isBlank())
                .findFirst()
                .orElse("");
    }

    private String extractMappingPath(String annotationArguments) {
        if (annotationArguments == null || annotationArguments.isBlank()) {
            return "";
        }
        Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(annotationArguments);
        if (quoted.find()) {
            return quoted.group(1);
        }
        return "";
    }

    private String httpMethod(String mappingAnnotation) {
        return switch (mappingAnnotation) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> "ANY";
        };
    }

    private String normalizeEndpointPath(String classPath, String methodPath) {
        String combined = ("/" + stripSlashes(classPath) + "/" + stripSlashes(methodPath))
                .replaceAll("/{2,}", "/");
        if (combined.length() > 1 && combined.endsWith("/")) {
            return combined.substring(0, combined.length() - 1);
        }
        return combined;
    }

    private String stripSlashes(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    private List<String> extractDependencies(String content) {
        List<String> dependencies = new ArrayList<>();
        Matcher matcher = FIELD_DEPENDENCY_PATTERN.matcher(content);
        while (matcher.find() && dependencies.size() < 30) {
            dependencies.add(simpleTypeName(matcher.group(1)));
        }
        return dependencies.stream()
                .filter(value -> !value.isBlank())
                .filter(this::isLikelyDependencyType)
                .distinct()
                .toList();
    }

    private String simpleTypeName(String value) {
        String cleaned = value.replaceAll("<.*>", "").trim();
        int lastDot = cleaned.lastIndexOf('.');
        if (lastDot >= 0) {
            cleaned = cleaned.substring(lastDot + 1);
        }
        return cleaned;
    }

    private boolean isLikelyDependencyType(String value) {
        return !List.of(
                "String",
                "Integer",
                "Long",
                "Double",
                "Float",
                "Boolean",
                "BigDecimal",
                "BigInteger",
                "LocalDate",
                "LocalDateTime",
                "Instant",
                "List",
                "Map",
                "Set",
                "Optional"
        ).contains(value);
    }

    private List<String> detectTechnologies(String content) {
        List<String> technologies = new ArrayList<>();
        if (content.contains("StringRedisTemplate") || content.contains("RedisTemplate")) {
            technologies.add("Redis");
        }
        if (content.contains("DefaultRedisScript") || content.toLowerCase().contains("lua")) {
            technologies.add("Redis Lua");
        }
        if (content.contains("RocketMQ") || content.contains("RocketMQTemplate") || content.contains("RocketMQListener")) {
            technologies.add("RocketMQ");
        }
        if (content.contains("MeterRegistry") || content.contains("Counter.builder") || content.contains("Gauge.builder")) {
            technologies.add("Micrometer metrics");
        }
        if (content.contains("Caffeine") || content.contains("Cache<")) {
            technologies.add("Caffeine");
        }
        if (content.contains("com.baomidou.mybatisplus")) {
            technologies.add("MyBatis-Plus");
        }
        if (content.contains("@Scheduled")) {
            technologies.add("Spring Scheduler");
        }
        if (content.contains("@Transactional")) {
            technologies.add("Spring Transaction");
        }
        return technologies.stream().distinct().toList();
    }

    private List<String> existingPaths(Path root, String... candidates) {
        List<String> paths = new ArrayList<>();
        for (String candidate : candidates) {
            if (Files.exists(root.resolve(candidate))) {
                paths.add(candidate);
            }
        }
        return paths;
    }

    private Map<String, String> detectCommands(
            Path root,
            boolean hasPom,
            boolean hasGradle,
            boolean hasPackageJson,
            boolean hasPyproject,
            boolean hasRequirements,
            boolean hasGoMod,
            boolean hasCargo
    ) {
        Map<String, String> commands = new LinkedHashMap<>();
        if (Files.exists(root.resolve("compose.yaml")) || Files.exists(root.resolve("docker-compose.yml"))) {
            commands.put("docker-start", "docker compose up -d --build");
            commands.put("docker-stop", "docker compose down");
            commands.put("docker-reset", "docker compose down -v");
        }
        if (hasPom) {
            commands.put("build", "mvn clean package");
            commands.put("test", "mvn test");
            commands.putIfAbsent("run", "mvn spring-boot:run");
        } else if (hasGradle) {
            commands.put("build", "./gradlew build");
            commands.put("test", "./gradlew test");
            commands.putIfAbsent("run", "./gradlew bootRun");
        } else if (hasPackageJson) {
            commands.put("install", "npm install");
            commands.put("test", "npm test");
            commands.putIfAbsent("run", "npm run dev");
        } else if (hasPyproject || hasRequirements) {
            commands.put("test", "pytest");
            commands.putIfAbsent("run", "python -m app");
        } else if (hasGoMod) {
            commands.put("build", "go build ./...");
            commands.put("test", "go test ./...");
        } else if (hasCargo) {
            commands.put("build", "cargo build");
            commands.put("test", "cargo test");
        }
        if (Files.exists(root.resolve("frontend/package.json"))) {
            commands.put("frontend-install", "cd frontend && npm install");
            commands.put("frontend-dev", "cd frontend && npm run dev");
        }
        return commands;
    }

    private List<String> buildTodos(Path root, Map<String, String> commands, boolean springBoot, List<String> docs) {
        List<String> todos = new ArrayList<>();
        todos.add("Fill in business context in .ai/manual/business-context.md.");
        todos.add("Confirm core flows in .ai/generated/core-flows.md.");
        if (docs.stream().noneMatch(path -> path.equalsIgnoreCase("README.md"))) {
            todos.add("Add or review README information; no root README.md was detected.");
        }
        if (commands.isEmpty()) {
            todos.add("Confirm build, test, and run commands manually.");
        }
        if (!springBoot && Files.exists(root.resolve("pom.xml"))) {
            todos.add("Confirm whether this Maven project uses Spring Boot.");
        }
        return todos;
    }

    private String detectLanguage(
            boolean hasPom,
            boolean hasGradle,
            boolean hasPackageJson,
            boolean hasPyproject,
            boolean hasRequirements,
            boolean hasGoMod,
            boolean hasCargo
    ) {
        if (hasPom || hasGradle) {
            return "Java";
        }
        if (hasPackageJson) {
            return "JavaScript/TypeScript";
        }
        if (hasPyproject || hasRequirements) {
            return "Python";
        }
        if (hasGoMod) {
            return "Go";
        }
        if (hasCargo) {
            return "Rust";
        }
        return "Unknown";
    }

    private String detectFramework(boolean hasPom, boolean hasGradle, boolean hasPackageJson, boolean springBoot) {
        if (springBoot) {
            return "Spring Boot";
        }
        if (hasPom) {
            return "Maven project";
        }
        if (hasGradle) {
            return "Gradle project";
        }
        if (hasPackageJson) {
            return "Node.js project";
        }
        return "Unknown";
    }

    private String detectBuildTool(
            boolean hasPom,
            boolean hasGradle,
            boolean hasPackageJson,
            boolean hasPyproject,
            boolean hasRequirements,
            boolean hasGoMod,
            boolean hasCargo
    ) {
        if (hasPom) {
            return "Maven";
        }
        if (hasGradle) {
            return "Gradle";
        }
        if (hasPackageJson) {
            return "npm";
        }
        if (hasPyproject || hasRequirements) {
            return "Python";
        }
        if (hasGoMod) {
            return "Go";
        }
        if (hasCargo) {
            return "Cargo";
        }
        return "Unknown";
    }

    private boolean isConfigFile(String path) {
        String name = Path.of(path).getFileName().toString();
        return name.equals("pom.xml")
                || name.equals("build.gradle")
                || name.equals("build.gradle.kts")
                || name.equals("package.json")
                || name.equals("pyproject.toml")
                || name.equals("requirements.txt")
                || name.equals("go.mod")
                || name.equals("Cargo.toml")
                || name.equals("Dockerfile")
                || name.startsWith("application.")
                || name.endsWith(".yml")
                || name.endsWith(".yaml")
                || name.endsWith(".properties");
    }

    private boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            if (IGNORED_DIRS.contains(part.toString().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private Optional<String> readIfExists(Path path) {
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            if (Files.size(path) > 500_000) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readGitValue(Path root, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return Optional.of(new String(process.getInputStream().readAllBytes()).trim())
                    .filter(value -> !value.isBlank());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private String relative(Path root, Path path) {
        return normalize(root.relativize(path.toAbsolutePath().normalize()).toString());
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
