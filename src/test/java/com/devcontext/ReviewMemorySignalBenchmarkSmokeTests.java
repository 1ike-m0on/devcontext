package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.application.memory.ObservationCaptureService;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.memory.ObservationSourceType;
import com.devcontext.domain.memory.ReportObservationSnapshot;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
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
        "spring.datasource.url=jdbc:sqlite:target/devcontext-review-memory-smoke-test.sqlite",
        "devcontext.llm.provider=test",
        "devcontext.vector.provider=jdbc"
})
@AutoConfigureMockMvc
@Import(ReviewMemorySignalBenchmarkSmokeTests.TestLlmConfig.class)
class ReviewMemorySignalBenchmarkSmokeTests {

    private static final String REPORT_SCHEMA = "review-memory-signal-smoke";
    private static final int REPORT_VERSION = 1;
    private static final String SUITE = "review-memory-signal-benchmark-smoke";
    private static final String CASE_NAME = "false-positive-suppression-memory";
    private static final String MODE = "strict";
    private static final String PROVIDER = "test";
    private static final String MODEL = "mock-llm";
    private static final String FAILURE_CATEGORY_NONE = "none";
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    static {
        try {
            Files.deleteIfExists(Path.of("target/devcontext-review-memory-smoke-test.sqlite"));
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
    private SmokeReviewLlmClient llmClient;

    @Autowired
    private ObservationCaptureService observationCaptureService;

    @TempDir
    private Path projectRoot;

    @Test
    void reviewMemorySignalBenchmarkSmokeShowsFalsePositiveSuppressionAffectsLaterReview() throws Exception {
        createReviewFixture(projectRoot);
        Project project = projectService.createProject("review-memory-signal-benchmark-smoke", projectRoot.toString(), "main");
        String feedbackNote = "Benchmark smoke: repository contract already guarantees a non-null user.";
        String diffText = "diff --git a/src/main/java/demo/UserService.java b/src/main/java/demo/UserService.java\n"
                + "+User user = userRepository.findById(id);\n"
                + "+return user.getName();";

        JsonNode firstCreate = createReview(project.id(), "feature/benchmark-prior-false-positive", diffText);
        long firstReviewId = firstCreate.path("reviewId").asLong();
        JsonNode firstDetail = reviewDetail(firstReviewId);
        assertThat(firstDetail.path("issues")).hasSize(1);
        long issueId = firstDetail.path("issues").get(0).path("id").asLong();

        updateIssueStatus(issueId, "false_positive", feedbackNote);

        JsonNode secondCreate = createReview(project.id(), "feature/benchmark-later-review", diffText);
        long secondReviewId = secondCreate.path("reviewId").asLong();
        JsonNode secondDetail = reviewDetail(secondReviewId);
        JsonNode secondEvents = reviewEvents(secondReviewId);
        JsonNode signal = secondCreate.path("reviewMemorySignals").get(0);
        String feedbackLoadedSummary = eventOutput(secondEvents.path("events"), "REVIEW_FEEDBACK_MEMORY_LOADED");
        String coverageSummary = eventOutput(secondEvents.path("events"), "REVIEW_CONTEXT_COVERAGE_RECORDED");
        String filteredSummary = eventOutput(secondEvents.path("events"), "REVIEW_ISSUES_FILTERED");
        int beforeIssueCount = firstDetail.path("issues").size();
        int afterIssueCount = secondDetail.path("issues").size();

        assertThat(secondCreate.path("reviewMemorySignals")).hasSize(1);
        assertThat(signal.path("signalType").asText()).isEqualTo("false_positive_pattern");
        assertThat(signal.path("feedbackStatus").asText()).isEqualTo("false_positive");
        assertThat(signal.path("projectId").asLong()).isEqualTo(project.id());
        assertThat(signal.path("reviewId").asLong()).isEqualTo(firstReviewId);
        assertThat(signal.path("issueId").asLong()).isEqualTo(issueId);
        assertThat(signal.path("note").asText()).isEqualTo(feedbackNote);
        assertThat(secondCreate.path("contextCoverage").path("reviewMemorySignals").asBoolean()).isTrue();
        assertThat(secondCreate.path("contextCoverage").path("sourceTypes"))
                .extracting(JsonNode::asText)
                .contains("REVIEW_FEEDBACK_MEMORY");
        assertThat(beforeIssueCount).isEqualTo(1);
        assertThat(afterIssueCount).isZero();
        assertThat(secondDetail.path("reviewMemorySignals")).hasSize(1);
        assertThat(secondEvents.path("reviewMemorySignals")).hasSize(1);
        assertThat(feedbackLoadedSummary).contains("1 false-positive patterns");
        assertThat(coverageSummary)
                .contains("reviewMemorySignals=true")
                .contains("REVIEW_FEEDBACK_MEMORY");
        assertThat(filteredSummary).contains("0 issues retained, 1 downgraded, 1 by prior feedback");
        assertThat(secondDetail.path("outcomeSummary").path("total").asInt()).isZero();
        assertThat(secondDetail.path("outcomeSummary").path("pending").asInt()).isZero();
        assertThat(llmClient.lastRequest().get().prompt()).contains(feedbackNote);

        SmokeReport report = writeSmokeReport(
                project,
                firstReviewId,
                issueId,
                secondReviewId,
                beforeIssueCount,
                afterIssueCount,
                secondCreate,
                secondDetail,
                signal,
                feedbackLoadedSummary,
                coverageSummary,
                filteredSummary
        );
        assertSmokeReportContract(report, project, firstReviewId, issueId, secondReviewId, beforeIssueCount, afterIssueCount);
    }

    private JsonNode createReview(long projectId, String compareBranch, String diffText) throws Exception {
        String response = mockMvc.perform(post("/api/projects/{projectId}/reviews", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceType": "manual",
                                  "baseBranch": "main",
                                  "compareBranch": "%s",
                                  "mode": "strict",
                                  "diffText": "%s"
                                }
                                """.formatted(compareBranch, escapeJson(diffText))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode reviewDetail(long reviewId) throws Exception {
        String response = mockMvc.perform(get("/api/reviews/{reviewId}", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private JsonNode reviewEvents(long reviewId) throws Exception {
        String response = mockMvc.perform(get("/api/reviews/{reviewId}/events", reviewId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).path("data");
    }

    private void updateIssueStatus(long issueId, String status, String note) throws Exception {
        mockMvc.perform(patch("/api/review-issues/{issueId}", issueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "%s",
                                  "note": "%s"
                                }
                                """.formatted(status, escapeJson(note))))
                .andExpect(status().isOk());
    }

    private String eventOutput(JsonNode events, String eventType) {
        for (JsonNode event : events) {
            if (eventType.equals(event.path("eventType").asText())) {
                return event.path("outputSummary").asText();
            }
        }
        return "";
    }

    private SmokeReport writeSmokeReport(
            Project project,
            long firstReviewId,
            long issueId,
            long secondReviewId,
            int beforeIssueCount,
            int afterIssueCount,
            JsonNode secondCreate,
            JsonNode secondDetail,
            JsonNode signal,
            String feedbackLoadedSummary,
            String coverageSummary,
            String filteredSummary
    ) throws IOException {
        Instant generatedAt = Instant.now();
        String runId = "review-memory-signal-smoke-" + RUN_ID_FORMAT.format(generatedAt);
        Path reportDir = Path.of("target", "review-memory-signal-benchmark-smoke");
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve(runId + ".json");
        Path markdownPath = reportDir.resolve(runId + ".md");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", REPORT_SCHEMA);
        payload.put("version", REPORT_VERSION);
        payload.put("runId", runId);
        payload.put("runTimestamp", generatedAt.toString());
        payload.put("generatedAt", generatedAt.toString());
        payload.put("suite", SUITE);
        payload.put("mode", MODE);
        payload.put("provider", PROVIDER);
        payload.put("model", MODEL);
        payload.put("caseName", CASE_NAME);
        payload.put("harnessNote", "Focused offline Spring smoke because the PowerShell benchmark harness uses mock LLM echo responses and cannot seed stateful ReviewIssue feedback by itself.");
        payload.put("projectName", project.name());
        payload.put("projectId", project.id());
        payload.put("projectRoot", project.rootPath());
        payload.put("priorReviewId", firstReviewId);
        payload.put("priorIssueId", issueId);
        payload.put("laterReviewId", secondReviewId);
        payload.put("signalCount", secondCreate.path("reviewMemorySignals").size());
        payload.put("memorySignalType", signal.path("signalType").asText());
        payload.put("memorySignalStatus", signal.path("feedbackStatus").asText());
        payload.put("memorySignalSourceProjectId", signal.path("projectId").asLong());
        payload.put("memorySignalSourceReviewId", signal.path("reviewId").asLong());
        payload.put("memorySignalSourceIssueId", signal.path("issueId").asLong());
        payload.put("contextCoverage", secondCreate.path("contextCoverage"));
        payload.put("sourceTypes", secondCreate.path("contextCoverage").path("sourceTypes"));
        payload.put("memorySignal", signal);
        payload.put("beforeIssueCount", beforeIssueCount);
        payload.put("afterIssueCount", afterIssueCount);
        payload.put("laterIssueCount", afterIssueCount);
        payload.put("laterOutcomeSummary", secondDetail.path("outcomeSummary"));
        payload.put("feedbackLoadedSummary", feedbackLoadedSummary);
        payload.put("contextCoverageSummary", coverageSummary);
        payload.put("reviewIssuesFilteredSummary", filteredSummary);
        payload.put("filteredByPriorFeedback", true);
        payload.put("filteredByPriorFeedbackCount", beforeIssueCount - afterIssueCount);
        payload.put("failureCategory", FAILURE_CATEGORY_NONE);
        payload.put("smokeOutcome", Map.of(
                "success", true,
                "memorySignalLoaded", true,
                "suppressionAffectedLaterReview", true,
                "beforeIssueCount", beforeIssueCount,
                "afterIssueCount", afterIssueCount,
                "filteredByPriorFeedback", true,
                "filteredByPriorFeedbackCount", beforeIssueCount - afterIssueCount,
                "failureCategory", FAILURE_CATEGORY_NONE,
                "laterReviewRetainedIssues", afterIssueCount
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), payload);

        Files.writeString(markdownPath, """
                # Review Memory Signal Benchmark Smoke

                ## Contract Summary

                - Schema: `%s`
                - Version: `%d`
                - Suite: `%s`
                - Mode: `%s`
                - Provider: `%s`
                - Model: `%s`
                - Run timestamp: `%s`
                - Project: `%s`
                - Case: `%s`
                - Failure category: `%s`

                ## Outcome

                - Success: `true`
                - Smoke path: prior `false_positive` feedback memory signal -> later Review
                - Filtered by prior feedback: `true`
                - Filtered by prior feedback count: `%d`
                - Before issue count: `%d`
                - After issue count: `%d`
                - Prior Review: `%d`
                - Prior Issue: `%d`
                - Later Review: `%d`
                - Review memory signal count: `%d`
                - Later retained issues: `%d`
                - Context coverage reviewMemorySignals: `%s`
                - Context source types: `%s`

                ## Memory Signal

                - Signal type: `%s`
                - Feedback status: `%s`
                - Source project id: `%d`
                - Source review id: `%d`
                - Source issue id: `%d`
                - Human note: `%s`

                ## Trace Evidence

                - Feedback memory loaded: `%s`
                - Context coverage: `%s`
                - Issue filtering: `%s`

                ## Harness Note

                The regular PowerShell CodeReview benchmark harness stays stateless. This focused offline Spring smoke writes JSON and Markdown benchmark artifacts while exercising the existing Review create, feedback PATCH, detail, and event APIs.
                """.formatted(
                REPORT_SCHEMA,
                REPORT_VERSION,
                SUITE,
                MODE,
                PROVIDER,
                MODEL,
                generatedAt,
                project.name(),
                CASE_NAME,
                FAILURE_CATEGORY_NONE,
                beforeIssueCount - afterIssueCount,
                beforeIssueCount,
                afterIssueCount,
                firstReviewId,
                issueId,
                secondReviewId,
                secondCreate.path("reviewMemorySignals").size(),
                afterIssueCount,
                secondCreate.path("contextCoverage").path("reviewMemorySignals").asBoolean(),
                joinText(secondCreate.path("contextCoverage").path("sourceTypes")),
                signal.path("signalType").asText(),
                signal.path("feedbackStatus").asText(),
                signal.path("projectId").asLong(),
                signal.path("reviewId").asLong(),
                signal.path("issueId").asLong(),
                signal.path("note").asText(),
                feedbackLoadedSummary,
                coverageSummary,
                filteredSummary
        ));
        observationCaptureService.captureReport(new ReportObservationSnapshot(
                project.id(),
                ObservationSourceType.BENCHMARK_REPORT,
                runId,
                SUITE,
                REPORT_SCHEMA,
                "REVIEW_MEMORY_SIGNAL_SMOKE",
                PROVIDER,
                MODEL,
                "success",
                FAILURE_CATEGORY_NONE,
                "Review memory signal benchmark smoke completed.",
                jsonPath.toString(),
                generatedAt,
                Map.of(
                        "caseCount", 1,
                        "completed", 1,
                        "failed", 0,
                        "skipped", 0,
                        "signalCount", secondCreate.path("reviewMemorySignals").size(),
                        "beforeIssueCount", beforeIssueCount,
                        "afterIssueCount", afterIssueCount,
                        "filteredByPriorFeedback", true,
                        "filteredByPriorFeedbackCount", beforeIssueCount - afterIssueCount
                )
        ));
        return new SmokeReport(jsonPath, markdownPath);
    }

    private void assertSmokeReportContract(
            SmokeReport report,
            Project project,
            long firstReviewId,
            long issueId,
            long secondReviewId,
            int beforeIssueCount,
            int afterIssueCount
    ) throws Exception {
        JsonNode json = objectMapper.readTree(report.jsonPath().toFile());
        assertThat(json.path("schema").asText()).isEqualTo(REPORT_SCHEMA);
        assertThat(json.path("version").asInt()).isEqualTo(REPORT_VERSION);
        assertThat(json.path("suite").asText()).isEqualTo(SUITE);
        assertThat(json.path("mode").asText()).isEqualTo(MODE);
        assertThat(json.path("provider").asText()).isEqualTo(PROVIDER);
        assertThat(json.path("model").asText()).isEqualTo(MODEL);
        assertThat(json.path("runTimestamp").asText()).isNotBlank();
        assertThat(json.path("projectName").asText()).isEqualTo(project.name());
        assertThat(json.path("caseName").asText()).isEqualTo(CASE_NAME);
        assertThat(json.path("memorySignalType").asText()).isEqualTo("false_positive_pattern");
        assertThat(json.path("memorySignalStatus").asText()).isEqualTo("false_positive");
        assertThat(json.path("memorySignalSourceProjectId").asLong()).isEqualTo(project.id());
        assertThat(json.path("memorySignalSourceReviewId").asLong()).isEqualTo(firstReviewId);
        assertThat(json.path("memorySignalSourceIssueId").asLong()).isEqualTo(issueId);
        assertThat(json.path("contextCoverage").path("reviewMemorySignals").asBoolean()).isTrue();
        assertThat(json.path("sourceTypes")).extracting(JsonNode::asText).contains("REVIEW_FEEDBACK_MEMORY");
        assertThat(json.path("beforeIssueCount").asInt()).isEqualTo(beforeIssueCount);
        assertThat(json.path("afterIssueCount").asInt()).isEqualTo(afterIssueCount);
        assertThat(json.path("filteredByPriorFeedback").asBoolean()).isTrue();
        assertThat(json.path("filteredByPriorFeedbackCount").asInt()).isEqualTo(beforeIssueCount - afterIssueCount);
        assertThat(json.path("failureCategory").asText()).isEqualTo(FAILURE_CATEGORY_NONE);
        assertThat(json.path("laterReviewId").asLong()).isEqualTo(secondReviewId);

        String markdown = Files.readString(report.markdownPath());
        assertThat(markdown)
                .contains("## Contract Summary")
                .contains("- Schema: `" + REPORT_SCHEMA + "`")
                .contains("- Version: `" + REPORT_VERSION + "`")
                .contains("- Suite: `" + SUITE + "`")
                .contains("- Mode: `" + MODE + "`")
                .contains("- Provider: `" + PROVIDER + "`")
                .contains("- Model: `" + MODEL + "`")
                .contains("- Run timestamp: `")
                .contains("- Project: `" + project.name() + "`")
                .contains("- Case: `" + CASE_NAME + "`")
                .contains("- Failure category: `" + FAILURE_CATEGORY_NONE + "`")
                .contains("- Signal type: `false_positive_pattern`")
                .contains("- Feedback status: `false_positive`")
                .contains("- Source project id: `" + project.id() + "`")
                .contains("- Source review id: `" + firstReviewId + "`")
                .contains("- Source issue id: `" + issueId + "`")
                .contains("- Before issue count: `" + beforeIssueCount + "`")
                .contains("- After issue count: `" + afterIssueCount + "`")
                .contains("- Filtered by prior feedback: `true`")
                .contains("- Context coverage reviewMemorySignals: `true`")
                .contains("REVIEW_FEEDBACK_MEMORY");

        assertReportObservationAvailable(report, project, beforeIssueCount, afterIssueCount);
    }

    private void assertReportObservationAvailable(
            SmokeReport report,
            Project project,
            int beforeIssueCount,
            int afterIssueCount
    ) throws Exception {
        JsonNode json = objectMapper.readTree(report.jsonPath().toFile());
        String observationsResponse = mockMvc.perform(get("/api/observations")
                        .param("projectId", String.valueOf(project.id()))
                        .param("taskType", "REVIEW_MEMORY_SIGNAL_SMOKE")
                        .param("lifecycle", "raw")
                        .param("limit", "20"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode observations = objectMapper.readTree(observationsResponse).path("data");
        JsonNode observation = null;
        for (JsonNode candidate : observations) {
            if (json.path("runId").asText().equals(candidate.path("reportRunId").asText())) {
                observation = candidate;
                break;
            }
        }

        assertThat(observation).as("report observation for %s", json.path("runId").asText()).isNotNull();
        assertThat(observation.path("sourceType").asText()).isEqualTo(ObservationSourceType.BENCHMARK_REPORT.value());
        assertThat(observation.path("sourceStatus").asText()).isEqualTo("success");
        assertThat(observation.path("provider").asText()).isEqualTo(PROVIDER);
        assertThat(observation.path("modelName").asText()).isEqualTo(MODEL);
        assertThat(observation.path("reportPath").asText()).isEqualTo(report.jsonPath().toString());
        assertThat(observation.path("summary").asText())
                .contains("caseCount=1")
                .contains("beforeIssueCount=" + beforeIssueCount)
                .contains("afterIssueCount=" + afterIssueCount);
        assertThat(observation.path("errorType").isNull()).isTrue();

        JsonNode metadata = objectMapper.readTree(observation.path("metadataJson").asText());
        assertThat(metadata.path("suite").asText()).isEqualTo(SUITE);
        assertThat(metadata.path("reportType").asText()).isEqualTo(REPORT_SCHEMA);
        assertThat(metadata.path("status").asText()).isEqualTo("success");
        assertThat(metadata.path("failureCategory").asText()).isEqualTo(FAILURE_CATEGORY_NONE);
        assertThat(metadata.path("generatedAt").asText()).isEqualTo(json.path("generatedAt").asText());
        assertThat(metadata.path("summaryMetrics").path("caseCount").asInt()).isEqualTo(1);
        assertThat(metadata.path("summaryMetrics").path("completed").asInt()).isEqualTo(1);
        assertThat(metadata.path("summaryMetrics").path("failed").asInt()).isZero();
        assertThat(metadata.path("summaryMetrics").path("filteredByPriorFeedback").asBoolean()).isTrue();
        assertThat(metadata.path("summaryMetrics").path("filteredByPriorFeedbackCount").asInt())
                .isEqualTo(beforeIssueCount - afterIssueCount);
    }

    private String joinText(JsonNode values) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode value : values) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(value.asText());
        }
        return builder.toString();
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

    private String escapeJson(String value) throws IOException {
        return objectMapper.writeValueAsString(value).replaceFirst("^\"", "").replaceFirst("\"$", "");
    }

    private record SmokeReport(Path jsonPath, Path markdownPath) {
    }

    @TestConfiguration
    static class TestLlmConfig {

        @Bean
        SmokeReviewLlmClient smokeReviewLlmClient() {
            return new SmokeReviewLlmClient();
        }
    }

    static class SmokeReviewLlmClient implements LlmClient {

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
