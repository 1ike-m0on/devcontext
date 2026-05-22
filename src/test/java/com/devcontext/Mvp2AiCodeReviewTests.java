package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-mvp2-test.sqlite",
        "devcontext.llm.provider=test"
})
@AutoConfigureMockMvc
@Import(Mvp2AiCodeReviewTests.TestLlmConfig.class)
class Mvp2AiCodeReviewTests {

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StubReviewLlmClient llmClient;

    @TempDir
    private Path projectRoot;

    @Test
    void createsAiCodeReviewFromDiffTextAndTracksIssueLifecycle() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("demo-review-project", projectRoot.toString(), "main");

        String createResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "baseBranch": "main",
                                  "compareBranch": "feature/null-safety",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\\n+User user = userRepository.findById(id);\\n+return user.getName();"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createJson = objectMapper.readTree(createResponse).path("data");
        long reviewId = createJson.path("reviewId").asLong();
        long runId = createJson.path("runId").asLong();
        String reportPath = createJson.path("reportPath").asText();

        assertThat(reviewId).isPositive();
        assertThat(runId).isPositive();
        assertThat(reportPath).isEqualTo(".ai/reviews/review-" + reviewId + ".md");
        assertThat(Files.readString(projectRoot.resolve(reportPath)))
                .contains("AI Code Review Report")
                .contains("Possible null dereference");
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("demo-review-project")
                .contains("src/main/java/demo/UserService.java")
                .contains("Prefer explicit null handling.");

        String detailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode detailJson = objectMapper.readTree(detailResponse).path("data");
        JsonNode issueJson = detailJson.path("issues").get(0);
        long issueId = issueJson.path("id").asLong();

        assertThat(detailJson.path("review").path("score").asDouble()).isEqualTo(2.4);
        assertThat(issueJson.path("severity").asText()).isEqualTo("critical");
        assertThat(issueJson.path("status").asText()).isEqualTo("pending");

        String updateResponse = mockMvc.perform(patch("/api/review-issues/{issueId}", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "accepted",
                                  "note": "Valid issue for MVP2 test."
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(updateResponse).path("data").path("status").asText())
                .isEqualTo("accepted");

        String eventsResponse = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("events");
        assertThat(events).hasSize(9);
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .containsExactly(
                        "RUN_STARTED",
                        "GIT_DIFF_COLLECTED",
                        "PROJECT_CONTEXT_LOADED",
                        "DECISION_MEMORY_RECALLED",
                        "PROMPT_BUILT",
                        "LLM_CALLED",
                        "LLM_RESPONSE_PARSED",
                        "REVIEW_ISSUES_SAVED",
                        "RUN_FINISHED"
                );
    }

    private void createReviewFixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.createDirectories(root.resolve(".ai/manual"));
        Files.writeString(root.resolve("pom.xml"), "<project></project>");
        Files.writeString(root.resolve("AGENTS.md"), "Use project AI instructions.");
        Files.writeString(root.resolve(".ai/manual/coding-preferences.md"), "Prefer explicit null handling.");
        Files.writeString(root.resolve("src/main/java/demo/UserService.java"), """
                package demo;

                class UserService {
                    String userName(Long id) {
                        return "";
                    }
                }
                """);
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        StubReviewLlmClient stubReviewLlmClient() {
            return new StubReviewLlmClient();
        }
    }

    static class StubReviewLlmClient implements LlmClient {

        private final AtomicReference<LlmRequest> lastRequest = new AtomicReference<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            return new LlmResponse("""
                    {
                      "score": 2.4,
                      "summary": "Detected null safety risk.",
                      "changeIntent": "Adds user name lookup.",
                      "impactScope": "User service behavior.",
                      "testGaps": ["Missing null case test."],
                      "recommendations": ["Handle missing user explicitly."],
                      "issues": [
                        {
                          "severity": "critical",
                          "title": "Possible null dereference",
                          "filePath": "src/main/java/demo/UserService.java",
                          "lineNumber": 12,
                          "description": "findById may return null before getName.",
                          "impact": "Request may fail with NullPointerException.",
                          "suggestion": "Use Optional or null check.",
                          "confidence": "high"
                        }
                      ]
                    }
                    """, request.modelName(), 180, 72);
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }
    }
}
