package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.llm.MockLlmApplicationService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.context.ContextAssembler;
import com.devcontext.context.ContextRequest;
import com.devcontext.domain.context.ContextItem;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-test.sqlite",
        "devcontext.llm.provider=mock"
})
class Mvp0IntegrationTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private ContextAssembler contextAssembler;

    @Autowired
    private MockLlmApplicationService mockLlmService;

    @Autowired
    private AgentRunApplicationService runService;

    @TempDir
    private Path projectRoot;

    @Test
    void completesMinimumMvp0Flow() throws IOException {
        Files.writeString(projectRoot.resolve("pom.xml"), "<project></project>");

        Project project = projectService.createProject("mvp0-test-project", projectRoot.toString(), "main");

        assertThat(project.id()).isNotNull();
        assertThat(project.language()).isEqualTo("Java");
        assertThat(project.framework()).isEqualTo("Maven project");
        assertThat(projectService.listProjects())
                .extracting(Project::id)
                .contains(project.id());

        List<ContextItem> contextItems = contextAssembler.assemble(new ContextRequest(project.id(), "MVP0_TEST"));

        assertThat(contextItems).hasSize(1);
        assertThat(contextItems.getFirst().type()).isEqualTo("PROJECT_SUMMARY");
        assertThat(contextItems.getFirst().content()).contains("mvp0-test-project");
        assertThat(contextItems.getFirst().hash()).isNotBlank();

        MockLlmApplicationService.MockLlmResult result = mockLlmService.chat(project.id(), "Summarize this project");

        assertThat(result.runId()).isNotNull();
        assertThat(result.response().content()).startsWith("MOCK_RESPONSE:");
        assertThat(result.response().inputTokenEstimate()).isPositive();
        assertThat(result.response().outputTokenEstimate()).isPositive();

        AgentRun run = runService.getRun(result.runId());
        assertThat(run.status()).isEqualTo("success");
        assertThat(run.projectId()).isEqualTo(project.id());

        List<String> eventTypes = runService.listEvents(result.runId()).stream()
                .map(AgentEvent::eventType)
                .toList();

        assertThat(eventTypes).containsExactly(
                "RUN_STARTED",
                "PROMPT_BUILT",
                "LLM_CALLED",
                "RUN_FINISHED"
        );

        Project updated = projectService.updateProject(project.id(), "renamed-mvp0-project", projectRoot.toString(), "develop");
        assertThat(updated.name()).isEqualTo("renamed-mvp0-project");
        assertThat(updated.defaultBranch()).isEqualTo("develop");
        assertThat(projectService.getProject(project.id()).name()).isEqualTo("renamed-mvp0-project");

        projectService.deleteProject(project.id());
        assertThat(projectService.listProjects())
                .extracting(Project::id)
                .doesNotContain(project.id());
    }
}
