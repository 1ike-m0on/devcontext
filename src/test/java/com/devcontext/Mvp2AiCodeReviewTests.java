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
import java.nio.charset.StandardCharsets;
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
        "devcontext.llm.provider=test",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
@Import(Mvp2AiCodeReviewTests.TestLlmConfig.class)
class Mvp2AiCodeReviewTests {

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-mvp2-test.sqlite"));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

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
                .contains("Prefer explicit null handling.")
                .contains("Write all JSON string values in English.")
                .contains("Prefer fewer high-signal issues")
                .contains("concrete runtime, data consistency, security, idempotency, performance, or test coverage impact")
                .contains("Run a mandatory test-coverage pass before returning JSON.")
                .contains("Report a missing focused-test issue only for high-risk behavior changes")
                .contains("A missing focused-test issue is first-class only when the high-risk behavior rule above is met")
                .contains("Do not include speculative business-intent questions as issues")
                .contains("Evidence gate: every issue must be backed by at least one changed diff line")
                .contains("pure speculation belongs in recommendations, not issues")
                .contains("Do not report ORM lazy-loading, transaction-proxy, distributed-lock, cache-staleness, or concurrency assumptions")
                .contains("First decide whether the diff is a safe defensive change")
                .contains("Use REVIEW_FEEDBACK_MEMORY signals to calibrate precision")
                .contains("Do not repeat false_positive_pattern entries")
                .contains("Return at most 4 issues");

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

