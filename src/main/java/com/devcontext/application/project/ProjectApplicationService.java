package com.devcontext.application.project;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.project.ProjectRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectApplicationService {

    private final ProjectRepository projectRepository;

    public ProjectApplicationService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Project createProject(String name, String rootPath, String defaultBranch) {
        Path normalizedPath = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.exists(normalizedPath) || !Files.isDirectory(normalizedPath)) {
            throw new ApiException("PROJECT_PATH_INVALID", "Project path does not exist or is not a directory", HttpStatus.BAD_REQUEST);
        }
        Instant now = Instant.now();
        Project project = new Project(
                null,
                requireText(name, "name"),
                normalizedPath.toString(),
                detectLanguage(normalizedPath),
                detectFramework(normalizedPath),
                normalizeDefaultBranch(defaultBranch),
                now,
                now
        );
        return projectRepository.save(project);
    }

    public Project updateProject(Long projectId, String name, String rootPath, String defaultBranch) {
        Project current = getProject(projectId);
        String nextName = name == null || name.isBlank() ? current.name() : name.trim();
        Path nextPath = rootPath == null || rootPath.isBlank()
                ? Path.of(current.rootPath()).toAbsolutePath().normalize()
                : Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.exists(nextPath) || !Files.isDirectory(nextPath)) {
            throw new ApiException("PROJECT_PATH_INVALID", "Project path does not exist or is not a directory", HttpStatus.BAD_REQUEST);
        }
        String nextDefaultBranch = defaultBranch == null || defaultBranch.isBlank()
                ? current.defaultBranch()
                : defaultBranch.trim();
        Project updated = new Project(
                current.id(),
                nextName,
                nextPath.toString(),
                detectLanguage(nextPath),
                detectFramework(nextPath),
                normalizeDefaultBranch(nextDefaultBranch),
                current.createdAt(),
                Instant.now()
        );
        return projectRepository.update(updated);
    }

    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    public Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException("PROJECT_NOT_FOUND", "Project not found", HttpStatus.NOT_FOUND));
    }

    public void deleteProject(Long projectId) {
        getProject(projectId);
        projectRepository.deleteById(projectId);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException("PROJECT_FIELD_REQUIRED", fieldName + " is required", HttpStatus.BAD_REQUEST);
        }
        return value.trim();
    }

    private String normalizeDefaultBranch(String defaultBranch) {
        return defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.trim();
    }

    private String detectLanguage(Path rootPath) {
        if (Files.exists(rootPath.resolve("pom.xml")) || Files.exists(rootPath.resolve("build.gradle"))) {
            return "Java";
        }
        if (Files.exists(rootPath.resolve("package.json"))) {
            return "JavaScript/TypeScript";
        }
        if (Files.exists(rootPath.resolve("pyproject.toml")) || Files.exists(rootPath.resolve("requirements.txt"))) {
            return "Python";
        }
        return "Unknown";
    }

    private String detectFramework(Path rootPath) {
        Path pom = rootPath.resolve("pom.xml");
        if (Files.exists(pom)) {
            return "Maven project";
        }
        if (Files.exists(rootPath.resolve("build.gradle"))) {
            return "Gradle project";
        }
        return "Unknown";
    }
}
