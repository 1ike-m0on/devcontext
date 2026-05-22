package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.context.ProjectContextAssetApplicationService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.context.ContextDocumentStatus;
import com.devcontext.domain.context.ContextGenerationResult;
import com.devcontext.domain.context.ProjectContextStatus;
import com.devcontext.domain.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:target/devcontext-test.sqlite")
@AutoConfigureMockMvc
class Mvp1ProjectContextAssetsTests {

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private ProjectContextAssetApplicationService contextAssetService;

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    private Path projectRoot;

    @Test
    void generatesProjectContextAssetsAndPreservesManualFiles() throws Exception {
        createSpringBootFixture(projectRoot);

        Project project = projectService.createProject("demo-context-project", projectRoot.toString(), "main");
        ContextGenerationResult result = contextAssetService.generate(project.id(), true, false);

        assertThat(result.generatedFiles()).contains(
                "AGENTS.md",
                ".ai/AI_README.md",
                ".ai/code-map.json",
                ".ai/generated/project-structure.md",
                ".ai/generated/tech-architecture.md",
                ".ai/generated/dev-guide.md",
                ".ai/generated/core-flows.md"
        );
        assertThat(result.manualCreatedFiles()).contains(
                ".ai/manual/business-context.md",
                ".ai/manual/coding-preferences.md",
                ".ai/manual/decisions.md",
                ".ai/manual/pitfalls.md"
        );
        assertThat(result.todos()).contains("Fill in business context in .ai/manual/business-context.md.");

        assertThat(Files.readString(projectRoot.resolve("AGENTS.md"))).contains("Project AI Context Entry");
        assertThat(Files.readString(projectRoot.resolve(".ai/AI_README.md"))).contains("demo-context-project");
        assertThat(Files.readString(projectRoot.resolve(".ai/generated/dev-guide.md"))).contains("mvn test");
        assertThat(Files.readString(projectRoot.resolve(".ai/generated/project-structure.md")))
                .doesNotContain(".ai/reviews")
                .doesNotContain("data")
                .doesNotContain("target");
        assertThat(Files.readString(projectRoot.resolve(".ai/code-map.json")))
                .contains("Spring Boot")
                .contains("HelloController")
                .contains("mvn spring-boot:run")
                .doesNotContain("review-1.md")
                .doesNotContain("devcontext.sqlite");

        Path manualBusinessContext = projectRoot.resolve(".ai/manual/business-context.md");
        Files.writeString(manualBusinessContext, "custom business context");

        String secondResponse = mockMvc.perform(post("/api/projects/{projectId}/context/generate", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "overwriteGenerated": true,
                                  "overwriteManual": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(secondResponse).contains("manualSkippedFiles", ".ai/manual/business-context.md");
        assertThat(Files.readString(manualBusinessContext)).isEqualTo("custom business context");

        ProjectContextStatus status = contextAssetService.getStatus(project.id());
        assertThat(status.documents())
                .extracting(ContextDocumentStatus::path)
                .contains("AGENTS.md", ".ai/code-map.json", ".ai/manual/business-context.md");
        assertThat(status.documents())
                .filteredOn(document -> document.path().equals("AGENTS.md"))
                .allMatch(ContextDocumentStatus::exists);

        mockMvc.perform(get("/api/projects/{projectId}/context", project.id()))
                .andExpect(status().isOk());
    }

    private void createSpringBootFixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/com/example/web"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.createDirectories(root.resolve("src/test/java/com/example"));
        Files.createDirectories(root.resolve(".ai/reviews"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(root.resolve("README.md"), "# Demo Context Project\n");
        Files.writeString(root.resolve(".ai/reviews/review-1.md"), "# Old Review\n");
        Files.writeString(root.resolve("data/devcontext.sqlite"), "sqlite-data");
        Files.writeString(root.resolve("logs/app.log"), "log-data");
        Files.writeString(root.resolve("src/main/resources/application.yml"), "server:\n  port: 18080\n");
        Files.writeString(root.resolve("src/main/java/com/example/DemoApplication.java"), """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class DemoApplication {
                    public static void main(String[] args) {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/example/web/HelloController.java"), """
                package com.example.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class HelloController {
                    @GetMapping("/hello")
                    public String hello() {
                        return "hello";
                    }
                }
                """);
        Files.writeString(root.resolve("src/test/java/com/example/DemoApplicationTests.java"), """
                package com.example;

                class DemoApplicationTests {
                }
                """);
    }
}