        String updatedDetailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(updatedDetailResponse).path("data").path("issues").get(0).path("status").asText())
                .isEqualTo("accepted");

        String eventsResponse = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("events");
        assertThat(events).hasSize(12);
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .containsExactly(
                        "RUN_STARTED",
                        "GIT_DIFF_COLLECTED",
                        "PROJECT_CONTEXT_LOADED",
                        "DECISION_MEMORY_RECALLED",
                        "PROMPT_BUILT",
                        "LLM_CALL_STARTED",
                        "LLM_CALLED",
                        "LLM_RESPONSE_PARSED",
                        "REVIEW_ISSUES_FILTERED",
                        "REVIEW_ISSUES_SAVED",
                        "RUN_FINISHED",
                        "REVIEW_ISSUE_STATUS_UPDATED"
                );
        assertThat(events)
                .filteredOn(event -> "LLM_CALLED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("inputSummary").asText()).isEqualTo("test/mock-llm"));
        assertThat(events.get(11).path("inputSummary").asText()).contains("pending -> accepted");
        assertThat(events.get(11).path("outputSummary").asText()).contains("Valid issue for MVP2 test.");

        String observationResponse = mockMvc.perform(get("/api/observations")
                        .param("reviewId", String.valueOf(reviewId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode observations = objectMapper.readTree(observationResponse).path("data");
        assertThat(observations)
                .extracting(observation -> observation.path("sourceType").asText())
                .contains("review_record", "review_issue", "review_feedback");
        assertThat(observations)
                .filteredOn(observation -> "review_feedback".equals(observation.path("sourceType").asText()))
                .first()
                .satisfies(observation -> {
                    assertThat(observation.path("sourceStatus").asText()).isEqualTo("accepted");
                    assertThat(observation.path("reviewId").asLong()).isEqualTo(reviewId);
                    assertThat(observation.path("issueId").asLong()).isEqualTo(issueId);
                    assertThat(observation.path("summary").asText()).contains("Valid issue for MVP2 test.");
                });

        String runResponse = mockMvc.perform(get("/api/agent-runs/{runId}", runId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode runJson = objectMapper.readTree(runResponse).path("data");
        assertThat(runJson.path("provider").asText()).isEqualTo("test");
        assertThat(runJson.path("modelName").asText()).isEqualTo("mock-llm");
    }

    @Test
    void addsFocusedTestGapGuardrailWhenModelOnlyReportsInputValidation() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("discount-review-project", projectRoot.toString(), "main");
        String diffText = readTestResource("code-review/fixtures/missing-test.diff");
        llmClient.nextResponse("""
                {
                  "score": 3.0,
                  "summary": "Detected input validation risk.",
                  "changeIntent": "Adds discount rules.",
                  "impactScope": "Pricing behavior.",
                  "testGaps": [],
                  "recommendations": [],
                  "issues": [
                    {
                      "severity": "critical",
                      "title": "Customer null dereference causes NPE in discount calculation",
                      "filePath": "src/main/java/com/acme/pricing/DiscountService.java",
                      "lineNumber": 21,
                      "description": "The newly added branch directly calls customer.isVip() without null check.",
                      "impact": "Runtime failure in discount calculation for null customer input.",
                      "suggestion": "Add a null guard before accessing Customer methods.",
                      "confidence": "high"
                    }
                  ]
                }
                """);

        String body = objectMapper.createObjectNode()
                .put("sourceType", "manual")
                .put("mode", "strict")
                .put("diffText", diffText)
                .toString();
        String createResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long reviewId = objectMapper.readTree(createResponse).path("data").path("reviewId").asLong();
        String detailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode issues = objectMapper.readTree(detailResponse).path("data").path("issues");
        assertThat(issues).hasSize(2);
        assertThat(issues.toString())
                .contains("Customer null dereference causes NPE in discount calculation")
                .contains("Missing focused tests for VIP and threshold discount rules");
    }

    @Test
    void detectsWorkingTreeReviewSourceAndCreatesReviewWithoutManualDiff() throws Exception {
        createReviewFixture(projectRoot);
        initGitRepository(projectRoot);
        Files.writeString(projectRoot.resolve("src/main/java/demo/UserService.java"), """
                package demo;

                class UserService {
                    String userName(Long id) {
                        User user = userRepository.findById(id);
                        return user.getName();
                    }
                }
                """);
        Project project = projectService.createProject("auto-source-review-project", projectRoot.toString(), "main");

        String sourcesResponse = mockMvc.perform(get("/api/projects/{projectId}/review-sources", project.id()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sources = objectMapper.readTree(sourcesResponse).path("data");
        JsonNode workingTree = null;
        for (JsonNode source : sources) {
            if ("working_tree".equals(source.path("sourceType").asText())) {
                workingTree = source;
                break;
            }
        }
        assertThat(workingTree).isNotNull();
        assertThat(workingTree.path("available").asBoolean()).isTrue();
        assertThat(workingTree.path("recommended").asBoolean()).isTrue();
        assertThat(workingTree.path("changedFileCount").asInt()).isEqualTo(1);
        assertThat(workingTree.path("changedFiles").toString()).contains("src/main/java/demo/UserService.java");

        String createResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "working_tree",
                                  "mode": "strict"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createJson = objectMapper.readTree(createResponse).path("data");
        long reviewId = createJson.path("reviewId").asLong();
        JsonNode detailJson = objectMapper.readTree(mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString()).path("data");

        assertThat(detailJson.path("review").path("baseBranch").asText()).isEqualTo("HEAD");
        assertThat(detailJson.path("review").path("compareBranch").asText()).isEqualTo("working_tree");
        assertThat(detailJson.path("issues")).hasSize(1);
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("diff --git")
                .contains("src/main/java/demo/UserService.java")
                .contains("return user.getName()");
    }

    @Test
    void includesUntrackedFilesInWorkingTreeReviewSourceAndDiff() throws Exception {
        createReviewFixture(projectRoot);
        initGitRepository(projectRoot);
        Files.writeString(projectRoot.resolve("src/main/java/demo/NewEndpoint.java"), """
                package demo;

                class NewEndpoint {
                    String create(CreateRequest request) {
                        return request.user().name();
                    }
                }
                """);
        Project project = projectService.createProject("untracked-review-project", projectRoot.toString(), "main");

        String sourcesResponse = mockMvc.perform(get("/api/projects/{projectId}/review-sources", project.id()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sources = objectMapper.readTree(sourcesResponse).path("data");
        JsonNode workingTree = null;
        for (JsonNode source : sources) {
            if ("working_tree".equals(source.path("sourceType").asText())) {
                workingTree = source;
                break;
            }
        }
        assertThat(workingTree).isNotNull();
        assertThat(workingTree.path("available").asBoolean()).isTrue();
        assertThat(workingTree.path("recommended").asBoolean()).isTrue();
        assertThat(workingTree.path("changedFileCount").asInt()).isEqualTo(1);
        assertThat(workingTree.path("untrackedFileCount").asInt()).isEqualTo(1);
        assertThat(workingTree.path("changedFiles").toString()).contains("src/main/java/demo/NewEndpoint.java");
        assertThat(workingTree.path("warning").asText()).contains("未跟踪文件");

        String createResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "working_tree",
                                  "mode": "strict"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createJson = objectMapper.readTree(createResponse).path("data");
        assertThat(createJson.path("diffTruncated").asBoolean()).isFalse();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("src/main/java/demo/NewEndpoint.java")
                .contains("new file mode 100644")
                .contains("return request.user().name()")
                .contains("diff truncated by DevContext: no");
    }

    @Test
    void limitsWorkingTreeReviewDiffToSelectedFiles() throws Exception {
        createReviewFixture(projectRoot);
        initGitRepository(projectRoot);
        Files.writeString(projectRoot.resolve("src/main/java/demo/UserService.java"), """
                package demo;

                class UserService {
                    String userName(Long id) {
                        User user = userRepository.findById(id);
                        return user.getName();
                    }
                }
                """);
        Files.writeString(projectRoot.resolve("src/main/java/demo/OtherService.java"), """
                package demo;

                class OtherService {
                    String risky() {
                        return "unselected change";
                    }
                }
                """);
        Project project = projectService.createProject("selected-file-review-project", projectRoot.toString(), "main");

        String sourcesResponse = mockMvc.perform(get("/api/projects/{projectId}/review-sources", project.id()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sources = objectMapper.readTree(sourcesResponse).path("data");
        JsonNode workingTree = null;
        for (JsonNode source : sources) {
            if ("working_tree".equals(source.path("sourceType").asText())) {
                workingTree = source;
                break;
            }
        }
        assertThat(workingTree).isNotNull();
        assertThat(workingTree.path("changedFileCount").asInt()).isEqualTo(2);

        mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "working_tree",
                                  "mode": "strict",
                                  "selectedFiles": ["src/main/java/demo/UserService.java"]
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(llmClient.lastRequest().get().prompt())
                .contains("src/main/java/demo/UserService.java")
                .contains("return user.getName()")
                .doesNotContain("src/main/java/demo/OtherService.java")
                .doesNotContain("unselected change");
    }

    @Test
    void injectsRelevantDecisionMemoryIntoReviewPrompt() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("decision-aware-review-project", projectRoot.toString(), "main");

        String decisionResponse = mockMvc.perform(post("/api/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "projectId": %d,
                                  "title": "Delete endpoint authorization decision",
                                  "scenario": "DELETE endpoints that remove project data must have visible authorization and ownership checks.",
                                  "options": ["open local endpoint", "authorized destructive endpoint"],
                                  "decision": "Require explicit authorization or a clearly documented local-only boundary before exposing destructive project deletion.",
                                  "reasons": ["Prevents accidental or malicious data loss.", "Makes destructive operations reviewable."],
                                  "tradeOffs": ["Adds request validation.", "May require local development bypass configuration."],
                                  "applicableWhen": ["A controller adds DELETE endpoints.", "The endpoint removes project or indexed data."],
                                  "notApplicableWhen": ["The endpoint is test-only and not reachable from runtime."],
                                  "outcome": "Destructive APIs remain scoped and auditable.",
                                  "evidence": [
                                    {
                                      "type": "review",
                                      "ref": "mvp2-decision-memory-test",
                                      "summary": "Review should recall authorization decisions for DELETE endpoints."
                                    }
                                  ],
                                  "status": "active",
                                  "tags": ["review-memory-test"]
                                }
                                """.formatted(project.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(decisionResponse).path("data").path("decisionId").asLong()).isPositive();

        String createResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "feature/delete-project",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/ProjectController.java b/src/main/java/demo/ProjectController.java\\n+@DeleteMapping(\\\"/api/projects/{projectId}\\\")\\n+void deleteProject(Long projectId) { projectService.delete(projectId); }"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long reviewId = objectMapper.readTree(createResponse).path("data").path("reviewId").asLong();
        String prompt = llmClient.lastRequest().get().prompt();
        assertThat(prompt)
                .contains("Relevant engineering decisions recalled for this code review.")
                .contains("Delete endpoint authorization decision")
                .contains("Require explicit authorization");

        String eventsResponse = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("events");
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains("DECISION_MEMORY_RECALLED");
        assertThat(events)
                .filteredOn(event -> "DECISION_MEMORY_RECALLED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("outputSummary").asText()).contains("1 decision cards recalled"));
    }

    @Test
    void acceptedReviewFeedbackBecomesConfirmedReviewMemorySignal() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("confirmed-feedback-review-project", projectRoot.toString(), "main");

        String firstCreateResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "feature/null-risk",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\\n+User user = userRepository.findById(id);\\n+return user.getName();"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long firstReviewId = objectMapper.readTree(firstCreateResponse).path("data").path("reviewId").asLong();
        String firstDetailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", firstReviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long issueId = objectMapper.readTree(firstDetailResponse).path("data").path("issues").get(0).path("id").asLong();

        mockMvc.perform(patch("/api/review-issues/{issueId}", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "accepted",
                                  "note": "Confirmed nullable repository lookup pattern for this project."
                                }
                                """))
                .andExpect(status().isOk());

        String secondCreateResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "feature/another-null-risk",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\\n+User user = userRepository.findById(id);\\n+return user.getName();"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long secondReviewId = objectMapper.readTree(secondCreateResponse).path("data").path("reviewId").asLong();
        String prompt = llmClient.lastRequest().get().prompt();
        assertThat(prompt)
                .contains("Review memory signals from this project's prior human code-review feedback.")
                .contains("Confirmed issue patterns")
                .contains("[confirmed_issue_pattern] status=accepted")
                .contains("projectId=" + project.id())
                .contains("reviewId=" + firstReviewId)
                .contains("issueId=" + issueId)
                .contains("Confirmed nullable repository lookup pattern for this project.");

        String eventsResponse = mockMvc.perform(get("/api/reviews/{reviewId}/events", secondReviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("events");
        assertThat(events)
                .filteredOn(event -> "REVIEW_FEEDBACK_MEMORY_LOADED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("outputSummary").asText())
                        .contains("1 confirmed patterns, 0 false-positive patterns"));
    }

    @Test
    void injectsRecentHumanReviewFeedbackIntoNextReviewPrompt() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("feedback-aware-review-project", projectRoot.toString(), "main");

        String firstCreateResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "feature/null-risk",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\\n+User user = userRepository.findById(id);\\n+return user.getName();"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long firstReviewId = objectMapper.readTree(firstCreateResponse).path("data").path("reviewId").asLong();
        String firstDetailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", firstReviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long issueId = objectMapper.readTree(firstDetailResponse).path("data").path("issues").get(0).path("id").asLong();

        mockMvc.perform(patch("/api/review-issues/{issueId}", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "false_positive",
                                  "note": "Repository contract already guarantees a non-null user in this fixture."
                                }
                                """))
                .andExpect(status().isOk());

        String secondCreateResponse = mockMvc.perform(post("/api/projects/{projectId}/reviews", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "feature/another-null-risk",
                                  "mode": "strict",
                                  "diffText": "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\\n+User user = userRepository.findById(id);\\n+return user.getName();"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        long secondReviewId = objectMapper.readTree(secondCreateResponse).path("data").path("reviewId").asLong();
        assertThat(llmClient.lastRequest().get().prompt())
                .contains("Review memory signals from this project's prior human code-review feedback.")
                .contains("False-positive suppression patterns")
                .contains("[false_positive_pattern] status=false_positive")
                .contains("projectId=" + project.id())
                .contains("reviewId=" + firstReviewId)
                .contains("issueId=" + issueId)
                .contains("Repository contract already guarantees a non-null user in this fixture.")
                .contains("Do not repeat false_positive_pattern entries");

        String secondDetailResponse = mockMvc.perform(get("/api/reviews/{reviewId}", secondReviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(secondDetailResponse).path("data").path("issues")).isEmpty();

        String eventsResponse = mockMvc.perform(get("/api/reviews/{reviewId}/events", secondReviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode events = objectMapper.readTree(eventsResponse).path("data").path("events");
        assertThat(events)
                .extracting(event -> event.path("eventType").asText())
                .contains("REVIEW_FEEDBACK_MEMORY_LOADED");
        assertThat(events)
                .filteredOn(event -> "REVIEW_FEEDBACK_MEMORY_LOADED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("outputSummary").asText())
                        .contains("0 confirmed patterns, 1 false-positive patterns"));
        assertThat(events)
                .filteredOn(event -> "REVIEW_ISSUES_FILTERED".equals(event.path("eventType").asText()))
                .first()
                .satisfies(event -> assertThat(event.path("outputSummary").asText())
                        .contains("0 issues retained, 1 downgraded, 1 by prior feedback"));
    }

    private String readTestResource(String resourcePath) throws IOException {
        try (var input = Mvp2AiCodeReviewTests.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertThat(input).as("test resource %s", resourcePath).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private void initGitRepository(Path root) throws IOException, InterruptedException {
        runGit(root, "init", "-b", "main");
        runGit(root, "config", "user.email", "devcontext@example.local");
        runGit(root, "config", "user.name", "DevContext Test");
        runGit(root, "add", ".");
        runGit(root, "commit", "-m", "initial fixture");
    }

    private void runGit(Path root, String... args) throws IOException, InterruptedException {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(java.util.List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("git command failed: " + output);
        }
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
        private final AtomicReference<String> nextResponse = new AtomicReference<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            lastRequest.set(request);
            String response = nextResponse.getAndSet(null);
            if (response == null) {
                response = """
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
                    """;
            }
            return new LlmResponse(response, request.modelName(), 180, 72);
        }

        AtomicReference<LlmRequest> lastRequest() {
            return lastRequest;
        }

        void nextResponse(String response) {
            nextResponse.set(response);
        }
    }
}
