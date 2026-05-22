package com.devcontext.context.provider;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.context.ContextProvider;
import com.devcontext.context.ContextRequest;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.project.Project;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ProjectSummaryContextProvider implements ContextProvider {

    private final ProjectApplicationService projectService;

    public ProjectSummaryContextProvider(ProjectApplicationService projectService) {
        this.projectService = projectService;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request.projectId() != null;
    }

    @Override
    public List<ContextItem> provide(ContextRequest request) {
        Project project = projectService.getProject(request.projectId());
        String content = """
                Project: %s
                Root path: %s
                Language: %s
                Framework: %s
                Default branch: %s
                """.formatted(
                project.name(),
                project.rootPath(),
                project.language(),
                project.framework(),
                project.defaultBranch()
        );
        return List.of(new ContextItem(
                null,
                null,
                project.id(),
                "PROJECT_SUMMARY",
                "Project summary",
                content,
                "project:" + project.id(),
                100,
                Math.max(1, content.length() / 4),
                sha256(content),
                Instant.now()
        ));
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

