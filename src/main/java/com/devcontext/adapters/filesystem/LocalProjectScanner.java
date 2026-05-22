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

        Map<String, String> commands = detectCommands(hasPom, hasGradle, hasPackageJson, hasPyproject, hasRequirements, hasGoMod, hasCargo);
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
        return new ScannedJavaFile(relative(root, file), packageName, className, annotations, methods);
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
            boolean hasPom,
            boolean hasGradle,
            boolean hasPackageJson,
            boolean hasPyproject,
            boolean hasRequirements,
            boolean hasGoMod,
            boolean hasCargo
    ) {
        Map<String, String> commands = new LinkedHashMap<>();
        if (hasPom) {
            commands.put("build", "mvn clean package");
            commands.put("test", "mvn test");
            commands.put("run", "mvn spring-boot:run");
            return commands;
        }
        if (hasGradle) {
            commands.put("build", "./gradlew build");
            commands.put("test", "./gradlew test");
            commands.put("run", "./gradlew bootRun");
            return commands;
        }
        if (hasPackageJson) {
            commands.put("install", "npm install");
            commands.put("test", "npm test");
            commands.put("run", "npm run dev");
            return commands;
        }
        if (hasPyproject || hasRequirements) {
            commands.put("test", "pytest");
            commands.put("run", "python -m app");
            return commands;
        }
        if (hasGoMod) {
            commands.put("build", "go build ./...");
            commands.put("test", "go test ./...");
            return commands;
        }
        if (hasCargo) {
            commands.put("build", "cargo build");
            commands.put("test", "cargo test");
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
